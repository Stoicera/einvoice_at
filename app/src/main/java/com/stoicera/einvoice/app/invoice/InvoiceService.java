package com.stoicera.einvoice.app.invoice;

import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.stoicera.einvoice.app.audit.AuditAction;
import com.stoicera.einvoice.app.audit.AuditService;
import com.stoicera.einvoice.app.persistence.InvoiceEntity;
import com.stoicera.einvoice.app.persistence.InvoiceRepository;
import com.stoicera.einvoice.app.persistence.ReportEntity;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.formats.ubl.Ubl21CreditNoteStrategy;
import com.stoicera.einvoice.formats.ubl.Ubl21InvoiceStrategy;
import com.stoicera.einvoice.mapping.ebinterface.InvoiceToEbInterface61Mapper;
import com.stoicera.einvoice.mapping.json.InvoiceJsonReader;
import com.stoicera.einvoice.mapping.ubl.InvoiceToUblMapper;
import com.stoicera.einvoice.mapping.ubl.UblDocument;
import com.stoicera.einvoice.rendering.InvoicePdfRenderer;
import com.stoicera.einvoice.validation.InvoiceValidator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Application service behind the invoices API: it owns the create → generate → validate → persist
 * flow and the tenant-scoped reads.
 *
 * <p>Create runs in a single transaction: the invoice row, its report row and the audit event are
 * committed together or not at all. A duplicate {@code (tenant, invoiceNumber)} is detected by the
 * database's unique index (translated from {@link DataIntegrityViolationException}), never by a
 * check-then-insert race. XML is never stored — the ebInterface document is regenerated from the
 * canonical JSON on demand.
 */
@Service
public class InvoiceService {

  /** Largest listing page the API will serve; larger requests are clamped down, never rejected. */
  static final int MAX_PAGE_SIZE = 100;

  private final InvoiceRepository invoices;
  private final ReportRepository reports;
  private final AuditService audit;
  private final InvoiceJsonReader jsonReader;
  private final InvoiceToEbInterface61Mapper ebiMapper;
  private final EbInterface61Strategy ebiStrategy;
  private final InvoiceValidator validator;
  private final InvoiceToUblMapper ublMapper;
  private final Ubl21InvoiceStrategy ublInvoiceStrategy;
  private final Ubl21CreditNoteStrategy ublCreditNoteStrategy;
  private final InvoicePdfRenderer pdfRenderer;

  /**
   * Serializes the findings list into the report's JSONB column. A dedicated Jackson 3 mapper: the
   * stored findings are the same {@code Finding} records the wire contract exposes, and those carry
   * no date/Optional/naming-sensitive fields, so a default mapper's output is byte-identical to the
   * MVC response mapper's — there is nothing to configure.
   */
  private final JsonMapper findingsMapper = JsonMapper.builder().build();

  public InvoiceService(
      InvoiceRepository invoices,
      ReportRepository reports,
      AuditService audit,
      InvoiceJsonReader jsonReader,
      InvoiceToEbInterface61Mapper ebiMapper,
      EbInterface61Strategy ebiStrategy,
      InvoiceValidator validator,
      InvoiceToUblMapper ublMapper,
      Ubl21InvoiceStrategy ublInvoiceStrategy,
      Ubl21CreditNoteStrategy ublCreditNoteStrategy,
      InvoicePdfRenderer pdfRenderer) {
    this.invoices = invoices;
    this.reports = reports;
    this.audit = audit;
    this.jsonReader = jsonReader;
    this.ebiMapper = ebiMapper;
    this.ebiStrategy = ebiStrategy;
    this.validator = validator;
    this.ublMapper = ublMapper;
    this.ublInvoiceStrategy = ublInvoiceStrategy;
    this.ublCreditNoteStrategy = ublCreditNoteStrategy;
    this.pdfRenderer = pdfRenderer;
  }

  /**
   * Creates an invoice from its raw canonical-JSON request body.
   *
   * <p>The body bytes are consumed exactly once: hashed for the audit trail, then parsed. The
   * received text is stored as the canonical form, as received (the jsonb column normalizes
   * whitespace/key order — see ADR-0005; byte-exactness is preserved via the audit SHA-256). The
   * extracted columns come from the parsed {@link Invoice}. The generated ebInterface document is
   * validated and its report persisted and returned — a report with findings does not stop
   * creation.
   *
   * @throws com.stoicera.einvoice.mapping.json.InvoiceJsonException the body is not the canonical
   *     JSON shape (mapped to 400)
   * @throws com.stoicera.einvoice.core.InvariantViolationException the JSON is well-formed but
   *     describes an invoice that violates a domain invariant (mapped to 422)
   * @throws DuplicateInvoiceException the tenant already has an invoice with this number (mapped to
   *     409)
   */
  @Transactional
  public InvoiceCreated create(UUID tenantId, byte[] body) {
    String payloadSha256 = sha256Hex(body);
    Invoice invoice = jsonReader.read(new ByteArrayInputStream(body));
    String canonical = new String(body, StandardCharsets.UTF_8);

    ValidationReport report = validate(invoice);

    InvoiceEntity invoiceEntity =
        new InvoiceEntity(
            tenantId,
            invoice.invoiceNumber(),
            invoice.type().code(),
            invoice.issueDate(),
            invoice.currency().getCurrencyCode(),
            invoice.totals().payableAmount().amount(),
            invoice.seller().name(),
            invoice.buyer().name(),
            canonical);
    try {
      // Flush now so the unique-index violation surfaces here as a catchable exception rather than
      // escaping unhandled at transaction commit.
      invoices.saveAndFlush(invoiceEntity);
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateInvoiceException(invoice.invoiceNumber(), e);
    }

    reports.save(
        new ReportEntity(
            tenantId,
            invoiceEntity.getId(),
            report.sourceFormat(),
            report.profile(),
            report.isValid(),
            findingsMapper.writeValueAsString(report.findings())));

    audit.record(tenantId, AuditAction.INVOICE_CREATED, payloadSha256);

    return new InvoiceCreated(invoiceEntity.getId(), report);
  }

