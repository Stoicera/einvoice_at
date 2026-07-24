package com.stoicera.einvoice.formats.ebinterface;

import java.util.List;

/**
 * Outcome of a lenient {@link EbInterfaceVersionStrategy#read(byte[])} or {@link
 * EbInterfaceVersionStrategy#read(org.w3c.dom.Node)}.
 *
 * <p>{@code document} is {@code null} on failure. {@code errors} carries the human-readable
 * diagnostics collected while parsing (empty on a clean read); the list is copied defensively and
 * is immutable.
 *
 * @param document the parsed document, or {@code null} if the read failed
 * @param errors the collected diagnostics, never {@code null}
 * @param <T> the version-specific JAXB document type
 */
public record ReadResult<T>(T document, List<String> errors) {

  public ReadResult {
    errors = List.copyOf(errors);
  }

  /**
   * @return {@code true} when a document was produced, i.e. the read succeeded
   */
  public boolean isSuccess() {
    return document != null;
  }
}
