package com.stoicera.einvoice.app.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.repair.RepairProposal;
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
 *
 * <p>{@code repair} is present only when the caller asked for it ({@code repair=true}) and a
 * finding has a known fix; otherwise the field is omitted, so a client that never asks sees exactly
 * the response it always did. The proposal is never persisted, not even for an authenticated caller
 * — the corrected document is the caller's to keep.
 */
public record ValidateResult(
    @Schema(
            description =
                "The persisted report's id, or null for an anonymous caller (nothing is"
                    + " persisted).",
            nullable = true)
        UUID id,
    @Schema(description = "The validation report produced for the uploaded document.")
        ValidationReport report,
    @Schema(
            description =
                "Correction proposal with evidence: each change with rule id, business term and"
                    + " the source of its value, the corrected document, a unified diff and the"
                    + " re-validation of the corrected document. Only present with repair=true"
                    + " and when a finding has a known fix. Never persisted. correctedXml is the"
                    + " document text; write it in the encoding its XML declaration names (UTF-8"
                    + " unless declared otherwise).",
            nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        RepairProposal repair) {

  /** A result without a correction proposal — the shape every caller got before it existed. */
  public ValidateResult(UUID id, ValidationReport report) {
    this(id, report, null);
  }

  /** This result with {@code proposal} attached. */
  public ValidateResult withRepair(RepairProposal proposal) {
    return new ValidateResult(id, report, proposal);
  }
}
