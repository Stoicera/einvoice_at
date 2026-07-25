package com.stoicera.einvoice.mapping.ubl;

import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;

/**
 * The result of mapping a canonical invoice to UBL: either a {@code ubl:Invoice} or a {@code
 * ubl:CreditNote}.
 *
 * <p>This type exists because UBL makes the document kind a property of the <em>syntax</em>, not of
 * the content: EN 16931 type code 380 travels as one root element and 381 as another, in different
 * namespaces and with different JAXB types that share no common Java supertype. A mapper that
 * returned {@code Object}, or two methods the caller had to pick between by re-deriving the type
 * code, would push that decision back onto every caller. A sealed type lets the caller switch
 * exhaustively and lets the compiler check it did.
 *
 * <p>Deliberately no {@code write()} here: serialisation belongs to {@code formats-ubl}, which this
 * module does not depend on in main scope — exactly the split the ebInterface mapper already has,
 * where the mapper builds the JAXB tree and the caller marshals it.
 */
public sealed interface UblDocument {

  /** A UBL 2.1 {@code ubl:Invoice} — EN 16931 BT-3 type code 380. */
  record CommercialInvoice(InvoiceType document) implements UblDocument {}

  /** A UBL 2.1 {@code ubl:CreditNote} — EN 16931 BT-3 type code 381. */
  record CreditNote(CreditNoteType document) implements UblDocument {}
}
