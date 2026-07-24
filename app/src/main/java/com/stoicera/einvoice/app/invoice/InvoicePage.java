package com.stoicera.einvoice.app.invoice;

import java.util.List;

/**
 * The invoice-listing envelope: a page of {@link InvoiceSummary} rows plus its paging metadata.
 *
 * <p>Deliberately a hand-rolled, explicit shape rather than serializing Spring Data's {@code Page}
 * / {@code PageImpl} directly: that internal type's JSON is unstable across versions (and Boot
 * warns against exposing it), whereas this envelope is a small, fixed public contract. {@code page}
 * and {@code size} echo the (clamped) request; {@code totalElements}/{@code totalPages} describe
 * the whole tenant-scoped result. Rows are ordered {@code createdAt} descending.
 */
public record InvoicePage(
    List<InvoiceSummary> content, int page, int size, long totalElements, int totalPages) {}
