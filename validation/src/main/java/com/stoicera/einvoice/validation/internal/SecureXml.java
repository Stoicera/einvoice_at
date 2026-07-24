package com.stoicera.einvoice.validation.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Hardened DOM parsing at the system boundary (Engineering Standards §4).
 *
 * <p>Every byte array this platform validates arrives from an untrusted upload, so it is parsed
 * with a namespace-aware {@link javax.xml.parsers.DocumentBuilderFactory} that has secure
 * processing on, {@code DOCTYPE} declarations disallowed, and external general/parameter entities
 * and external DTD loading switched off. This closes XML External Entity (XXE) and entity-expansion
 * (billion-laughs) vectors before any content reaches the pipeline: a document that even declares a
 * {@code DOCTYPE} is rejected outright.
 */
public final class SecureXml {

  private SecureXml() {}

  /**
   * Parses {@code xml} into a DOM with XXE hardening.
   *
   * @param xml the raw, untrusted bytes
   * @return the parsed document, or {@link Optional#empty()} when the bytes are not well-formed XML
   *     or declare a forbidden {@code DOCTYPE}
   */
  public static Optional<Document> parse(byte[] xml) {
    try {
      DocumentBuilder builder = hardenedFactory().newDocumentBuilder();
      Document document = builder.parse(new ByteArrayInputStream(xml));
      return Optional.of(document);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      // Malformed XML or a forbidden DOCTYPE is the domain, not an error to propagate: the pipeline
      // turns an empty result into an XML-01 finding.
      return Optional.empty();
    }
  }

  private static DocumentBuilderFactory hardenedFactory() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    // Reject any document that even declares a DOCTYPE; this alone defeats XXE and entity
    // expansion.
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    // Belt-and-braces even though a DOCTYPE can no longer appear: never resolve external entities.
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }
}
