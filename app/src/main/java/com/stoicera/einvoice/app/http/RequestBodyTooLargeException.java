package com.stoicera.einvoice.app.http;

import java.io.IOException;

/**
 * Thrown by {@link RequestBodySizeLimitFilter}'s counting stream when a request body grows past the
 * configured cap mid-read — the chunked-transfer case, where no {@code Content-Length} header
 * exists for the filter to pre-check.
 *
 * <p>An {@link IOException} on purpose: it is raised from inside {@code ServletInputStream.read},
 * whose contract permits nothing else. Spring's message converters wrap a read failure in {@code
 * HttpMessageNotReadableException}, so {@code ApiExceptionHandler} unwraps the cause chain to
 * recover the correct 413 instead of reporting the generic 400 an unreadable body would otherwise
 * get.
 */
public class RequestBodyTooLargeException extends IOException {

  private static final long serialVersionUID = 1L;

  private final long limitBytes;

  public RequestBodyTooLargeException(long limitBytes) {
    super("Request body exceeds the configured limit of " + limitBytes + " bytes");
    this.limitBytes = limitBytes;
  }

  public long getLimitBytes() {
    return limitBytes;
  }
}
