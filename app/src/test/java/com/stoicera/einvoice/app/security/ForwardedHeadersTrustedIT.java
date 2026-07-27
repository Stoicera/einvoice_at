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
 * {@code SERVER_FORWARD_HEADERS_STRATEGY=native}: behind a trusted reverse proxy, the per-IP rate
 * limit applies to the <em>client</em>, not to the proxy — and not to an address the client made up
 * (M6; the item the M5 review deferred as "{@code X-Forwarded-For} handling", plus the M6 hostile
 * review's finding F1).
 *
 * <p><strong>Why this matters more than it looks.</strong> {@link RateLimitFilter} keys its buckets
 * on {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}. Put Traefik in front of the
 * application without telling the application, and every anonymous caller on the internet arrives
 * with Traefik's address: one shared bucket, so the first abusive client rate-limits everybody
 * else. The public validator — an open, CPU-heavy endpoint — is exactly where that would hurt.
 *
 * <p><strong>{@code native}, not {@code framework}, and the difference is the security of the
 * control.</strong> Both strategies rewrite the request from {@code X-Forwarded-For}; they disagree
 * about which entry of the chain is the client, and a forwarded chain is part trustworthy and part
 * not. A proxy <em>appends</em> the peer it saw to whatever header arrived, so the rightmost entry
 * is the only one anything trustworthy wrote and everything to its left is caller-supplied text.
 *
 * <ul>
 *   <li>{@code framework} — Spring's {@code ForwardedHeaderFilter} — takes the <strong>leftmost
 *       </strong> entry ({@code ForwardedHeaderUtils.parseForwardedFor}: {@code
 *       tokenizeToStringArray(header, ",")[0]}). That is precisely the attacker-controlled end, so
 *       the per-IP limit costs one header line to bypass. {@link
 *       #ignoresAddressesPrependedByTheClient} is the test that demonstrates it: written against
 *       {@code framework} it fails, with all three requests admitted.
 *   <li>{@code native} — Tomcat's {@code RemoteIpValve} — walks the chain <strong>right to
 *       left</strong>, discarding entries that match {@code
 *       server.tomcat.remoteip.internal-proxies} (Boot's default covers loopback and every RFC 1918
 *       range, so a proxy on a Docker bridge network is already covered) and stopping at the first
 *       address that does not. Anything the client prepended is left behind.
 * </ul>
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

  /** The address the proxy itself observed, i.e. the only entry in a chain worth believing. */
  private static final String REAL_CLIENT = "203.0.113.77";

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void trustTheProxyAndLimitTightly(DynamicPropertyRegistry registry) {
    registry.add("server.forward-headers-strategy", () -> "native");
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

  /**
   * A caller cannot mint itself a fresh bucket by <em>prepending</em> an address to {@code
   * X-Forwarded-For} — the M6 hostile review's finding F1, and the reason the strategy is {@code
   * native} (see the class Javadoc for which end of the chain each strategy believes).
   *
   * <p>A proxy appends: it takes whatever {@code X-Forwarded-For} arrived and adds the peer it
   * actually saw, so a request a client sent as {@code X-Forwarded-For: 198.51.100.1} reaches the
   * application as {@code X-Forwarded-For: 198.51.100.1, 203.0.113.77}. Three requests from one
   * client must therefore share one bucket however many upstreams that client invents.
   *
   * <p>Change the strategy back to {@code framework} and this test fails with three admitted
   * requests — which is what makes it a regression test for the finding rather than a restatement
   * of the configuration.
   */
  @Test
  @DisplayName("a client cannot mint a new bucket by prepending an address to X-Forwarded-For")
  void ignoresAddressesPrependedByTheClient() throws Exception {
    // Three requests from ONE real client, each claiming a different upstream that never existed.
    assertThat(validateAs("198.51.100.1, " + REAL_CLIENT).statusCode()).isEqualTo(200);
    assertThat(validateAs("198.51.100.2, " + REAL_CLIENT).statusCode()).isEqualTo(200);
    assertThat(validateAs("198.51.100.3, " + REAL_CLIENT).statusCode())
        .as("all three requests are the same client, so they must share one bucket")
        .isEqualTo(429);
  }

  private HttpResponse<String> validateAs(String forwardedFor) throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", Fixtures.validFileBytes());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + VALIDATE))
            .header("Content-Type", multipart.contentType())
            .header("X-Forwarded-For", forwardedFor)
            .header("X-Forwarded-Proto", "https")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
