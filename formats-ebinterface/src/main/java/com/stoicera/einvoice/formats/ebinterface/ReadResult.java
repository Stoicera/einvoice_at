package com.stoicera.einvoice.formats.ebinterface;

import java.util.List;

/**
 * Outcome of a lenient {@link EbInterfaceVersionStrategy#read(byte[])} or {@link
 * EbInterfaceVersionStrategy#read(org.w3c.dom.Node)}.
 *
 * <p>{@code document} is {@code null} on failure and non-null on success. {@link #isSuccess()} is
 * the single source of truth for whether the read succeeded — callers MUST decide success from it
 * and MUST NOT infer success from {@code errors} being empty.
 *
 * <p>{@code errors} carries the human-readable diagnostics the underlying reader collected — of
 * <em>every</em> severity it reports, not errors alone. A read that produces only warning- or
 * info-level diagnostics still yields a document, so {@code errors} may be non-empty even when
 * {@link #isSuccess()} is {@code true}; conversely a failed read carries at least one error. The
 * list is copied defensively and is immutable.
 *
 * @param document the parsed document, or {@code null} if the read failed
 * @param errors all collected diagnostics (any severity), never {@code null}; may be non-empty on a
 *     successful read
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
