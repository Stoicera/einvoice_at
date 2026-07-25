package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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
 * End-to-end proof of {@link RateLimitFilter} against {@code POST /api/v1/validate}: 3 anonymous
 * requests pass, the 4th is rejected with a {@code 429} problem+json body and a positive integer
 * {@code Retry-After}, and — the bypass this whole filter exists to preserve — an authenticated
 * request still succeeds immediately after that anonymous bucket is exhausted.
 *
 * <p><b>Path-encoding bypass regression guard.</b> {@link RateLimitFilter#shouldNotFilter} used to
 * compare against {@link HttpServletRequest#getRequestURI()} with raw string equality — the
 * undecoded, un-normalized URI straight off the wire — while the security layer (and the
 * DispatcherServlet's own routing) matches the DECODED path. A percent-encoded path segment (e.g.
 * {@code /api/v1/%76alidate}, "v" percent-encoded) or a matrix-parameterized one (e.g. {@code
 * /api/v1/validate;x=y}) still resolves as the {@code permitAll} validator for authorization and
 * dispatch purposes but failed that raw comparison, so the filter was skipped entirely and the
 * anonymous bucket was never charged — unlimited anonymous access to the compute-heavy validator
 * through either variant. The test below sends the percent-encoded variant genuinely
 * percent-encoded on the wire (confirmed with a raw-socket capture during development, since {@code
 * java.net.http.HttpClient} could in principle normalize it first; see the fix-wave report) and
 * asserts it is charged against, and then rejected by, the exact same per-IP bucket as the plain
 * path — proving the filter no longer treats either variant as a different, unlimited route.
 *
 * <p>The matrix-parameterized variant is exercised too, but its expectation is a {@code 400}, not a
 * {@code 429}: this deployment's default Spring Security {@code StrictHttpFirewall} rejects any
 * request whose raw URI contains a semicolon before it ever reaches {@code FilterChainProxy}'s
 * internal filters — including {@link RateLimitFilter} and {@code AuthorizationFilter} — so on this
 * branch, with the firewall's out-of-the-box defaults, that particular variant was never actually
 * reachable as a limiter bypass; the assertion below is a regression guard confirming that stays
 * true rather than a proof of the fix (the percent-encoded case carries that proof).
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
 * <p>The whole scenario, including the path-encoding regression guard, is one test method rather
 * than several: {@link RateLimitFilter}'s bucket map is a singleton bean keyed by client IP, shared
 * by every request this Spring context serves for the life of the class — and every request in this
 * class comes from the same loopback client IP — so splitting the exhaust-then-bypass flow (or the
 * path-variant assertions folded into it) across separate {@code @Test} methods would make one
 * method's outcome depend on however much of the shared bucket a previous method already spent,
 * instead of each being self-contained.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitIT extends AbstractKeycloakIT {

  private static final String VALIDATE = "/api/v1/validate";
  // "v" of "validate" percent-encoded (%76) — decodes to the exact same path the security layer
  // and DispatcherServlet route as the permitAll validator, but fails a raw-string comparison.
  private static final String VALIDATE_PERCENT_ENCODED = "/api/v1/%76alidate";
  // A matrix parameter on the last path segment — RFC 3986 path "params", stripped by Spring's own
  // PathContainer when matching but present verbatim in the raw request-target/getRequestURI().
  // Spring Security's default StrictHttpFirewall rejects any semicolon in the raw URI outright
  // (400, see class Javadoc), so this never reaches RateLimitFilter at all on this branch's
  // defaults.
  private static final String VALIDATE_MATRIX_PARAM = "/api/v1/validate;x=y";
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
    // Fill the capacity-3 bucket with a mix of the plain path and the percent-encoded path
    // variant. If RateLimitFilter ever again compared only the raw, undecoded request URI, the
    // encoded request below would fail shouldNotFilter's match, be treated as "some other,
    // unlimited route", and pass for free no matter how many times it were repeated — never truly
    // charging the shared per-IP bucket at all. Requiring it to be the request that consumes the
    // LAST token (rather than one of the first two) forces that possibility to fail loudly: if it
    // silently bypassed the filter, the plain-path 4th request below would still see 1 token left
    // and wrongly return 200.
    HttpResponse<String> first = postTo(VALIDATE, Fixtures.validFileBytes(), null);
    assertThat(first.statusCode()).as("1st anonymous request, plain path").isEqualTo(200);

    HttpResponse<String> second = postTo(VALIDATE, Fixtures.validFileBytes(), null);
    assertThat(second.statusCode()).as("2nd anonymous request, plain path").isEqualTo(200);

    HttpResponse<String> third = postTo(VALIDATE_PERCENT_ENCODED, Fixtures.validFileBytes(), null);
    assertThat(third.statusCode())
        .as("3rd anonymous request, percent-encoded path — consumes the bucket's last token")
        .isEqualTo(200);

    // The bucket is now empty: every remaining call against this route — whichever form it takes
    // — must be rejected. A separate bucket for the encoded form (the pre-fix bug) would instead
    // let this through.
    HttpResponse<String> blockedEncoded =
        postTo(VALIDATE_PERCENT_ENCODED, Fixtures.validFileBytes(), null);
    assertThat(blockedEncoded.statusCode())
        .as("percent-encoded-path request against the now-exhausted shared bucket")
        .isEqualTo(429);

    // Regression guard, not a proof of this fix (see class Javadoc): Spring Security's default
    // StrictHttpFirewall already rejects this request for its semicolon before it reaches
    // RateLimitFilter, AuthorizationFilter, or the DispatcherServlet — a 400, never a 200 or a 429.
    HttpResponse<String> matrixParamVariant =
        postTo(VALIDATE_MATRIX_PARAM, Fixtures.validFileBytes(), null);
    assertThat(matrixParamVariant.statusCode())
        .as("matrix-parameterized-path request, rejected by the firewall ahead of this filter")
        .isEqualTo(400);

    HttpResponse<String> blocked = postTo(VALIDATE, Fixtures.validFileBytes(), null);
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
    HttpResponse<String> authenticated = postTo(VALIDATE, Fixtures.validFileBytes(), bearer(token));
    assertThat(authenticated.statusCode()).isEqualTo(200);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * Sends the multipart validate request to {@code rawPath}, built via {@link URI#create(String)}
   * so a percent-encoded or matrix-parameterized path is preserved exactly as given rather than
   * normalized by this method — {@code java.net.http.HttpClient} sends whatever {@link
   * URI#getRawPath()} returns as the request-target, confirmed on the wire with a throwaway
   * raw-socket capture during development (see the fix-wave report for the captured request line).
   */
  private HttpResponse<String> postTo(String rawPath, byte[] fileBytes, String[] auth)
      throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fileBytes);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
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
