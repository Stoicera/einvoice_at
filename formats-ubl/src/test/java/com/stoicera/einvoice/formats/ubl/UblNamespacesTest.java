package com.stoicera.einvoice.formats.ubl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UblNamespacesTest {

  @Test
  void resolvesBothBillingDocumentKinds() {
    assertThat(UblNamespaces.documentKindOf(UblDocumentKind.INVOICE.namespaceUri()))
        .contains(UblDocumentKind.INVOICE);
    assertThat(UblNamespaces.documentKindOf(UblDocumentKind.CREDIT_NOTE.namespaceUri()))
        .contains(UblDocumentKind.CREDIT_NOTE);
  }

  @Test
  void rootElementLocalNamesAreTheUblOnes() {
    assertThat(UblDocumentKind.INVOICE.rootElementLocalName()).isEqualTo("Invoice");
    assertThat(UblDocumentKind.CREDIT_NOTE.rootElementLocalName()).isEqualTo("CreditNote");
  }

  /**
   * UBL 2.1 has several dozen other root elements; this platform reads invoices. An Order is a
   * perfectly valid UBL document and still must not resolve here, so the caller reports "unknown
   * format" rather than half-supporting it.
   */
  @Test
  void doesNotResolveANonBillingUblDocument() {
    assertThat(UblNamespaces.documentKindOf("urn:oasis:names:specification:ubl:schema:xsd:Order-2"))
        .isEmpty();
  }

  @Test
  void doesNotResolveAForeignOrAbsentNamespace() {
    assertThat(UblNamespaces.documentKindOf("http://www.ebinterface.at/schema/6p1/")).isEmpty();
    assertThat(UblNamespaces.documentKindOf("")).isEmpty();
    assertThat(UblNamespaces.documentKindOf(null)).isEmpty();
  }
}
