package com.stoicera.einvoice.app.report;

import com.stoicera.einvoice.app.audit.AuditAction;
import com.stoicera.einvoice.app.audit.AuditService;
import com.stoicera.einvoice.app.persistence.ReportEntity;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.EbInterface61Validator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Application service behind {@code POST /api/v1/validate} and the tenant-scoped {@code GET
 * /api/v1/reports} reads.
 *
 * <p>{@link #validate} is the one seam the whole validator pipeline runs through for a
 * caller-uploaded document. It never persists for an anonymous caller (GDPR stance, SPEC section 8:
 * the empty {@link Optional} passed in means "write nothing") and always persists — report row plus
 * a {@code VALIDATION_RUN} audit event, both in one transaction — for an authenticated one. The
 * validator itself never throws on bad input (malformed XML, an unrecognised format, an oversized
 * document all become findings), so every call here returns 200-shaped data; there is no error path
 * from the validator's side.
 */
@Service
public class ReportService {

  /** Largest listing page the API will serve; larger requests are clamped down, never rejected. */
  static final int MAX_PAGE_SIZE = 100;

  private final ReportRepository reports;
  private final AuditService audit;
  private final EbInterface61Validator validator;

  /**
   * Serializes/deserializes the findings list to and from the report's JSONB column. Same default
   * Jackson 3 mapper as {@code InvoiceService}'s — {@code Finding} carries no date/Optional/naming-
   * sensitive fields, so round-tripping through it reproduces the stored list exactly.
   */
  private final JsonMapper findingsMapper = JsonMapper.builder().build();

  public ReportService(
      ReportRepository reports, AuditService audit, EbInterface61Validator validator) {
    this.reports = reports;
    this.audit = audit;
    this.validator = validator;
  }

  /**
   * Validates the uploaded bytes and, for an authenticated caller, persists the result.
   *
   * @param bytes the raw upload bytes, read exactly once by the caller
   * @param tenantId the caller's tenant, or {@link Optional#empty()} for an anonymous caller — in
   *     which case nothing is written: no report row, no audit event
   * @return the validation report, with the persisted report's id ({@code null} when nothing was
   *     persisted)
   */
  @Transactional
  public ValidateResult validate(byte[] bytes, Optional<UUID> tenantId) {
    ValidationReport report = validator.validate(bytes);
    if (tenantId.isEmpty()) {
      return new ValidateResult(null, report);
    }

    UUID tenant = tenantId.get();
    ReportEntity entity =
        new ReportEntity(
            tenant,
            null, // ad-hoc validation, not tied to a stored invoice
            report.sourceFormat(),
            report.profile(),
            report.isValid(),
            findingsMapper.writeValueAsString(report.findings()));
    reports.save(entity);
    audit.record(tenant, AuditAction.VALIDATION_RUN, sha256Hex(bytes));

    return new ValidateResult(entity.getId(), report);
  }

  /**
   * Lists the tenant's reports, newest first. {@code size} is clamped to {@code [1, }{@value
   * #MAX_PAGE_SIZE}{@code ]} and {@code page} to {@code >= 0}; out-of-range values are clamped,
   * never rejected.
   */
  @Transactional(readOnly = true)
  public ReportPage list(UUID tenantId, int page, int size) {
    int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int clampedPage = Math.max(page, 0);
    Page<ReportEntity> result =
        reports.findByTenantId(
            tenantId,
            PageRequest.of(clampedPage, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt")));

    List<ReportSummary> content = result.getContent().stream().map(ReportSummary::of).toList();
    return new ReportPage(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  /** Returns the full stored report for one of the tenant's reports. */
  @Transactional(readOnly = true)
  public ReportDetail get(UUID tenantId, UUID id) {
    ReportEntity entity =
        reports
            .findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new ReportNotFoundException(id));
    return ReportDetail.of(entity, readFindings(entity.getFindings()));
  }

  private List<Finding> readFindings(String findingsJson) {
    JavaType listType =
        findingsMapper.getTypeFactory().constructCollectionType(List.class, Finding.class);
    return findingsMapper.readValue(findingsJson, listType);
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
