package com.stoicera.einvoice.app.invoice;

import com.stoicera.einvoice.core.validation.ValidationReport;
import java.util.UUID;

/**
 * Body of a successful {@code POST /api/v1/invoices}: the new invoice's id and the validation
 * report produced for it.
 *
 * <p>The report is the {@code core} {@link ValidationReport} record serialized as-is — its JSON
 * shape ({@code sourceFormat}, {@code profile}, {@code valid}, {@code findings[]} with {@code
 * severity}/{@code ruleId}/{@code location}/{@code messageDe}/{@code messageEn}/{@code
 * aiExplanation}) is public API contract from M3 on and is pinned by a dedicated test. A report
 * with findings is still returned with 201: validation is informative here, not gating.
 */
public record InvoiceCreated(UUID id, ValidationReport report) {}
