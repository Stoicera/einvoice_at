package com.stoicera.einvoice.formats.ebinterface;

import org.w3c.dom.Node;

/**
 * Read/write strategy for one concrete ebInterface version.
 *
 * <p>SPEC §10: adding ebInterface 7.0 must not touch {@code core} — a new version is a new strategy
 * implementation, nothing more. Implementations wrap the ph-ebinterface JAXB marshallers and are
 * intentionally <em>lenient</em>: they do not perform XSD/Schematron validation (that is the
 * validation module's responsibility). The {@code read} overloads therefore collect, rather than
 * throw, the diagnostics the underlying parser reports.
 *
 * @param <T> the version-specific JAXB document type (e.g. {@code Ebi61InvoiceType})
 */
public interface EbInterfaceVersionStrategy<T> {

  /** The XML target namespace this strategy reads and writes. */
  String namespaceUri();

  /**
   * Parses {@code xml} leniently into the version-specific document type.
   *
   * @param xml the raw XML bytes; the character encoding is taken from the XML declaration
   * @return a {@link ReadResult} whose {@code document} is {@code null} when the bytes could not be
   *     parsed into a usable document, with the collected diagnostics in {@code errors}
   */
  ReadResult<T> read(byte[] xml);

  /**
   * Unmarshals an already-parsed DOM {@code node} leniently into the version-specific document
   * type.
   *
   * <p>This overload exists so a caller that has already parsed the untrusted bytes through a
   * hardened, XXE-safe {@code DocumentBuilder} can reuse that DOM instead of handing the raw bytes
   * back to the marshaller for a second, unhardened parse. Structural correctness is still the
   * validation module's concern: the read is lenient and collects, rather than throws, diagnostics.
   *
   * @param node the parsed DOM node to unmarshal, typically the document or its root element
   * @return a {@link ReadResult} whose {@code document} is {@code null} when the node could not be
   *     unmarshalled into a usable document, with the collected diagnostics in {@code errors}
   */
  ReadResult<T> read(Node node);

  /**
   * Serialises {@code invoice} to formatted UTF-8 XML.
   *
   * @param invoice the document tree to marshal
   * @return the XML as a string
   * @throws IllegalStateException if the tree cannot be marshalled
   */
  String write(T invoice);
}
