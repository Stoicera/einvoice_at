package com.stoicera.einvoice.app.web;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The absolute URL the public pages are published under, for everything that has to name itself
 * from the outside: the canonical link and {@code og:url} on the two public pages, the {@code
 * og:image} URL, and every {@code <loc>} in {@code /sitemap.xml}.
 *
 * <p>Configured ({@code app.public-base-url}, env {@code APP_PUBLIC_BASE_URL}) rather than derived
 * from the request, for the same reason {@code Problems.BASE} is: behind a reverse proxy the
 * request sees whatever scheme and host the proxy forwarded (or did not — {@code
 * SERVER_FORWARD_HEADERS_STRATEGY} is {@code none} by default), and a canonical URL that follows
 * the {@code Host} header is one a caller can rewrite. Blank means the public demo (the same
 * convention as {@code PROBLEM_TYPE_BASE_URI}); a self-hosted instance sets its own host. A value
 * that is not an absolute http(s) URI fails startup, so no page ever carries a canonical link a
 * crawler cannot resolve.
 */
@Component
public class PublicSite {

  static final String DEFAULT_BASE_URL = "https://einvoice.sebastiankern.net";

  private final String baseUrl;

  PublicSite(@Value("${app.public-base-url:}") String baseUrl) {
    String configured = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
    URI uri = URI.create(configured);
    if (!uri.isAbsolute()
        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
        || uri.getHost() == null) {
      throw new IllegalArgumentException(
          "app.public-base-url must be an absolute http(s) URI, got: " + baseUrl);
    }
    this.baseUrl =
        configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
  }

  /** The absolute URL of a path on the public host; {@code path} must start with {@code /}. */
  public String url(String path) {
    return baseUrl + path;
  }
}
