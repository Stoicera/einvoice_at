package com.stoicera.einvoice.app.convert;

import com.stoicera.einvoice.app.audit.AuditAction;
import com.stoicera.einvoice.app.audit.AuditService;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.formats.ubl.Ubl21CreditNoteStrategy;
import com.stoicera.einvoice.formats.ubl.Ubl21InvoiceStrategy;
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import com.stoicera.einvoice.mapping.conversion.ConversionLosses;
import com.stoicera.einvoice.mapping.conversion.ConversionReport;
import com.stoicera.einvoice.mapping.conversion.TargetFormat;
import com.stoicera.einvoice.mapping.ebinterface.EbInterface61ToInvoiceMapper;
import com.stoicera.einvoice.mapping.ebinterface.InvoiceToEbInterface61Mapper;
import com.stoicera.einvoice.mapping.ubl.InvoiceToUblMapper;
import com.stoicera.einvoice.mapping.ubl.UblDocument;
import com.stoicera.einvoice.mapping.ubl.UblToInvoiceMapper;
import com.stoicera.einvoice.validation.DocumentFormat;
import com.stoicera.einvoice.validation.InvoiceValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Converts an uploaded invoice document from one format to the other, reporting what the trip cost.
 *
 * <h2>Through the canonical model, never syntax to syntax</h2>
 *
 * <p>A direct ebInterface→UBL transformation would need a second, independent understanding of both
 * standards and would drift from the one the rest of the platform uses. Converting through the
 * canonical model means the invoice is understood once, by the model that already derives and
 * re-verifies every amount (ADR-0003) — so a conversion cannot silently change a total, and
 * whatever the model cannot represent is visible as a loss instead of disappearing.
 *
 * <p>That is also why every conversion runs four steps and not two: <em>read</em> the source (which
 * discovers deviations), <em>analyse</em> what the target cannot carry, <em>write</em> the target,
 * and <em>validate</em> the result. The last step matters most in practice: converting an
 * ebInterface invoice to UBL produces a document that must satisfy Peppol, and the caller learns
 * that here rather than from a rejection at an access point.
 */
@Service
public class ConversionService {

  private final AuditService audit;
  private final InvoiceValidator validator;
  private final EbInterface61Strategy ebiStrategy;
  private final Ubl21InvoiceStrategy ublInvoiceStrategy;
  private final Ubl21CreditNoteStrategy ublCreditNoteStrategy;
  private final InvoiceToEbInterface61Mapper toEbInterface;
  private final EbInterface61ToInvoiceMapper fromEbInterface;
  private final InvoiceToUblMapper toUbl;
  private final UblToInvoiceMapper fromUbl;

  public ConversionService(
      AuditService audit,
      InvoiceValidator validator,
      EbInterface61Strategy ebiStrategy,
      Ubl21InvoiceStrategy ublInvoiceStrategy,
      Ubl21CreditNoteStrategy ublCreditNoteStrategy,
      InvoiceToEbInterface61Mapper toEbInterface,
      EbInterface61ToInvoiceMapper fromEbInterface,
      InvoiceToUblMapper toUbl,
      UblToInvoiceMapper fromUbl) {
    this.audit = audit;
    this.validator = validator;
    this.ebiStrategy = ebiStrategy;
    this.ublInvoiceStrategy = ublInvoiceStrategy;
    this.ublCreditNoteStrategy = ublCreditNoteStrategy;
    this.toEbInterface = toEbInterface;
    this.fromEbInterface = fromEbInterface;
    this.toUbl = toUbl;
    this.fromUbl = fromUbl;
  }

