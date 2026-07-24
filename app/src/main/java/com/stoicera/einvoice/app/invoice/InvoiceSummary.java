package com.stoicera.einvoice.app.invoice;

import com.stoicera.einvoice.app.persistence.InvoiceEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the invoice listing: the extracted projection columns plus {@code valid}, taken from
 * the invoice's stored validation report. Deliberately a flat, stable shape — it is public API
 * contract — rather than exposing the whole entity or canonical document.
 */
public record InvoiceSummary(
    UUID id,
    String invoiceNumber,
    String typeCode,
    LocalDate issueDate,
    BigDecimal payableAmount,
    String currency,
    String buyerName,
    boolean valid) {

  static InvoiceSummary of(InvoiceEntity entity, boolean valid) {
    return new InvoiceSummary(
        entity.getId(),
        entity.getInvoiceNumber(),
        entity.getTypeCode(),
        entity.getIssueDate(),
        entity.getPayableAmount(),
        entity.getCurrency(),
        entity.getBuyerName(),
        valid);
  }
}
