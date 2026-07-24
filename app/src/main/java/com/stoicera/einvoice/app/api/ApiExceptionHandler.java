package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.invoice.DuplicateInvoiceException;
import com.stoicera.einvoice.app.invoice.InvoiceNotFoundException;
import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.mapping.json.InvoiceJsonException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every failure of the REST API into an RFC 9457 {@code application/problem+json}
 * response.
 *
 * <p>Each {@link ProblemDetail} carries a stable {@code type} URI under {@code
 * https://einvoice-at.stoicera.com/problems/}, a human {@code title}, and a {@code detail} that is
 * safe to echo. Messages from {@code core}/{@code mapping} follow the codebase's bounded-echo
 * discipline, so they are echoed verbatim to help a caller fix their request; the catch-all 500, by
 * contrast, never leaks an exception message or class.
 *
 * <p>The class extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (wrong
 * method, unsupported media type, unreadable body, type-mismatched path/query values, and an
 * oversized upload — {@code MaxUploadSizeExceededException} → 413, the application cap itself is
 * wired in a later task) keep their correct HTTP status; {@link #handleExceptionInternal} then
 * stamps the project's {@code type} URI onto those framework problems too, so the whole API speaks
 * one problem vocabulary. Anything not otherwise mapped falls through to a bare 500 that leaks
 * nothing.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

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

  /** Unknown id, or an id belonging to another tenant — one indistinguishable 404, no oracle. */
  @ExceptionHandler(InvoiceNotFoundException.class)
  ProblemDetail handleNotFound(InvoiceNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND,
        "invoice-not-found",
        "Invoice not found",
        "No invoice with the given id exists for this tenant.");
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

  /** Last resort: never leak an internal message, class name, or stack trace. */
  @ExceptionHandler(Exception.class)
  ProblemDetail handleUnexpected(Exception ex) {
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "Internal server error",
        "An unexpected error occurred while processing the request.");
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
        problem.setType(URI.create(PROBLEM_BASE + slugForStatus(statusCode)));
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
    problem.setType(URI.create(PROBLEM_BASE + slug));
    problem.setTitle(title);
    return problem;
  }
}
