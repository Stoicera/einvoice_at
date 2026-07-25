package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.convert.UnsupportedConversionException;
import com.stoicera.einvoice.app.http.RequestBodySizeLimitFilter;
import com.stoicera.einvoice.app.http.RequestBodyTooLargeException;
import com.stoicera.einvoice.app.invoice.DuplicateInvoiceException;
import com.stoicera.einvoice.app.invoice.InvoiceNotFoundException;
import com.stoicera.einvoice.app.problem.Problems;
import com.stoicera.einvoice.app.report.ReportNotFoundException;
import com.stoicera.einvoice.app.security.ApiKeyNotFoundException;
import com.stoicera.einvoice.app.security.TooManyApiKeysException;
import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.mapping.json.InvoiceJsonException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every failure of the REST API into an RFC 9457 {@code application/problem+json}
 * response.
 *
 * <p>Each {@link ProblemDetail} carries a stable {@code type} URI under {@link Problems#BASE}, a
 * human {@code title}, and a {@code detail} that is safe to echo. Messages from {@code core}/{@code
 * mapping} follow the codebase's bounded-echo discipline, so they are echoed verbatim to help a
 * caller fix their request; the catch-all 500, by contrast, never leaks an exception message or
 * class.
 *
 * <p>The class extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (wrong
 * method, unsupported media type, unreadable body, type-mismatched path/query values, a missing
 * multipart part — {@code MissingServletRequestPartException} → 400 — and an oversized upload —
 * {@code MaxUploadSizeExceededException} → 413, the application cap being {@code
 * spring.servlet.multipart.max-file-size}/{@code max-request-size} in {@code application.yml}) keep
 * their correct HTTP status; {@link #handleExceptionInternal} then stamps the project's {@code
 * type} URI onto those framework problems too, so the whole API speaks one problem vocabulary.
 * Anything not otherwise mapped falls through to a bare 500 that leaks nothing.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  /** Canonical-JSON shape error (unknown field, wrong node type, unparsable amount/date, …). */
  @ExceptionHandler(InvoiceJsonException.class)
  ProblemDetail handleInvoiceJson(InvoiceJsonException ex) {
    return problem(HttpStatus.BAD_REQUEST, "invalid-json", "Invalid invoice JSON", ex.getMessage());
  }

  /** Well-formed JSON, but the invoice it describes breaks a domain invariant. */
  @ExceptionHandler(InvariantViolationException.class)
  ProblemDetail handleInvariant(InvariantViolationException ex) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "invalid-invoice",
        "Invoice violates a domain rule",
        ex.getMessage());
  }

  /**
   * The requested conversion cannot be performed as asked: the two formats are the same, or the
   * upload is not in the format the caller declared.
   *
   * <p>The detail is echoed because it is entirely about the caller's own request — a format name
   * they sent and a format name we detected — and knowing which of the two is wrong is the whole
   * value of the message.
   */
  @ExceptionHandler(UnsupportedConversionException.class)
  ProblemDetail handleUnsupportedConversion(UnsupportedConversionException ex) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "unsupported-conversion",
        "Unsupported conversion",
        ex.getMessage());
  }

  /** Unknown id, or an id belonging to another tenant — one indistinguishable 404, no oracle. */
  @ExceptionHandler(InvoiceNotFoundException.class)
  ProblemDetail handleNotFound(InvoiceNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND,
        "invoice-not-found",
        "Invoice not found",
        "No invoice with the given id exists for this tenant.");
  }

  /**
   * Unknown report id, or an id belonging to another tenant — one indistinguishable 404, no oracle.
   */
  @ExceptionHandler(ReportNotFoundException.class)
  ProblemDetail handleReportNotFound(ReportNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND,
        "report-not-found",
        "Report not found",
        "No report with the given id exists for this tenant.");
  }

  /**
   * Unknown API-key id, or one belonging to another tenant — one indistinguishable 404, no oracle.
   */
  @ExceptionHandler(ApiKeyNotFoundException.class)
  ProblemDetail handleApiKeyNotFound(ApiKeyNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND,
        "api-key-not-found",
        "API key not found",
        "No API key with the given id exists for this tenant.");
  }

  /** The tenant already holds the maximum number of active API keys. */
  @ExceptionHandler(TooManyApiKeysException.class)
  ProblemDetail handleTooManyApiKeys(TooManyApiKeysException ex) {
    return problem(
        HttpStatus.CONFLICT,
        "api-key-limit-reached",
        "API key limit reached",
        "This tenant already holds the maximum of "
            + ex.getLimit()
            + " active API keys. Revoke one before creating another.");
  }

  /** The {@code (tenant, invoiceNumber)} uniqueness constraint was violated. */
  @ExceptionHandler(DuplicateInvoiceException.class)
  ProblemDetail handleDuplicate(DuplicateInvoiceException ex) {
    return problem(
        HttpStatus.CONFLICT,
        "duplicate-invoice",
        "Duplicate invoice number",
        "An invoice with the same invoice number already exists for this tenant.");
  }

  /**
   * Last resort: never leak an internal message, class name, or stack trace <em>to the caller</em>
   * — and never discard it on the server either.
   *
   * <p>Before the M3 hostile review this handler swallowed the exception outright, so a 500 in
   * production left no stack trace, no message, nothing: an incident that could not be
   * investigated. Not telling the client is a security decision; not telling the operator was a
   * bug. The exception is logged here in full, with its stack trace, at ERROR.
   *
   * <p>Only genuinely unmapped failures reach this method — every exception carrying caller data in
   * its message ({@code DuplicateInvoiceException} and its raw invoice number, the {@code
   * core}/{@code mapping} messages) has its own handler above and is never logged here.
   */
  @ExceptionHandler(Exception.class)
  ProblemDetail handleUnexpected(Exception ex) {
    logger.error("Unhandled exception while processing a request; answering 500", ex);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "Internal server error",
        "An unexpected error occurred while processing the request.");
  }

  /**
   * Recovers the correct 413 when {@link RequestBodySizeLimitFilter}'s counting stream tripped
   * mid-read on a chunked body.
   *
   * <p>That failure surfaces as an {@link java.io.IOException} from inside {@code
   * ServletInputStream.read} (the only thing its contract allows), and Spring's message converters
   * wrap any read failure in {@code HttpMessageNotReadableException} — which would otherwise be
   * reported as a generic 400 "unreadable body", hiding the real reason. The cause chain is walked
   * rather than only the immediate cause, since the number of wrapping layers is a converter
   * implementation detail. Anything that is genuinely an unreadable body keeps its 400 by
   * delegating to {@code super}.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof RequestBodyTooLargeException tooLarge) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
            .body(
                problem(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "content-too-large",
                    HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase(),
                    "The request body exceeds the " + tooLarge.getLimitBytes() + " byte limit."));
      }
      if (cause.getCause() == cause) {
        break; // defensive: a self-referential cause would otherwise loop forever
      }
    }
    return super.handleHttpMessageNotReadable(ex, headers, status, request);
  }

  /**
   * Stamps the project's {@code type} URI (and a title, when missing) onto the {@link
   * ProblemDetail} that {@link ResponseEntityExceptionHandler} builds for Spring MVC's own
   * exceptions, so a framework-produced problem is not left with the default {@code about:blank}
   * type. Their status and safe default detail are preserved.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
        problem.setType(Problems.type(slugForStatus(statusCode)));
      }
      HttpStatus resolved = HttpStatus.resolve(statusCode.value());
      if (problem.getTitle() == null && resolved != null) {
        problem.setTitle(resolved.getReasonPhrase());
      }
    }
    return response;
  }

  private static String slugForStatus(HttpStatusCode statusCode) {
    HttpStatus resolved = HttpStatus.resolve(statusCode.value());
    if (resolved == null) {
      return "error";
    }
    return resolved.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static ProblemDetail problem(
      HttpStatus status, String slug, String title, @Nullable String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
    problem.setType(Problems.type(slug));
    problem.setTitle(title);
    return problem;
  }
}
