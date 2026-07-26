package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Docker-free unit tests for {@link RateLimitFilter}: the capacity boundary, the problem+json shape
 * and {@code Retry-After} header on rejection, refill after the configured period (via a fake
 * {@link TimeMeter}), the authenticated bypass for both authentication kinds — including the
 * specific {@code AnonymousAuthenticationToken} trap {@link CurrentTenant#resolveIfAuthenticated}
 * already avoids — independent per-IP buckets, the exact-route match, and the tracked-client
 * eviction bound.
 */
class RateLimitFilterTest {

  private static final String VALIDATE = "/api/v1/validate";
  private static final String CONVERT = "/api/v1/convert";
  private static final String API_EXPLAIN =
      "/api/v1/reports/1c1a9b6e-0000-0000-0000-000000000001/explain";
  private static final String UI_EXPLAIN =
      "/app/berichte/1c1a9b6e-0000-0000-0000-000000000001/erklaeren";
  private static final String PUBLIC_EXPLAIN = "/validator/erklaeren";
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Any limit will do for the eviction test; only the map's size is under assertion there. */
  private static final RateLimitFilter.Limit SWEEP_LIMIT =
      new RateLimitFilter.Limit("validate", VALIDATE, 10, 10, true);

  @AfterEach
  void clearSecurityContext() {
    // RateLimitFilter reads SecurityContextHolder directly (it runs before Spring MVC, so there is
    // no per-request context reset to rely on here); each test must not leak into the next.
    SecurityContextHolder.clearContext();
  }

  @Test
  void anonymousRequestsUpToCapacityPassThroughThenTheNextIsRejectedWith429() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(3, 3, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.1";

    for (int i = 0; i < 3; i++) {
      filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain);
    }
    assertThat(chain.invocations()).isEqualTo(3);

    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), blocked, chain);

    assertThat(chain.invocations()).isEqualTo(3); // the 4th call never reached the chain
    assertThat(blocked.getStatus()).isEqualTo(429);
  }

  @Test
  void aRejectedRequestGetsAProblemJsonBodyAndAPositiveIntegerRetryAfterHeader() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.2";

    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain); // consumes the token
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), blocked, chain);

    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getContentType()).isEqualTo("application/problem+json");

    String retryAfter = blocked.getHeader("Retry-After");
    assertThat(retryAfter).isNotNull();
    assertThat(Integer.parseInt(retryAfter)).isPositive();

    JsonNode problem = JSON.readTree(blocked.getContentAsString());
    assertThat(problem.get("type").asText())
        .isEqualTo("https://einvoice-at.stoicera.com/problems/rate-limited");
    assertThat(problem.get("title").asText()).isNotBlank();
    assertThat(problem.get("status").asInt()).isEqualTo(429);
    assertThat(problem.get("detail").asText()).isNotBlank();
  }

  @Test
  void refillAfterTheConfiguredPeriodElapsesAllowsAnotherRequest() throws Exception {
    MutableTimeMeter clock = new MutableTimeMeter();
    RateLimitFilter filter =
        new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000, clock); // 1 token, 1 token per minute
    CountingChain chain = new CountingChain();
    String ip = "198.51.100.7";

    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain);
    assertThat(chain.invocations()).isEqualTo(1);

    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), blocked, chain);
    assertThat(chain.invocations()).isEqualTo(1);
    assertThat(blocked.getStatus()).isEqualTo(429);

    clock.advance(Duration.ofMinutes(1)); // the full refill period elapses on the fake clock
    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain);
    assertThat(chain.invocations()).isEqualTo(2);
  }

  @Test
  void distinctClientIpsEachGetTheirOwnCapacity() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();

    filter.doFilter(anonymousPost("203.0.113.10"), new MockHttpServletResponse(), chain);
    filter.doFilter(anonymousPost("203.0.113.11"), new MockHttpServletResponse(), chain);

    assertThat(chain.invocations()).isEqualTo(2); // neither IP was blocked by the other's bucket
  }

  @Test
  void onlyExactPostToValidateIsRateLimitedOtherRoutesAndMethodsPassThroughUnthrottled()
      throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.20";

    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain); // exhausts the token
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), blocked, chain);
    assertThat(blocked.getStatus()).isEqualTo(429);

    MockHttpServletRequest get = new MockHttpServletRequest("GET", VALIDATE);
    get.setRemoteAddr(ip);
    filter.doFilter(get, new MockHttpServletResponse(), chain);

    MockHttpServletRequest otherPost = new MockHttpServletRequest("POST", "/api/v1/invoices");
    otherPost.setRemoteAddr(ip);
    filter.doFilter(otherPost, new MockHttpServletResponse(), chain);

    // The 1 allowed POST /validate + the GET + the other POST — none of the non-matching requests
    // touched this IP's exhausted bucket.
    assertThat(chain.invocations()).isEqualTo(3);
  }

  @Test
  void anAnonymousAuthenticationTokenIsStillRateLimitedDespiteAnsweringTrueToIsAuthenticated()
      throws Exception {
    // The exact trap CurrentTenant.resolveIfAuthenticated's Javadoc warns about: Spring Security's
    // AnonymousAuthenticationToken answers true to isAuthenticated() for a permitAll request, so a
    // filter that trusted that call alone would let every anonymous caller bypass the limiter.
    AnonymousAuthenticationToken anonymous =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    assertThat(anonymous.isAuthenticated()).isTrue();
    SecurityContextHolder.getContext().setAuthentication(anonymous);

    RateLimitFilter filter = new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.30";

    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain);
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), blocked, chain);

    assertThat(chain.invocations()).isEqualTo(1);
    assertThat(blocked.getStatus()).isEqualTo(429);
  }

  @Test
  void aJwtAuthenticatedCallerBypassesTheLimiterEvenAfterTheAnonymousBucketIsExhausted()
      throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.40";

    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain); // consumes the token
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), blocked, chain);
    assertThat(blocked.getStatus()).isEqualTo(429);

    SecurityContextHolder.getContext().setAuthentication(jwtAuthentication());
    MockHttpServletResponse allowed = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), allowed, chain);

    assertThat(chain.invocations()).isEqualTo(2); // the first pass + this bypassed one
  }

  @Test
  void anApiKeyAuthenticatedCallerBypassesTheLimiterEvenAfterTheAnonymousBucketIsExhausted()
      throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.41";

    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain); // consumes the token
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), blocked, chain);
    assertThat(blocked.getStatus()).isEqualTo(429);

    SecurityContextHolder.getContext()
        .setAuthentication(
            ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));
    MockHttpServletResponse allowed = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), allowed, chain);

    assertThat(chain.invocations()).isEqualTo(2);
  }

  @Test
  void trackedClientsAreBoundedByASweepOnceTheHardCapIsExceeded() {
    RateLimitFilter filter = new RateLimitFilter(10, 10, 1000, 1000, 1000, 1000);

    // MAX_TRACKED_CLIENTS is 10_000 (see RateLimitFilter); one over that forces a sweep.
    for (int i = 0; i <= 10_000; i++) {
      filter.bucketFor(
          "10." + (i / (256 * 256)) + "." + ((i / 256) % 256) + "." + (i % 256), SWEEP_LIMIT);
    }

    assertThat(filter.trackedClientCount()).isLessThanOrEqualTo(10_000);
    // The sweep drops down to the 75% eviction target, not just barely under the cap.
    assertThat(filter.trackedClientCount()).isLessThan(9_000);
  }

  /**
   * {@code /convert} limits authenticated callers — the opposite of {@code /validate}, and the
   * whole point of the M4 hostile review's finding F9.
   *
   * <p>That endpoint requires authentication. Had it inherited the validator's "authenticated
   * callers are exempt" policy, the limit would apply to exactly nobody, which is the state M4
   * shipped in: the most expensive operation in the platform, unlimited.
   */
  @Test
  void convertLimitsAuthenticatedCallersUnlikeValidate() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1000, 1000, 1, 1, 1000, 1000);
    CountingChain chain = new CountingChain();
    SecurityContextHolder.getContext()
        .setAuthentication(
            ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));

    filter.doFilter(post(CONVERT, "203.0.113.50"), new MockHttpServletResponse(), chain);
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(post(CONVERT, "203.0.113.50"), blocked, chain);

    assertThat(chain.invocations()).isEqualTo(1);
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getContentAsString()).contains(CONVERT);
  }

  /**
   * Two tenants sharing one egress address do not share an allowance: an authenticated caller is
   * bucketed by credential, not by IP. Keying {@code /convert} by IP would punish every tenant
   * behind a NAT for one tenant's traffic — and let one tenant multiply their own allowance by
   * calling from several addresses.
   */
  @Test
  void convertBucketsAuthenticatedCallersByCredentialNotByIp() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1000, 1000, 1, 1, 1000, 1000);
    CountingChain chain = new CountingChain();
    String sharedIp = "203.0.113.51";

    SecurityContextHolder.getContext()
        .setAuthentication(
            ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));
    filter.doFilter(post(CONVERT, sharedIp), new MockHttpServletResponse(), chain);

    // A different tenant, same address: their own bucket, still full.
    SecurityContextHolder.getContext()
        .setAuthentication(
            ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));
    MockHttpServletResponse second = new MockHttpServletResponse();
    filter.doFilter(post(CONVERT, sharedIp), second, chain);

    assertThat(chain.invocations()).isEqualTo(2);
    assertThat(second.getStatus()).isEqualTo(200);
  }

  /** The two routes hold separate buckets, so conversions cannot spend a validator's allowance. */
  @Test
  void theTwoRoutesDoNotShareABucket() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1, 1, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.52";

    filter.doFilter(post(CONVERT, ip), new MockHttpServletResponse(), chain);
    MockHttpServletResponse convertBlocked = new MockHttpServletResponse();
    filter.doFilter(post(CONVERT, ip), convertBlocked, chain);
    assertThat(convertBlocked.getStatus()).isEqualTo(429);

    // The same anonymous client's validate bucket is untouched.
    MockHttpServletResponse validateAllowed = new MockHttpServletResponse();
    filter.doFilter(anonymousPost(ip), validateAllowed, chain);

    assertThat(validateAllowed.getStatus()).isEqualTo(200);
    assertThat(chain.invocations()).isEqualTo(2);
  }

  // --- the explain routes (M5 hostile review, F4) -----------------------------------------------

  /**
   * The finding this closes: {@code RateLimitFilter}'s own Javadoc argued that a paid LLM call
   * needs a limit for the same reason CPU does — and then limited only the <em>anonymous</em>
   * route. The two authenticated explain routes, which spend the operator's provider budget per
   * click, were limited by nothing at all. {@code app.ai.max-findings-per-request} bounds one
   * request; it says nothing about a rate.
   */
  @Test
  void theAuthenticatedExplainRoutesAreLimitedForAuthenticatedCallersToo() throws Exception {
    for (String path : new String[] {API_EXPLAIN, UI_EXPLAIN}) {
      RateLimitFilter filter = new RateLimitFilter(1000, 1000, 1000, 1000, 1, 1);
      CountingChain chain = new CountingChain();
      SecurityContextHolder.getContext()
          .setAuthentication(
              ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));

      filter.doFilter(post(path, "203.0.113.60"), new MockHttpServletResponse(), chain);
      MockHttpServletResponse blocked = new MockHttpServletResponse();
      filter.doFilter(post(path, "203.0.113.60"), blocked, chain);

      assertThat(chain.invocations()).as("%s", path).isEqualTo(1);
      assertThat(blocked.getStatus()).as("%s", path).isEqualTo(429);
    }
  }

  @Test
  void theExplainBucketIsSeparateFromValidateAndConvert() throws Exception {
    // Exhausting explanations must not stop a tenant validating or converting, and vice versa: the
    // three routes cost different things and one budget for all of them would couple them.
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1, 1, 1, 1);
    CountingChain chain = new CountingChain();
    SecurityContextHolder.getContext()
        .setAuthentication(
            ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));

    filter.doFilter(post(API_EXPLAIN, "203.0.113.61"), new MockHttpServletResponse(), chain);
    MockHttpServletResponse explainBlocked = new MockHttpServletResponse();
    filter.doFilter(post(API_EXPLAIN, "203.0.113.61"), explainBlocked, chain);
    assertThat(explainBlocked.getStatus()).isEqualTo(429);

    MockHttpServletResponse convertAllowed = new MockHttpServletResponse();
    filter.doFilter(post(CONVERT, "203.0.113.61"), convertAllowed, chain);

    assertThat(convertAllowed.getStatus()).isEqualTo(200);
    assertThat(chain.invocations()).isEqualTo(2);
  }

  @Test
  void theTwoExplainRoutesShareOneBudgetBecauseTheyBuyTheSameThing() throws Exception {
    // The dashboard button and the API endpoint explain the same stored report through the same
    // provider. Separate buckets would hand one tenant two allowances for one bill.
    RateLimitFilter filter = new RateLimitFilter(1000, 1000, 1000, 1000, 1, 1);
    CountingChain chain = new CountingChain();
    SecurityContextHolder.getContext()
        .setAuthentication(
            ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));

    filter.doFilter(post(UI_EXPLAIN, "203.0.113.62"), new MockHttpServletResponse(), chain);
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(post(API_EXPLAIN, "203.0.113.62"), blocked, chain);

    assertThat(chain.invocations()).isEqualTo(1);
    assertThat(blocked.getStatus()).isEqualTo(429);
  }

  @Test
  void theExplainLimitMatchesAnyReportIdNotJustOne() throws Exception {
    // A per-path-literal matcher would be trivially bypassed by explaining a different report each
    // time, which is exactly what a caller burning the budget would do.
    RateLimitFilter filter = new RateLimitFilter(1000, 1000, 1000, 1000, 1, 1);
    CountingChain chain = new CountingChain();
    SecurityContextHolder.getContext()
        .setAuthentication(
            ApiKeyAuthenticationToken.authenticated(UUID.randomUUID(), UUID.randomUUID()));

    filter.doFilter(
        post("/api/v1/reports/" + UUID.randomUUID() + "/explain", "203.0.113.63"),
        new MockHttpServletResponse(),
        chain);
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(
        post("/api/v1/reports/" + UUID.randomUUID() + "/explain", "203.0.113.63"), blocked, chain);

    assertThat(chain.invocations()).isEqualTo(1);
    assertThat(blocked.getStatus()).isEqualTo(429);
  }

  /**
   * The public route keeps its own policy, unchanged: it shares the validate bucket and exempts
   * authenticated callers. It is anonymous-reachable, so its risk is abuse of a free endpoint; the
   * authenticated routes' risk is a tenant's own spending, which is a different bucket and a
   * different rule.
   */
  @Test
  void thePublicExplainRouteStillSharesTheValidateBucket() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(1, 1, 1000, 1000, 1000, 1000);
    CountingChain chain = new CountingChain();
    String ip = "203.0.113.64";

    filter.doFilter(anonymousPost(ip), new MockHttpServletResponse(), chain);
    MockHttpServletResponse blocked = new MockHttpServletResponse();
    filter.doFilter(post(PUBLIC_EXPLAIN, ip), blocked, chain);

    assertThat(chain.invocations()).isEqualTo(1);
    assertThat(blocked.getStatus()).isEqualTo(429);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static MockHttpServletRequest anonymousPost(String remoteAddr) {
    return post(VALIDATE, remoteAddr);
  }

  private static MockHttpServletRequest post(String path, String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(remoteAddr);
    return request;
  }

  private static JwtAuthenticationToken jwtAuthentication() {
    Jwt jwt =
        new Jwt(
            "test-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("sub", "test-user"));
    return new JwtAuthenticationToken(jwt);
  }

  /** A {@link TimeMeter} a test can advance deterministically, for refill assertions. */
  private static final class MutableTimeMeter implements TimeMeter {
    private long nanos;

    @Override
    public long currentTimeNanos() {
      return nanos;
    }

    @Override
    public boolean isWallClockBased() {
      return true;
    }

    void advance(Duration duration) {
      nanos += duration.toNanos();
    }
  }

  /**
   * Counts invocations instead of doing real work — the filter's decision is what is under test.
   */
  private static final class CountingChain implements FilterChain {
    private final AtomicInteger count = new AtomicInteger();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) {
      count.incrementAndGet();
    }

    int invocations() {
      return count.get();
    }
  }
}
