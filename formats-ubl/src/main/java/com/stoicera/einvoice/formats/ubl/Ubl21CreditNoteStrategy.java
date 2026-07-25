package com.stoicera.einvoice.formats.ubl;

import com.helger.ubl21.UBL21Marshaller;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;

/**
 * UBL 2.1 {@code ubl:CreditNote} read/write — the syntax an EN 16931 type code 381 travels in under
 * Peppol BIS Billing 3.0.
 *
 * <p>Lenient by design; see {@link AbstractUbl21Strategy} for the read/write contract and for why
 * schema validation is deliberately off here.
 */
public final class Ubl21CreditNoteStrategy extends AbstractUbl21Strategy<CreditNoteType> {

  public Ubl21CreditNoteStrategy() {
    super(UblDocumentKind.CREDIT_NOTE, UBL21Marshaller::creditNote);
  }
}
