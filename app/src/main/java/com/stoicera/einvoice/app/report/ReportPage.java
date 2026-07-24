package com.stoicera.einvoice.app.report;

import java.util.List;

/**
 * The reports-listing envelope: a page of {@link ReportSummary} rows plus its paging metadata.
 *
 * <p>Same hand-rolled shape as {@code InvoicePage} — a small, fixed public contract rather than
 * Spring Data's {@code Page}/{@code PageImpl} serialized directly. {@code page} and {@code size}
 * echo the (clamped) request; {@code totalElements}/{@code totalPages} describe the whole
 * tenant-scoped result. Rows are ordered {@code createdAt} descending.
 */
public record ReportPage(
    List<ReportSummary> content, int page, int size, long totalElements, int totalPages) {}
