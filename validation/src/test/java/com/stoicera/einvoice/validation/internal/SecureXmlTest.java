package com.stoicera.einvoice.validation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.validation.TestDocuments;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class SecureXmlTest {

  @Test
  void parsesWellFormedXmlNamespaceAware() {
    Optional<Document> dom =
        SecureXml.parse(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(dom).isPresent();
    assertThat(dom.get().getDocumentElement().getLocalName()).isEqualTo("Invoice");
    assertThat(dom.get().getDocumentElement().getNamespaceURI())
        .isEqualTo("http://www.ebinterface.at/schema/6p1/");
  }

  @Test
  void refusesDoctypeXxePayload() {
    Optional<Document> dom =
        SecureXml.parse(TestDocuments.bytes(TestDocuments.xxeDoctypePayload()));

    assertThat(dom).isEmpty();
  }

  @Test
  void returnsEmptyForMalformedBytes() {
    Optional<Document> dom = SecureXml.parse(TestDocuments.bytes(TestDocuments.malformed()));

    assertThat(dom).isEmpty();
  }

  @Test
  void returnsEmptyForEmptyInput() {
    assertThat(SecureXml.parse(new byte[0])).isEmpty();
  }
}
