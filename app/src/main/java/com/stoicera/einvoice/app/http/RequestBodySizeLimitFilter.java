package com.stoicera.einvoice.app.http;

import com.stoicera.einvoice.app.problem.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of any non-multipart request body the application will read.
 *
 * <p><b>Why this exists.</b> {@code spring.servlet.multipart.max-file-size}/{@code
 * max-request-size} bound the validator's uploads, but they only apply to multipart requests. No
 * Spring Boot or Tomcat property bounds an ordinary body: {@code
 * server.tomcat.max-http-form-post-size} is form-encoded only (verified against Boot 4.1's
 * configuration metadata). {@code POST /api/v1/invoices} declares its body as {@code byte[]}, which
 * Spring buffers whole before the handler is entered, so without this filter a single caller could
 * hand the server an arbitrarily large body and exhaust the heap — and that same unbounded string
 * would then be written into the {@code invoice.canonical} column.
 *
 * <p><b>Two checks, because one is not enough.</b> A {@code Content-Length} header is checked up
 * front, which rejects the ordinary case without reading a byte. A chunked request carries no such
 * header, so the body is additionally wrapped in a counting stream that fails the moment the cap is
 * passed — a header-only guard would be trivially sidestepped by dropping the header.
 *
 * <p><b>Placement.</b> Registered at {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE},
 * ahead of Spring Security's chain, on purpose: an oversized body must be refused <em>before</em>
 * authentication, or an anonymous caller could still make the server buffer gigabytes only to be
 * told 401 afterwards.
 *
 * <p>Multipart requests are skipped and left to the container's own multipart caps, so the two
 * mechanisms never disagree about who rejected an upload. Both answer 413 with the same {@code
 * content-too-large} problem type.
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestBodySizeLimitFilter.class);

  private final long maxBytes;

  public RequestBodySizeLimitFilter(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType != null
        && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxBytes) {
      reject(request, response);
      return;
    }
    try {
      filterChain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
    } catch (RequestBodyTooLargeException e) {
      // The chunked path: the counting stream failed mid-read. Only reachable if nothing further
      // up the chain has already turned the failure into a response (Spring MVC does, via
      // ApiExceptionHandler's unwrapping of HttpMessageNotReadableException) and nothing has been
      // committed yet.
      if (!response.isCommitted()) {
        reject(request, response);
      }
    }
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // Security-relevant rejection, so it is visible in the logs. Only the method, path and the cap
    // are recorded — never any part of the body.
    log.warn(
        "Rejected oversized request body on {} {} (limit {} bytes)",
        request.getMethod(),
        request.getRequestURI(),
        maxBytes);
    Problems.write(
        response,
        HttpStatus.CONTENT_TOO_LARGE,
        "content-too-large",
        HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase(),
        "The request body exceeds the " + maxBytes + " byte limit.");
  }

  /** Wraps the request so every read of its body goes through {@link CountingInputStream}. */
  private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

    private final long limit;
    private ServletInputStream stream;

    private LimitedBodyRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new CountingInputStream(super.getInputStream(), limit);
      }
      return stream;
    }
  }

  /**
   * Passes bytes straight through while counting them, and throws once more than {@code limit} have
   * been read. Every {@code read} overload is delegated so no path can bypass the counter.
   */
  private static final class CountingInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long limit;
    private long count;

    private CountingInputStream(ServletInputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int read = delegate.read();
      if (read != -1) {
        countAndCheck(1);
      }
      return read;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int read = delegate.read(b, off, len);
      if (read > 0) {
        countAndCheck(read);
      }
      return read;
    }

    private void countAndCheck(int justRead) throws RequestBodyTooLargeException {
      count += justRead;
      if (count > limit) {
        throw new RequestBodyTooLargeException(limit);
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      delegate.setReadListener(readListener);
    }

    @Override
    public int available() throws IOException {
      return delegate.available();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
