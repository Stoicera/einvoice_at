package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.MultipartBodies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}: behind a trusted reverse proxy, the per-IP
 * rate limit applies to the <em>client</em>, not to the proxy (M6; the item the M5 review deferred
 * as "{@code X-Forwarded-For} handling").
 *
 * <p><strong>Why this matters more than it looks.</strong> {@link RateLimitFilter} keys its buckets
 * on {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}. Put Traefik in front of the
 * application without telling the application, and every anonymous caller on the internet arrives
 * with Traefik's address: one shared bucket, so the first abusive client rate-limits everybody
 * else. The public validator — an open, CPU-heavy endpoint — is exactly where that would hurt.
 *
 * <p>{@link ForwardedHeadersUntrustedIT} is the other half of this pair and asserts the opposite
 * behaviour under the default (`none`), which is what makes the switch a decision rather than a
 * setting nobody checked. Neither test parses a header itself: the switch is Boot's {@code
 * server.forward-headers-strategy}, and this asserts what the application <em>does</em> as a
 * result.
 *
 * <p>Its own low-capacity context, for {@link RateLimitIT}'s reason: the 429 has to be reachable in
 * a short test, and the shared auth-IT context deliberately raises the limit out of the way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersTrustedIT extends AbstractKeycloakIT {

  private static final String VALIDATE = "/api/v1/validate";
  private static final int CAPACITY = 2;

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void trustTheProxyAndLimitTightly(DynamicPropertyRegistry registry) {
    registry.add("server.forward-headers-strategy", () -> "framework");
    registry.add("app.rate-limit.validate.capacity", () -> CAPACITY);
    registry.add("app.rate-limit.validate.refill-per-minute", () -> 1);
  }

  @Test
  @DisplayName(
      "each forwarded client gets its own bucket, and exhausting one does not touch another")
  void limitsPerForwardedClientRatherThanPerProxy() throws Exception {
    // One client burns its whole allowance.
    assertThat(validateAs("203.0.113.10").statusCode()).isEqualTo(200);
    assertThat(validateAs("203.0.113.10").statusCode()).isEqualTo(200);
    assertThat(validateAs("203.0.113.10").statusCode())
        .as("the noisy client's own bucket is exhausted")
        .isEqualTo(429);

    // A different client behind the same proxy is unaffected. Without the forwarded-headers
    // strategy both would share the loopback bucket and this would already be a 429 — which is the
    // whole failure this switch prevents.
    assertThat(validateAs("198.51.100.7").statusCode())
        .as("a second client behind the same proxy must have its own allowance")
        .isEqualTo(200);
  }

  private HttpResponse<String> validateAs(String clientIp) throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", Fixtures.validFileBytes());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + VALIDATE))
            .header("Content-Type", multipart.contentType())
            .header("X-Forwarded-For", clientIp)
            .header("X-Forwarded-Proto", "https")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
