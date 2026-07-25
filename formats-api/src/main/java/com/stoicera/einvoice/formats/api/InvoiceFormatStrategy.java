package com.stoicera.einvoice.formats.api;

import org.w3c.dom.Node;

/**
 * Read/write strategy for one concrete invoice-document format and version — ebInterface 6.1, UBL
 * 2.1 Invoice, UBL 2.1 CreditNote, and whatever comes next.
 *
 * <p>SPEC §10: adding a format or a version must not touch {@code core} — a new one is a new
 * strategy implementation. Implementations wrap a standards library's JAXB marshallers and are
 * intentionally <em>lenient</em>: they do not perform XSD/Schematron validation (that is the
 * validation module's responsibility). The {@code read} overloads therefore collect, rather than
 * throw, the diagnostics the underlying parser reports.
 *
 * <p><strong>Why this interface exists (M4).</strong> ADR-0004 Entscheidung 10 deliberately
 * deferred a genuinely polymorphic seam until a second format existed to be polymorphic over —
 * building it for a single implementation would have been speculative generality. M4 adds that
 * second format, so the seam lands now, in its own dependency-free module, together with the two
 * things every adapter needs to agree on: this contract and {@link ReadResult}. What it
 * deliberately does <em>not</em> do is dispatch: the caller that must pick a strategy for an
 * unknown document does so from the detected format (the {@code validation} module's
 * format-detection stage), not by scanning a registry of strategies — the namespace is the
 * discriminator, and {@link #namespaceUri()} is what ties an implementation to one.
 *
 * @param <T> the format-specific JAXB document type (e.g. {@code Ebi61InvoiceType}, {@code
 *     InvoiceType})
 */
public interface InvoiceFormatStrategy<T> {

  /** The XML target namespace this strategy reads and writes. */
  String namespaceUri();

  /**
   * Parses {@code xml} leniently into the format-specific document type.
   *
   * @param xml the raw XML bytes; the character encoding is taken from the XML declaration
   * @return a {@link ReadResult} whose {@code document} is {@code null} when the bytes could not be
   *     parsed into a usable document, with the collected diagnostics in {@code errors}
   */
  ReadResult<T> read(byte[] xml);

  /**
   * Unmarshals an already-parsed DOM {@code node} leniently into the format-specific document type.
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
   * Serialises {@code document} to formatted UTF-8 XML.
   *
   * @param document the document tree to marshal
   * @return the XML as a string
   * @throws IllegalStateException if the tree cannot be marshalled
   */
  String write(T document);
}
