package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end proof of {@link RateLimitFilter} against {@code POST /api/v1/validate} (T8): 3
 * anonymous requests pass, the 4th is rejected with a {@code 429} problem+json body and a positive
 * integer {@code Retry-After}, and — the bypass this whole filter exists to preserve — an
 * authenticated request still succeeds immediately after that anonymous bucket is exhausted.
 *
 * <p>Runs in its own Spring context, deliberately not the one {@code ValidateApiIT}, {@code
 * AuthMatrixIT} and the other auth ITs share: this class's own {@link DynamicPropertySource} method
 * overrides {@code app.rate-limit.validate.*} to a low capacity (3, refill 1/minute) so the 429
 * path is actually reachable in a short-lived test; every other IT instead inherits {@link
 * AbstractKeycloakIT}'s much higher {@code @TestPropertySource} capacity so this feature landing
 * never makes their unrelated anonymous {@code /validate} calls flaky.
 * {@code @DynamicPropertySource} always outranks {@code @TestPropertySource} regardless of which
 * class in the hierarchy declares it (see {@link AbstractKeycloakIT}'s Javadoc), so this override
 * is guaranteed to win over the inherited one.
 *
 * <p>The whole scenario is one test method rather than several: {@link RateLimitFilter}'s bucket
 * map is a singleton bean, shared by every request this Spring context serves for the life of the
 * class, so splitting the exhaust-then-bypass flow across separate {@code @Test} methods would make
 * its outcome depend on JUnit's method execution order instead of being self-contained.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitIT extends AbstractKeycloakIT {

  private static final String VALIDATE = "/api/v1/validate";
  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";
  private static final int CAPACITY = 3;

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @DynamicPropertySource
  static void lowRateLimit(DynamicPropertyRegistry registry) {
    registry.add("app.rate-limit.validate.capacity", () -> CAPACITY);
    registry.add("app.rate-limit.validate.refill-per-minute", () -> 1);
  }

  @Test
  void anonymousCallsExhaustTheBucketThenAnAuthenticatedCallStillPasses() throws Exception {
    for (int i = 0; i < CAPACITY; i++) {
      HttpResponse<String> response = postValidate(Fixtures.validFileBytes(), null);
      assertThat(response.statusCode())
          .as("anonymous request %d of %d should still be within capacity", i + 1, CAPACITY)
          .isEqualTo(200);
    }

    HttpResponse<String> blocked = postValidate(Fixtures.validFileBytes(), null);
    assertThat(blocked.statusCode()).isEqualTo(429);
    assertThat(contentType(blocked)).contains("application/problem+json");

    String retryAfter = blocked.headers().firstValue("Retry-After").orElse(null);
    assertThat(retryAfter).isNotNull();
    assertThat(Integer.parseInt(retryAfter)).isPositive();

    JsonNode problem = json.readTree(blocked.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "rate-limited");
    assertThat(problem.get("status").asInt()).isEqualTo(429);
    assertThat(problem.get("title").asText()).isNotBlank();
    assertThat(problem.get("detail").asText()).isNotBlank();

    // The bypass proof: the same anonymous bucket is empty (just verified above), but an
    // authenticated caller from the same client IP is never subject to it at all.
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    HttpResponse<String> authenticated = postValidate(Fixtures.validFileBytes(), bearer(token));
    assertThat(authenticated.statusCode()).isEqualTo(200);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private HttpResponse<String> postValidate(byte[] fileBytes, String[] auth) throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fileBytes);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + VALIDATE))
            .header("Content-Type", multipart.contentType());
    if (auth != null) {
      builder.header(auth[0], auth[1]);
    }
    builder.POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String[] bearer(String token) {
    return new String[] {"Authorization", "Bearer " + token};
  }

  private static String contentType(HttpResponse<String> response) {
    return response.headers().firstValue("Content-Type").orElse("");
  }
}