  /**
   * Returns the stored canonical JSON for one of the tenant's invoices, as received (the jsonb
   * column normalizes whitespace/key order — see ADR-0005; byte-exactness is preserved via the
   * audit SHA-256).
   */
  @Transactional(readOnly = true)
  public String canonicalJson(UUID tenantId, UUID id) {
    return invoices
        .findByIdAndTenantId(id, tenantId)
        .map(InvoiceEntity::getCanonical)
        .orElseThrow(() -> new InvoiceNotFoundException(id));
  }

  /**
   * Regenerates the ebInterface 6.1 XML for one of the tenant's invoices from its stored canonical
   * JSON. XML is never persisted, so it is always produced fresh through the reader → mapper →
   * strategy chain.
   */
  @Transactional(readOnly = true)
  public String ebInterfaceXml(UUID tenantId, UUID id) {
    return ebiStrategy.write(ebiMapper.map(reread(tenantId, id)));
  }

  /**
   * Regenerates the Peppol BIS Billing 3.0 UBL XML for one of the tenant's invoices — a {@code
   * ubl:Invoice} or a {@code ubl:CreditNote}, decided by the invoice's own BT-3 type code. XML is
   * never persisted, so it is always produced fresh through the reader → mapper → strategy chain.
   */
  @Transactional(readOnly = true)
  public String ublXml(UUID tenantId, UUID id) {
    return switch (ublMapper.map(reread(tenantId, id))) {
      case UblDocument.CommercialInvoice(var document) -> ublInvoiceStrategy.write(document);
      case UblDocument.CreditNote(var document) -> ublCreditNoteStrategy.write(document);
    };
  }

  /**
   * Renders one of the tenant's invoices as a German PDF print view.
   *
   * <p>Rendered from the stored canonical JSON, never from a generated XML document: the PDF, the
   * ebInterface output and the UBL output are three views of one invoice, and taking them all from
   * the same source is what keeps them from disagreeing.
   */
  @Transactional(readOnly = true)
  public byte[] pdf(UUID tenantId, UUID id) {
    return pdfRenderer.render(reread(tenantId, id));
  }

  /** The stored canonical JSON, parsed back into the domain model. */
  private Invoice reread(UUID tenantId, UUID id) {
    String canonical = canonicalJson(tenantId, id);
    return jsonReader.read(new ByteArrayInputStream(canonical.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Lists the tenant's invoices, newest first. {@code size} is clamped to {@code [1, }{@value
   * #MAX_PAGE_SIZE}{@code ]} and {@code page} to {@code >= 0}; out-of-range values are clamped,
   * never rejected.
   */
  @Transactional(readOnly = true)
  public InvoicePage list(UUID tenantId, int page, int size) {
    int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int clampedPage = Math.max(page, 0);
    Page<InvoiceEntity> result =
        invoices.findByTenantId(
            tenantId,
            PageRequest.of(clampedPage, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt")));

    Map<UUID, Boolean> validByInvoice = validityByInvoice(result.getContent());
    List<InvoiceSummary> content =
        result.getContent().stream()
            .map(e -> InvoiceSummary.of(e, validByInvoice.getOrDefault(e.getId(), false)))
            .toList();

    return new InvoicePage(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  private ValidationReport validate(Invoice invoice) {
    Ebi61InvoiceType ebi = ebiMapper.map(invoice);
    String xml = ebiStrategy.write(ebi);
    return validator.validate(xml.getBytes(StandardCharsets.UTF_8));
  }

  /** Maps each invoice id in the page to the {@code valid} flag of its most recent report. */
  private Map<UUID, Boolean> validityByInvoice(List<InvoiceEntity> page) {
    if (page.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = page.stream().map(InvoiceEntity::getId).toList();
    Map<UUID, ReportEntity> latest = new HashMap<>();
    for (ReportEntity report : reports.findByInvoiceIdIn(ids)) {
      latest.merge(
          report.getInvoiceId(),
          report,
          (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b);
    }
    Map<UUID, Boolean> valid = new HashMap<>();
    latest.forEach((invoiceId, report) -> valid.put(invoiceId, report.isValid()));
    return valid;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated on every JVM; its absence is a broken runtime, not a recoverable state.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
