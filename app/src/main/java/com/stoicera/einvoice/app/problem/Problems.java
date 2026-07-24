package com.stoicera.einvoice.app.problem;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single source of the API's RFC 9457 problem vocabulary.
 *
 * <p>Two very different layers have to speak it. Spring MVC's {@code ApiExceptionHandler} builds
 * {@link org.springframework.http.ProblemDetail} objects and lets the framework serialise them;
 * servlet filters run <em>outside</em> MVC dispatch, where {@code @RestControllerAdvice} never sees
 * their rejections, so they have to write the same shape by hand. Both took the {@code type} base
 * URI from their own private constant before this class existed — two copies of one contract. The
 * base URI and the hand-written form now live here, so a filter's 413 and a controller's 409 are
 * provably the same vocabulary.
 *
 * <p>Deliberately its own package rather than {@code ..app.api..}: the security filters need it and
 * {@code ..app.api..} already depends on {@code ..app.security..} ({@code CurrentTenant}), so
 * hosting it there would close a package cycle.
 */
public final class Problems {

  /** Every {@code type} URI the API emits is this base plus one stable per-condition slug. */
  public static final String BASE = "https://einvoice-at.stoicera.com/problems/";

  // Same construction idiom as the services' private findingsMapper: a local Jackson 3 mapper, not
  // a Spring-managed bean — the map written below has no configuration-sensitive content.
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Problems() {}

  /** The stable {@code type} URI for a condition slug (e.g. {@code "duplicate-invoice"}). */
  public static URI type(String slug) {
    return URI.create(BASE + slug);
  }

  /**
   * Writes a complete {@code application/problem+json} response by hand, for callers that run
   * outside Spring MVC's dispatch (servlet filters). Any headers the caller wants on the response —
   * {@code Retry-After}, for instance — must be set before calling this, since it commits the body.
   */
  public static void write(
      HttpServletResponse response, HttpStatus status, String slug, String title, String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType("application/problem+json");
    Map<String, Object> problem = new LinkedHashMap<>();
    problem.put("type", BASE + slug);
    problem.put("title", title);
    problem.put("status", status.value());
    problem.put("detail", detail);
    JSON.writeValue(response.getWriter(), problem);
  }
}
