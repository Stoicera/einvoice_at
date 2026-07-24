package com.stoicera.einvoice.validation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.validation.TestDocuments;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

class SecureXmlTest {

  private static final String XINCLUDE_NS = "http://www.w3.org/2001/XInclude";

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
  void doesNotResolveXIncludeLocalFileDisclosure(@TempDir Path tempDir) throws Exception {
    // XInclude is a namespaced document-body mechanism, resolved independently of
    // DOCTYPE/DTD/entity
    // handling: an xi:include with parse="text" splices an arbitrary local file's raw bytes
    // straight
    // into the DOM the instant the parser is XInclude-aware. The payload carries NO DOCTYPE, so
    // disallow-doctype-decl does nothing to stop it — setXIncludeAware(false) is the only guard.
    // This test pins that guard: flip SecureXml's setXIncludeAware to true and the two assertions
    // below go RED (the secret leaks into the DOM and the xi:include element vanishes, expanded).
    String secret = "TOP-SECRET-XINCLUDE-MARKER-5f3c9a2e";
    Path secretFile = tempDir.resolve("secret.txt");
    Files.writeString(secretFile, secret, StandardCharsets.UTF_8);

    Optional<Document> dom =
        SecureXml.parse(
            TestDocuments.bytes(TestDocuments.xIncludeTextPayload(secretFile.toUri().toString())));

    // XInclude-off leaves the element un-expanded: a well-formed parse, not an error.
    assertThat(dom).isPresent();
    Document document = dom.get();
    // The local file was never resolved: its secret appears nowhere in the DOM's text.
    assertThat(document.getDocumentElement().getTextContent()).doesNotContain(secret);
    // The xi:include element survives verbatim, un-expanded — proof XInclude processing stayed off.
    assertThat(document.getElementsByTagNameNS(XINCLUDE_NS, "include").getLength()).isEqualTo(1);
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
