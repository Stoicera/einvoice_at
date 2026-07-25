package com.stoicera.einvoice.app.report;

import com.stoicera.einvoice.core.validation.ValidationReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/validate}, identical for every caller: the {@link ValidationReport}
 * (the same wire contract {@code POST /api/v1/invoices} answers with, reused as-is) plus the id of
 * the persisted {@code ReportEntity} — or {@code null} when nothing was persisted.
 *
 * <p>{@code id} is {@code null} for an anonymous caller: anonymous validation writes zero database
 * rows (GDPR stance, SPEC section 8), so there is no id to return. An authenticated caller (JWT or
 * API key) always gets a non-null id — the report was persisted (with {@code invoiceId} null: an
 * ad-hoc validation is not tied to a stored invoice) and the run is audited. One response shape for
 * both, rather than two different envelopes, keeps the contract simple and lets a client probe
 * {@code id != null} to know whether the run was recorded.
 */
public record ValidateResult(
    @Schema(
            description =
                "The persisted report's id, or null for an anonymous caller (nothing is"
                    + " persisted).",
            nullable = true)
        UUID id,
    @Schema(description = "The validation report produced for the uploaded document.")
        ValidationReport report) {}
