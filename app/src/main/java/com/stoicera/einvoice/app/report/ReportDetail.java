package com.stoicera.einvoice.app.report;

import com.stoicera.einvoice.app.persistence.ReportEntity;
import com.stoicera.einvoice.core.validation.Finding;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code GET /api/v1/reports/{id}}: the full stored report, flattened rather than nesting a
 * {@code ValidationReport} object — {@code id}, {@code invoiceId} (nullable), {@code sourceFormat},
 * {@code profile}, {@code valid} and {@code createdAt} sit alongside the {@code findings} array
 * exactly as it was stored (deserialized from the {@code report.findings} JSONB column back into
 * {@link Finding} records, not re-echoed as a raw string).
 */
public record ReportDetail(
    UUID id,
    UUID invoiceId,
    String sourceFormat,
    String profile,
    boolean valid,
    List<Finding> findings,
    Instant createdAt) {

  static ReportDetail of(ReportEntity entity, List<Finding> findings) {
    return new ReportDetail(
        entity.getId(),
        entity.getInvoiceId(),
        entity.getSourceFormat(),
        entity.getProfile(),
        entity.isValid(),
        findings,
        entity.getCreatedAt());
  }
}
