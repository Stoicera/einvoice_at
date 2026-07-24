package com.stoicera.einvoice.app.report;

import com.stoicera.einvoice.app.persistence.ReportEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the reports listing ({@code GET /api/v1/reports}): id, the invoice it belongs to (or
 * {@code null} for a report produced by an ad-hoc {@code POST /api/v1/validate} that was never
 * attached to an invoice), the format/profile validated and the outcome. Deliberately a flat,
 * stable shape — public API contract — rather than exposing the entity or the full findings list
 * (see {@link ReportDetail} for that).
 */
public record ReportSummary(
    UUID id,
    UUID invoiceId,
    String sourceFormat,
    String profile,
    boolean valid,
    Instant createdAt) {

  static ReportSummary of(ReportEntity entity) {
    return new ReportSummary(
        entity.getId(),
        entity.getInvoiceId(),
        entity.getSourceFormat(),
        entity.getProfile(),
        entity.isValid(),
        entity.getCreatedAt());
  }
}
