package com.stoicera.einvoice.formats.ubl;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Finds a document's root element name without unmarshalling it.
 *
 * <p><strong>Why this exists.</strong> JAXB unmarshals by declared type, not by root element name,
 * and ph-ubl's marshallers offer no way to demand a particular root. With schema validation
 * deliberately off (see {@link AbstractUbl21Strategy}), handing a {@code ubl:CreditNote} to the
 * invoice marshaller therefore <em>succeeds</em>: the two documents share the same {@code cbc:}/
 * {@code cac:} child vocabulary, so nothing looks unknown and JAXB quietly produces an {@code
 * InvoiceType} — dropping {@code CreditedQuantity}, keeping the wrong document kind, and reporting
 * no error at all. That was measured, not assumed; it is the reason the strategies check the root
 * element themselves.
 *
 * <p>The ebInterface adapter needs no equivalent: each ebInterface version has exactly one root
 * element, and a foreign one already fails the read through the diagnostics JAXB collects. The
 * guard lives here, where the hole is, rather than being generalised into {@code formats-api} for a
 * problem the other adapter does not have.
 *
 * <p>The byte-array peek uses StAX rather than a DOM: it stops at the first start element, so it
 * neither materialises the document nor duplicates a full hardened {@code DocumentBuilder}. Because
 * it stops there, no entity is ever expanded and no external resource is ever dereferenced — that
 * safety comes from where the read ends, not from a parser setting. DTD support and external
 * entities are nonetheless switched off as defence in depth for whatever reads further one day;
 * mutation testing confirms neither setting can change this method's answer, and both survivors are
 * recorded as equivalent in the module's PIT configuration rather than dressed up with a test that
 * only appears to kill them.
 */
final class UblRootElement {

  private UblRootElement() {}

  /**
   * The root element's qualified name, read from the first start element of {@code xml}.
   *
   * @return the root element name, or {@link Optional#empty()} when the bytes are not well-formed
   *     XML or carry no element at all — in which case the caller lets the marshaller run and
   *     report the real diagnostics rather than inventing its own
   */
  static Optional<QName> peek(byte[] xml) {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

    try {
      // Deliberately not closed. The reader wraps an in-memory ByteArrayInputStream, so there is no
      // OS handle to release, and StAX's close() does not close the underlying stream in any case.
      // A close() in a finally block could only do harm here: it is declared to throw
      // XMLStreamException, and a throwing finally would replace an already-computed answer with a
      // failure.
      XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
      while (reader.hasNext()) {
        if (reader.next() == XMLStreamConstants.START_ELEMENT) {
          return Optional.of(reader.getName());
        }
      }
      // Defensive: a reader that runs to completion without ever reporting a start element. No
      // input reaches it in practice — bytes with no element are not well-formed XML, so the
      // reader throws and the catch below answers instead.
      return Optional.empty();
    } catch (XMLStreamException e) {
      return Optional.empty();
    }
  }

  /**
   * The root element's qualified name of an already-parsed {@code node}, which may be the document
   * or the root element itself.
   *
   * @return the root element name, or {@link Optional#empty()} when the node carries no element
   */
  static Optional<QName> of(Node node) {
    Element element =
        switch (node) {
          case Document document -> document.getDocumentElement();
          case Element self -> self;
          default -> null;
        };
    if (element == null) {
      return Optional.empty();
    }
    // A DOM built without namespace awareness reports null for both getNamespaceURI() and
    // getLocalName(); getNodeName() is then the only name available. Callers in this repo hand over
    // a namespace-aware DOM, but a caller that does not must get an honest "this is not my root
    // element" rather than a NullPointerException.
    String namespaceUri = element.getNamespaceURI();
    String localName =
        element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
    return Optional.of(
        namespaceUri == null ? new QName(localName) : new QName(namespaceUri, localName));
  }
}
