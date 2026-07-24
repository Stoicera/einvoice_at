package com.stoicera.einvoice.validation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.validation.TestDocuments;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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

  @Test
  void malformedInputIsRejectedQuietlyWithoutStderrNoise() {
    // The installed QUIET_ERROR_HANDLER must swallow the parser's fatal error: without it, JAXP's
    // default handler prints a "[Fatal Error] ..." line to System.err for every malformed upload,
    // spamming CI logs on each negative corpus fixture. Assert the parse is both empty AND silent.
    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    Optional<Document> dom;
    try {
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      dom = SecureXml.parse(TestDocuments.bytes(TestDocuments.malformed()));
    } finally {
      System.setErr(originalErr);
    }

    assertThat(dom).isEmpty();
    assertThat(captured.toString(StandardCharsets.UTF_8)).isEmpty();
  }
}
