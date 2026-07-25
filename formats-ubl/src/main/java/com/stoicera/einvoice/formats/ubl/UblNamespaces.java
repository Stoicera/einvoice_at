package com.stoicera.einvoice.formats.ubl;

import java.util.Optional;

/**
 * Maps XML target namespaces to the {@link UblDocumentKind} they identify.
 *
 * <p>This is the seam that lets a caller pick the right strategy for an unknown UBL document
 * without hard-coding namespace strings — the counterpart of {@code EbInterfaceNamespaces} in the
 * ebInterface adapter, and the lookup the validation module's format detection runs against.
 *
 * <p>Only the two billing document kinds resolve. UBL 2.1 defines several dozen other root elements
 * (Order, DespatchAdvice, Catalogue, …); this platform reads and writes invoices, so anything else
 * is deliberately an empty result rather than a partially-supported document.
 */
public final class UblNamespaces {

  private UblNamespaces() {}

  /**
   * Resolves the UBL billing document kind whose root namespace equals {@code namespaceUri}.
   *
   * @param namespaceUri the XML target namespace to look up; {@code null} resolves to empty
   * @return the matching document kind, or {@link Optional#empty()} if none matches
   */
  public static Optional<UblDocumentKind> documentKindOf(String namespaceUri) {
    for (UblDocumentKind kind : UblDocumentKind.values()) {
      if (kind.namespaceUri().equals(namespaceUri)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }
}
