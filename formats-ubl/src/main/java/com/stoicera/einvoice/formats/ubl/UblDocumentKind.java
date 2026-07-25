package com.stoicera.einvoice.formats.ubl;

import com.helger.ubl21.EUBL21DocumentType;

/**
 * The two UBL 2.1 document types Peppol BIS Billing 3.0 uses to carry an invoice.
 *
 * <p>Unlike ebInterface, which expresses "is this a credit note?" as an attribute on one root
 * element ({@code @DocumentType}), UBL splits the two into <em>different root elements in different
 * namespaces</em>: an EN 16931 type code 380 travels as a {@code ubl:Invoice}, a 381 as a {@code
 * ubl:CreditNote}. That makes the document kind a property of the syntax itself rather than of the
 * content, which is why it is an enum here and why each constant is bound to a distinct strategy.
 *
 * <p>The namespace and root element name are read from ph-ubl's own {@link EUBL21DocumentType}
 * rather than written out as string literals, so this enum cannot drift from the schemas the
 * marshallers actually use.
 */
public enum UblDocumentKind {

  /** {@code ubl:Invoice} — EN 16931 BT-3 type code 380 (commercial invoice). */
  INVOICE(EUBL21DocumentType.INVOICE),

  /** {@code ubl:CreditNote} — EN 16931 BT-3 type code 381 (credit note). */
  CREDIT_NOTE(EUBL21DocumentType.CREDIT_NOTE);

  private final EUBL21DocumentType documentType;

  UblDocumentKind(EUBL21DocumentType documentType) {
    this.documentType = documentType;
  }

  /** The XML target namespace of this document kind's root element. */
  public String namespaceUri() {
    return documentType.getRootElementNamespaceURI();
  }

  /** The local name of this document kind's root element ({@code Invoice} / {@code CreditNote}). */
  public String rootElementLocalName() {
    return documentType.getRootElementLocalName();
  }
}
