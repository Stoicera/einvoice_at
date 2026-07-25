package com.stoicera.einvoice.formats.ubl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * The root-element guard that keeps a {@code ubl:CreditNote} from being read as an {@code
 * ubl:Invoice} — see {@link UblRootElement}'s Javadoc for why JAXB does not do this itself.
 */
class UblRootElementTest {

  private static final String INVOICE_NS = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";

  private static final String INVOICE_XML =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<!-- a comment before the root element -->\n"
          + "<ubl:Invoice xmlns:ubl=\""
          + INVOICE_NS
          + "\"><ID>1</ID></ubl:Invoice>";

  @Test
  void peekReadsTheRootElementNameSkippingProlog() {
    // The comment and the XML declaration come first: the peek must skip past both rather than
    // giving up on the first non-element event.
    assertThat(UblRootElement.peek(INVOICE_XML.getBytes(StandardCharsets.UTF_8)))
        .contains(new QName(INVOICE_NS, "Invoice"));
  }

  @Test
  void peekReadsAnUnqualifiedRootElement() {
    assertThat(
            UblRootElement.peek("<Invoice><ID>1</ID></Invoice>".getBytes(StandardCharsets.UTF_8)))
        .contains(new QName("Invoice"));
  }

  @Test
  void peekIsEmptyWhenNoRootElementCanBeReached() {
    // Empty for bytes the reader cannot get a root element out of, so the caller falls through to
    // the marshaller and reports its diagnostics rather than raising a root-element complaint about
    // bytes that never parsed at all.
    assertThat(UblRootElement.peek("not xml".getBytes(StandardCharsets.UTF_8))).isEmpty();
    assertThat(UblRootElement.peek(new byte[0])).isEmpty();
  }

  /**
   * A truncated document still yields its root element name, because the peek is a streaming read:
   * StAX reports the start element long before it discovers the missing end tag. That is the right
   * outcome here — the name is all this guard needs, and the marshaller that runs afterwards is
   * what reports the document being broken.
   */
  @Test
  void peekReadsTheRootNameOfATruncatedDocument() {
    assertThat(UblRootElement.peek("<Invoice>".getBytes(StandardCharsets.UTF_8)))
        .contains(new QName("Invoice"));
  }

  /**
   * A DOCTYPE naming an external DTD subset that does not exist neither fails the peek nor stalls
   * it: the root element name still comes back.
   *
   * <p>What this test does <em>not</em> prove is that the factory's {@code SUPPORT_DTD} / {@code
   * IS_SUPPORTING_EXTERNAL_ENTITIES} settings did the work — mutation testing shows both survive
   * being removed. That is the honest result and not a gap in the test: {@code peek} stops at the
   * first start element, which no DTD, entity or external resource can influence, so those two
   * settings cannot change its answer. They are kept as defence in depth for whatever reads further
   * one day, and the survivors are recorded as equivalent in the module's PIT configuration rather
   * than papered over with a test that only appears to kill them.
   */
  @Test
  void peekHandlesADocumentWithAnExternalDtdReference() {
    String withExternalDtd =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE Invoice SYSTEM \"file:///nonexistent/definitely-not-here.dtd\">"
            + "<Invoice/>";

    assertThat(UblRootElement.peek(withExternalDtd.getBytes(StandardCharsets.UTF_8)))
        .contains(new QName("Invoice"));
  }

  @Test
  void ofReadsTheRootElementOfADocument() {
    assertThat(UblRootElement.of(parse(INVOICE_XML, true)))
        .contains(new QName(INVOICE_NS, "Invoice"));
  }

  @Test
  void ofReadsAnElementNodeItself() {
    Document document = parse(INVOICE_XML, true);

    assertThat(UblRootElement.of(document.getDocumentElement()))
        .contains(new QName(INVOICE_NS, "Invoice"));
  }

  /**
   * A DOM built without namespace awareness reports null for namespace and local name alike. The
   * guard must still produce a name — an honest mismatch beats a NullPointerException.
   */
  @Test
  void ofFallsBackToTheNodeNameOnANamespaceUnawareDom() {
    assertThat(UblRootElement.of(parse(INVOICE_XML, false))).contains(new QName("ubl:Invoice"));
  }

  @Test
  void ofIsEmptyForADocumentWithoutARootElement() {
    assertThat(UblRootElement.of(newEmptyDocument())).isEmpty();
  }

  @Test
  void ofIsEmptyForANodeThatIsNeitherDocumentNorElement() {
    Document document = parse(INVOICE_XML, true);

    assertThat(UblRootElement.of(document.createTextNode("text"))).isEmpty();
  }

  private static Document parse(String xml, boolean namespaceAware) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(namespaceAware);
      return factory
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("test fixture DOM did not parse", e);
    }
  }

  private static Document newEmptyDocument() {
    try {
      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      return builder.newDocument();
    } catch (Exception e) {
      throw new IllegalStateException("test fixture document could not be created", e);
    }
  }
}
