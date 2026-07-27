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
 * The default, {@code SERVER_FORWARD_HEADERS_STRATEGY=none}: {@code X-Forwarded-For} is
 * <strong>ignored</strong>, so a caller cannot mint itself a fresh rate-limit bucket by inventing a
 * client address (M6).
 *
 * <p>This is the security half of the pair {@link ForwardedHeadersTrustedIT} completes. With no
 * proxy in front of the application, {@code X-Forwarded-For} is caller-supplied text and nothing
 * more; honouring it would make the per-IP limit on the anonymous validator free to bypass — one
 * header line per request, unlimited buckets. The application therefore ships with it off and a
 * deployment that genuinely has a trusted proxy turns it on, which is what {@code
 * docs/deployment.md} does for the Traefik/Dokploy target.
 *
 * <p>No property is set here beyond the tight limit: the point is that the <em>default</em> is
 * safe, so overriding the strategy would defeat the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersUntrustedIT extends AbstractKeycloakIT {

  private static final String VALIDATE = "/api/v1/validate";
  private static final int CAPACITY = 2;

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void limitTightly(DynamicPropertyRegistry registry) {
    registry.add("app.rate-limit.validate.capacity", () -> CAPACITY);
    registry.add("app.rate-limit.validate.refill-per-minute", () -> 1);
  }

  @Test
  @DisplayName("a forged X-Forwarded-For buys no extra allowance when no proxy is trusted")
  void ignoresAForgedForwardedForHeader() throws Exception {
    assertThat(validateAs("203.0.113.10").statusCode()).isEqualTo(200);
    assertThat(validateAs("198.51.100.7").statusCode()).isEqualTo(200);

    // A third, again-different claimed address. All three requests came from the same real peer, so
    // they must have shared one bucket and this one must be refused. If the application ever starts
    // trusting the header by default, this is a 200 and the anonymous limit is bypassable by anyone
    // who can set a header.
    assertThat(validateAs("192.0.2.44").statusCode())
        .as("every claimed address must share the real peer's bucket")
        .isEqualTo(429);
  }

  private HttpResponse<String> validateAs(String claimedClientIp) throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", Fixtures.validFileBytes());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + VALIDATE))
            .header("Content-Type", multipart.contentType())
            .header("X-Forwarded-For", claimedClientIp)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
