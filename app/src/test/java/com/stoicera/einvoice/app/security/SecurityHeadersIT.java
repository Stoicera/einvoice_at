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

  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse("<absent>");
  }
}
