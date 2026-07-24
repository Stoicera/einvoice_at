package com.stoicera.einvoice.app.invoice;

import java.util.UUID;

/**
 * Thrown when an invoice cannot be served to the caller — either because no row with that id
 * exists, or because the row belongs to a different tenant.
 *
 * <p>Both cases deliberately share one exception (and, at the HTTP boundary, one 404 body): a
 * tenant must not be able to tell "does not exist" apart from "exists but is not yours", or the API
 * would leak the existence of other tenants' invoices. The id is carried for logging only and is
 * never echoed in the response.
 */
public class InvoiceNotFoundException extends RuntimeException {

  public InvoiceNotFoundException(UUID id) {
    super("Invoice not found: " + id);
  }
}
