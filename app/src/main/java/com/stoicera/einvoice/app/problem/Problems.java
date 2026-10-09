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

  /**
   * The base when nothing is configured: the public demo, which answers every {@code
   * /problems/{slug}} with a redirect to that slug's entry in {@code docs/problems.md} ({@code
   * ProblemTypeController}). Until 2026-10 this was {@code einvoice-at.stoicera.com}, a host that
   * never resolved, so no {@code type} URI the API emitted could be followed.
   */
  static final String DEFAULT_BASE = "https://einvoice.sebastiankern.net/problems/";

  /** Overrides {@link #DEFAULT_BASE}, so a self-hosted instance can point at its own host. */
  static final String BASE_ENV = "PROBLEM_TYPE_BASE_URI";

  /**
   * Every {@code type} URI the API emits is this base plus one stable per-condition slug.
   *
   * <p>Read from the environment once, at class initialisation, rather than injected from Spring
   * configuration: the servlet filters write problems by hand through the static {@link #write},
   * and a value that could differ between the MVC handler and a filter would be exactly the "two
   * copies of one contract" this class exists to prevent. A deployment sets it once and restarts.
   */
  public static final String BASE = resolveBase(System.getenv(BASE_ENV));

  // Same construction idiom as the services' private findingsMapper: a local Jackson 3 mapper, not
  // a Spring-managed bean — the map written below has no configuration-sensitive content.
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Problems() {}

  /**
   * The configured base, or {@link #DEFAULT_BASE} when it is blank. It must be an absolute http(s)
   * URI — a relative or malformed one fails startup instead of producing {@code type} values a
   * client cannot resolve — and it always ends in {@code /}, so {@code BASE + slug} stays a path
   * segment whether or not the operator typed the trailing slash.
   */
  static String resolveBase(String configured) {
    if (configured == null || configured.isBlank()) {
      return DEFAULT_BASE;
    }
    String base = configured.strip();
    URI uri = URI.create(base);
    if (!uri.isAbsolute()
        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
        || uri.getHost() == null) {
      throw new IllegalArgumentException(
          BASE_ENV + " must be an absolute http(s) URI, got: " + configured);
    }
    return base.endsWith("/") ? base : base + "/";
  }

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
