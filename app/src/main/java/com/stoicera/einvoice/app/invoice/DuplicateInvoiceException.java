package com.stoicera.einvoice.app.invoice;

/**
 * Thrown when persisting an invoice would violate the {@code (tenant_id, invoice_number)}
 * uniqueness constraint — the tenant already has an invoice with that number.
 *
 * <p>Raised by translating the database's {@link
 * org.springframework.dao.DataIntegrityViolationException} rather than pre-checking then inserting:
 * the check-then-act race is closed by letting the unique index be the single arbiter. The invoice
 * number is carried for logging only; the 404/409 boundary body stays generic and does not echo it.
 */
public class DuplicateInvoiceException extends RuntimeException {

  public DuplicateInvoiceException(String invoiceNumber, Throwable cause) {
    super("Duplicate invoice number for tenant: " + invoiceNumber, cause);
  }
}
