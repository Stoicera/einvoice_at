package com.stoicera.einvoice.formats.ubl;

import com.helger.ubl21.UBL21Marshaller;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;

/**
 * UBL 2.1 {@code ubl:Invoice} read/write — the syntax an EN 16931 type code 380 travels in under
 * Peppol BIS Billing 3.0.
 *
 * <p>Lenient by design; see {@link AbstractUbl21Strategy} for the read/write contract and for why
 * schema validation is deliberately off here.
 */
public final class Ubl21InvoiceStrategy extends AbstractUbl21Strategy<InvoiceType> {

  public Ubl21InvoiceStrategy() {
    super(UblDocumentKind.INVOICE, UBL21Marshaller::invoice);
  }
}
