package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Pins the response security headers ENGINEERING_STANDARDS §4 requires.
 *
 * <p>They come from Spring Security's defaults, which {@code SecurityConfig} deliberately leaves in
 * place — but nothing asserted them, so a future {@code .headers(HeadersConfigurer::disable)}, or a
 * migration that changed the defaults, would have shipped unnoticed (M3 hostile review, F10).
 * Asserting a framework default is worth it precisely because it is invisible: no line of this
 * project's code would have to change for it to disappear.
 *
 * <p>The 401 route is used on purpose. Headers on an error response are the ones most often lost,
 * and they are also the ones that matter for an unauthenticated attacker's browser. {@code
 * Cache-Control: no-store} additionally covers the review's question about the one response that
 * carries an API-key plaintext, since the same default applies to every route.
 *
 * <h2>The browser surface has its own requirements (M5)</h2>
 *
 * <p>Until the M5 hostile review (finding F8) this class covered {@code /api/**} only, and it was
 * not extended when M5 added HTML pages — so the surface with the <em>most</em> to gain from
 * response headers had no assertion at all. An API that answers {@code problem+json} needs nosniff
 * and no framing; a page that renders model output and assigns server HTML into {@code innerHTML}
 * additionally wants a <strong>Content-Security-Policy</strong>, because a CSP is the control that
 * still holds when an escaping bug gets through. {@code Referrer-Policy} is here for a different
 * reason: the public validator is a German-SEO landing page, so its outbound links would otherwise
 * leak the full referring URL to third parties.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersIT extends AbstractPostgresIT {

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void everyApiResponseCarriesTheDefaultSecurityHeaders() throws Exception {
    HttpResponse<Void> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/invoices"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());

    assertThat(response.statusCode()).isEqualTo(401);
    // No MIME sniffing: a response the API says is JSON must not be reinterpreted as HTML/script.
    assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
    // No framing: nothing here is meant to be embedded, so clickjacking has no surface.
    assertThat(header(response, "X-Frame-Options")).isEqualTo("DENY");
    // Nothing from this API — least of all a credential or an invoice — belongs in a shared cache.
    assertThat(header(response, "Cache-Control")).contains("no-store");
  }

  @Test
  void theHealthProbeCarriesThemToo() throws Exception {
    // A permitAll route goes through the same chain, so it must not be a gap in the coverage.
    HttpResponse<Void> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(header(response, "X-Frame-Options")).isEqualTo("DENY");
  }

  @Test
  void everyBrowserPageCarriesAContentSecurityPolicyAndAReferrerPolicy() throws Exception {
    for (String page : new String[] {"/", "/validator"}) {
      HttpResponse<Void> response = get(page);

      assertThat(response.statusCode()).as("%s", page).isEqualTo(200);
      assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(header(response, "X-Frame-Options")).isEqualTo("DENY");
      assertThat(header(response, "Referrer-Policy")).isEqualTo("no-referrer");

      String csp = header(response, "Content-Security-Policy");
      assertThat(csp).as("a CSP on %s", page).isNotEqualTo("<absent>");
      // The four clauses that carry the weight, asserted individually so a future edit that
      // loosens one is a failing test rather than a diff nobody reads.
      assertThat(csp)
          // No default source at all: anything not named below is refused outright.
          .contains("default-src 'none'")
          // First-party scripts only — this UI vendors nothing and loads from no CDN (ADR-0009),
          // so 'self' is the whole allowance and there is no 'unsafe-inline' to grant.
          .contains("script-src 'self'")
          // The forms post to this origin only; an injected form cannot exfiltrate to another.
          .contains("form-action 'self'")
          // Belt over X-Frame-Options for browsers that honour the CSP directive instead.
          .contains("frame-ancestors 'none'");
    }
  }

  @Test
  void theApiDoesNotCarryTheBrowserPolicy() throws Exception {
    // The contrast matters: a CSP on a JSON API is noise, and the two chains are configured
    // separately on purpose (ADR-0009). Swagger UI in particular loads its own assets and would
    // break under the page policy above, so it must not inherit it.
    assertThat(header(get("/api/v1/invoices"), "Content-Security-Policy")).isEqualTo("<absent>");
    assertThat(header(get("/v3/api-docs"), "Content-Security-Policy")).isEqualTo("<absent>");
  }

  private HttpResponse<Void> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse("<absent>");
  }
}
