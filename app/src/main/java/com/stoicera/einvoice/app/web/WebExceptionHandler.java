package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.app.invoice.InvoiceNotFoundException;
import com.stoicera.einvoice.app.report.ReportNotFoundException;
import com.stoicera.einvoice.app.security.ApiKeyNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Renders the browser surface's failures as <strong>pages</strong>, not as problem documents.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code ApiExceptionHandler} is a {@code @RestControllerAdvice} with no package restriction, so
 * it applies to every controller in the application — including the Thymeleaf ones. Without this
 * class, mistyping an invoice id in the dashboard's address bar produced a correct 404 whose body
 * was {@code application/problem+json}: raw JSON in a browser window, in English, from a German UI.
 * The status was right and the experience was wrong.
 *
 * <p>Two things make this advice win for web controllers without touching the API's behaviour:
 *
 * <ul>
 *   <li>{@code basePackages} restricts it to {@code ..app.web..}, so {@code ..app.api..} keeps
 *       answering problem+json exactly as before — the REST contract is untouched;
 *   <li>{@link Order} with the highest precedence, because when two advices both match a controller
 *       Spring picks by order, and a package-scoped advice does not automatically outrank an
 *       unscoped one.
 * </ul>
 *
 * <p>Only the not-found family is handled. A genuine 500 on a dashboard route falls through to
 * {@code ApiExceptionHandler}'s catch-all, which logs it in full and leaks nothing — the right
 * behaviour, and one this class must not quietly replace with a friendlier page that forgets to
 * log.
 */
@ControllerAdvice(basePackages = "com.stoicera.einvoice.app.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebExceptionHandler {

  /**
   * An id that is not this tenant's — whether it never existed or belongs to someone else, which
   * stays deliberately indistinguishable, exactly as on the API.
   */
  @ExceptionHandler({
    InvoiceNotFoundException.class,
    ReportNotFoundException.class,
    ApiKeyNotFoundException.class
  })
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String notFound() {
    return "error/404";
  }
}