  /**
   * Converts {@code source} from {@code from} to {@code to}.
   *
   * @param tenantId the caller's tenant, audited against the payload hash
   * @param source the uploaded document bytes
   * @param from the format the caller says the upload is in
   * @param to the format to produce
   * @throws UnsupportedConversionException {@code from} equals {@code to}, or the upload is not in
   *     the format the caller declared
   * @throws com.stoicera.einvoice.core.InvariantViolationException the document parses but
   *     describes an invoice the canonical model rejects (mapped to 422)
   */
  // Deliberately NOT @Transactional. It was, and that was a scalability defect the M4 hostile
  // review caught (finding F8): the annotation opened a database transaction, and therefore held a
  // HikariCP connection, across the read, both mappings, the write AND a full Peppol XSLT
  // validation run — seconds of pure CPU on a real document — in order to protect a single audit
  // INSERT on the last line. Under concurrency the connection pool, which has nothing to do with
  // any of that work, is the first thing to exhaust.
  //
  // Nothing is lost by removing it: the only database write is AuditService.record, which carries
  // its own @Transactional and so still commits atomically. There is no second write for it to be
  // atomic *with*. The conversion itself is a pure function over the upload — it persists nothing,
  // so there is nothing a rollback could undo.
  public ConvertResult convert(
      UUID tenantId, byte[] source, ConversionFormat from, ConversionFormat to) {
    if (from == to) {
      throw new UnsupportedConversionException(
          "Source and target format are the same; there is nothing to convert.");
    }

    CanonicalResult read = read(source, from);
    List<Finding> notes = new ArrayList<>(read.notes());
    notes.addAll(ConversionLosses.writingTo(read.invoice(), to.targetFormat()));

    String xml = write(read.invoice(), to);
    ValidationReport report = validator.validate(xml.getBytes(StandardCharsets.UTF_8));

    audit.record(tenantId, AuditAction.CONVERSION_RUN, sha256Hex(source));

    return new ConvertResult(new ConversionReport(from.id(), to.id(), notes), xml, report);
  }

  /**
   * Reads the upload into the canonical model, after confirming it really is in the declared
   * format.
   *
   * <p>The declared format is checked rather than trusted: a caller who says {@code
   * from=ebinterface} and uploads UBL would otherwise get a confusing parse failure from deep
   * inside a mapper instead of a clear "that is not what you said it was".
   *
   * <p><strong>The order of the two steps below is a security boundary, not a formality.</strong>
   * {@link InvoiceValidator#detectFormat} parses through {@code SecureXml}, which refuses a
   * document that so much as declares a {@code DOCTYPE}; such a document therefore detects as
   * {@link DocumentFormat#UNKNOWN} and is rejected here, before its bytes ever reach a format
   * adapter's own JAXB reader — a parser this module does not configure and must not assume is
   * hardened. Moving the detection after the read, or skipping it on a branch where its result
   * looks unused, would open an XXE path. {@code ConvertApiIT
   * .neverResolvesAnExternalEntityInAnUploadedDocument} pins it (M4 hostile review, finding F10).
   */
  private CanonicalResult read(byte[] source, ConversionFormat from) {
    DocumentFormat detected = validator.detectFormat(source);

    return switch (from) {
      case EBINTERFACE -> {
        requireDetected(detected, from, DocumentFormat.EBINTERFACE_61);
        yield fromEbInterface.map(requireParsed(ebiStrategy.read(source).document(), from));
      }
      case UBL -> {
        if (detected == DocumentFormat.UBL_CREDIT_NOTE) {
          yield fromUbl.map(requireParsed(ublCreditNoteStrategy.read(source).document(), from));
        }
        requireDetected(detected, from, DocumentFormat.UBL_INVOICE);
        yield fromUbl.map(requireParsed(ublInvoiceStrategy.read(source).document(), from));
      }
    };
  }

  private String write(Invoice invoice, ConversionFormat to) {
    return switch (to) {
      case EBINTERFACE -> ebiStrategy.write(toEbInterface.map(invoice));
      case UBL ->
          switch (toUbl.map(invoice)) {
            case UblDocument.CommercialInvoice(var document) -> ublInvoiceStrategy.write(document);
            case UblDocument.CreditNote(var document) -> ublCreditNoteStrategy.write(document);
          };
    };
  }

  private static void requireDetected(
      DocumentFormat detected, ConversionFormat declared, DocumentFormat expected) {
    if (detected != expected) {
      throw new UnsupportedConversionException(
          "The uploaded document is not %s: its root element identifies it as %s."
              .formatted(declared.id(), detected.sourceFormat()));
    }
  }

  private static <T> T requireParsed(T document, ConversionFormat from) {
    return Optional.ofNullable(document)
        .orElseThrow(
            () ->
                new UnsupportedConversionException(
                    "The uploaded document could not be parsed as %s.".formatted(from.id())));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated on every JVM; its absence is a broken runtime, not a recoverable state.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** The formats {@code POST /convert} accepts, as they appear in the query string. */
  public enum ConversionFormat {
    EBINTERFACE("ebinterface", TargetFormat.EBINTERFACE_61),
    UBL("ubl", TargetFormat.UBL);

    private final String id;
    private final TargetFormat targetFormat;

    ConversionFormat(String id, TargetFormat targetFormat) {
      this.id = id;
      this.targetFormat = targetFormat;
    }

    public String id() {
      return id;
    }

    TargetFormat targetFormat() {
      return targetFormat;
    }
  }
}
