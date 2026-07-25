package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.problem.Problems;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token-bucket rate limiting for the two endpoints that cost real CPU: {@code POST
 * /api/v1/validate} and {@code POST /api/v1/convert} (SPEC section 4).
 *
 * <p><b>Two routes, two policies, and the difference is the point.</b> The public validator is
 * limited for <em>anonymous</em> callers only — the limit is there to keep an open endpoint from
 * being abused, and a caller who has authenticated is not that threat. {@code /convert} is limited
 * for <em>everyone</em>, because it already requires authentication: exempting authenticated
 * callers there would leave a limit that applies to nobody at all. It was, until the M4 hostile
 * review found the most expensive operation in the platform — read, two mappings, a write, and a
 * full Peppol XSLT validation of the result — behind no limit of any kind (finding F9). A 2 MB
 * upload cap bounds one request; it says nothing about a rate.
 *
 * <p><b>Authenticated bypass.</b> Reuses {@link CurrentTenant#resolveIfAuthenticated}'s allow-list
 * idiom: the authentication kind is decided by an explicit {@code instanceof} check against {@link
 * JwtAuthenticationToken} / {@link ApiKeyAuthenticationToken}, never by trusting {@link
 * Authentication#isAuthenticated()}. Spring Security's own {@code AnonymousAuthenticationToken}
 * answers {@code true} to that call for every {@code permitAll} request — trusting it here would
 * let every anonymous caller opt out of the limit for free.
 *
 * <p><b>Chain placement.</b> Registered in {@link SecurityConfig} with {@code addFilterAfter(...,
 * AuthorizationFilter.class)}: by the time {@code AuthorizationFilter} has run, every
 * authentication mechanism ({@link ApiKeyAuthFilter}, the bearer-token filter, and Spring
 * Security's own anonymous-authentication filter) has already populated the {@link
 * SecurityContextHolder}, and the request has already cleared authorization. This filter therefore
 * only ever sees requests the chain actually let through — exhausting the anonymous bucket can
 * never pre-empt the chain's own 401/403 decision, and if this route's authorization rule ever
 * stops being {@code permitAll}, a request that no longer clears it is rejected before reaching
 * here rather than needlessly consuming a token first.
 *
 * <p><b>Keying.</b> {@link HttpServletRequest#getRemoteAddr()} only — deliberately no {@code
 * X-Forwarded-For} parsing. This is a single deployed instance with no trusted reverse proxy in
 * front of it yet, so honoring a client-supplied forwarded-for header would just hand every
 * anonymous caller a free ticket to spoof a different bucket. Once Traefik terminates in front of
 * the app (M6), the proxy's forwarded-header contract needs to be pinned down (trusted-proxy list,
 * which hop to trust) and this filter revisited — see ADR-0005.
 *
 * <p><b>Storage.</b> An in-memory map keyed by remote address, one {@link Bucket} per client.
 * Honest about being single-instance: nothing here is shared across replicas, so a horizontally
 * scaled deployment would need a distributed store (bucket4j ships a proxy for exactly that) rather
 * than this map. The map is bounded at {@link #MAX_TRACKED_CLIENTS}; a write that pushes it over
 * the cap sweeps the least-recently-seen quarter of entries, so an unbounded stream of distinct
 * callers cannot grow this map without limit.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private static final String VALIDATE_PATH = "/api/v1/validate";
  private static final String CONVERT_PATH = "/api/v1/convert";

  // Deliberately NOT request.getRequestURI().equals(VALIDATE_PATH): getRequestURI() returns the
  // raw, undecoded, un-normalized URI straight off the wire, while SecurityConfig's
  // requestMatchers(HttpMethod, "/api/v1/validate") — and the DispatcherServlet's own routing —
  // resolve the request against a decoded PathContainer via PathPatternRequestMatcher. A raw-string
  // equals() check is a different, stricter test than the one that actually decides "is this
  // permitAll /api/v1/validate", so a percent-encoded or matrix-parameterized variant of the same
  // path (e.g. "/api/v1/%76alidate", "/api/v1/validate;x=y") clears authorization as the public
  // validator yet fails this raw comparison — an unlimited-anonymous-access bypass of the limiter.
  // Matching through the identical PathPatternRequestMatcher machinery keeps this filter and
  // SecurityConfig's authorization rule looking at the same normalized path.
  private static final RequestMatcher VALIDATE_MATCHER =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, VALIDATE_PATH);

  /** Same matcher machinery, same reason, for the conversion endpoint. */
  private static final RequestMatcher CONVERT_MATCHER =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, CONVERT_PATH);

  // Filters run outside Spring MVC's dispatch, so ApiExceptionHandler's @RestControllerAdvice never
  // sees a rejection this filter makes — the problem body has to be written here, by hand. It goes
  // through Problems so it is provably the same vocabulary the controllers speak, rather than a
  // second copy of the type-URI convention.
  private static final String PROBLEM_SLUG = "rate-limited";

  // Single-instance memory bound (see class Javadoc): 10k distinct callers tracked at once is
  // generous for one instance and cheap (a bucket is a handful of longs).
  private static final int MAX_TRACKED_CLIENTS = 10_000;
  private static final int EVICTION_TARGET = MAX_TRACKED_CLIENTS * 3 / 4;

  private final Limit validateLimit;
  private final Limit convertLimit;
  private final TimeMeter timeMeter;
  private final ConcurrentHashMap<String, ClientBucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(
      long validateCapacity,
      long validateRefillPerMinute,
      long convertCapacity,
      long convertRefillPerMinute) {
    this(
        validateCapacity,
        validateRefillPerMinute,
        convertCapacity,
        convertRefillPerMinute,
        TimeMeter.SYSTEM_MILLISECONDS);
  }

  /** Package-private: lets tests substitute a fake clock for deterministic refill assertions. */
  RateLimitFilter(
      long validateCapacity,
      long validateRefillPerMinute,
      long convertCapacity,
      long convertRefillPerMinute,
      TimeMeter timeMeter) {
    this.validateLimit =
        new Limit("validate", VALIDATE_PATH, validateCapacity, validateRefillPerMinute, true);
    this.convertLimit =
        new Limit("convert", CONVERT_PATH, convertCapacity, convertRefillPerMinute, false);
    this.timeMeter = timeMeter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !VALIDATE_MATCHER.matches(request) && !CONVERT_MATCHER.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Limit limit = VALIDATE_MATCHER.matches(request) ? validateLimit : convertLimit;
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean authenticated = isAuthenticated(authentication);

    if (limit.exemptsAuthenticated() && authenticated) {
      filterChain.doFilter(request, response);
      return;
    }

    String client = clientKey(request, authentication, authenticated);
    ConsumptionProbe probe =
        bucketFor(limit.name() + '|' + client, limit).tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      filterChain.doFilter(request, response);
      return;
    }
    // A security-relevant rejection, so it does not happen in silence. The bucket key is already
    // the only thing this filter knows about the caller.
    log.warn(
        "Rate-limited {} {} for {} (capacity {}/min)",
        authenticated ? "authenticated" : "anonymous",
        limit.path(),
        client,
        limit.refillPerMinute());
    writeRateLimited(response, ceilSeconds(probe.getNanosToWaitForRefill()), limit);
  }

  private static boolean isAuthenticated(Authentication authentication) {
    return authentication instanceof JwtAuthenticationToken
        || authentication instanceof ApiKeyAuthenticationToken;
  }

  /**
   * What a caller is bucketed by.
   *
   * <p>An authenticated caller is keyed by their <em>credential</em> — {@link
   * Authentication#getName()}, which is the tenant id for an API key and the token subject for a
   * JWT login — rather than by IP. That is the right unit for {@code /convert}, where the limit
   * exists to stop one tenant monopolising the CPU: keying by IP would instead punish every tenant
   * behind a shared egress address, and would let one tenant multiply their own allowance by
   * calling from several. Neither value needs a database lookup, which matters in a filter.
   *
   * <p>An anonymous caller has no credential, so the client address is all there is.
   */
  private static String clientKey(
      HttpServletRequest request, Authentication authentication, boolean authenticated) {
    return authenticated ? authentication.getName() : request.getRemoteAddr();
  }

  Bucket bucketFor(String key, Limit limit) {
    ClientBucket tracked =
        buckets.compute(
            key,
            (ignored, existing) -> {
              ClientBucket entry = existing != null ? existing : new ClientBucket(newBucket(limit));
              entry.lastAccessNanos.set(System.nanoTime());
              return entry;
            });
    if (buckets.size() > MAX_TRACKED_CLIENTS) {
      evictStale();
    }
    return tracked.bucket;
  }

  /**
   * Best-effort sweep, not perfectly atomic under concurrent writers — a soft memory bound, not a
   * hard correctness guarantee. Runs inline on whichever request happens to push the map over the
   * cap; scheduled sweeping would be the alternative, but this stays boring and needs no extra
   * thread.
   */
  private void evictStale() {
    buckets.entrySet().stream()
        .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessNanos.get()))
        .limit(Math.max(0, buckets.size() - EVICTION_TARGET))
        .map(Map.Entry::getKey)
        .forEach(buckets::remove);
  }

  private Bucket newBucket(Limit limit) {
    return Bucket.builder()
        .addLimit(
            bandwidth ->
                bandwidth
                    .capacity(limit.capacity())
                    .refillGreedy(limit.refillPerMinute(), Duration.ofMinutes(1)))
        .withCustomTimePrecision(timeMeter)
        .build();
  }

  private void writeRateLimited(HttpServletResponse response, long retryAfterSeconds, Limit limit)
      throws IOException {
    // Set before Problems.write, which commits the body. jakarta.servlet's HttpServletResponse
    // carries no SC_TOO_MANY_REQUESTS constant (429 predates RFC 6585 in the Servlet spec's own
    // list), so the status comes from Spring's HttpStatus enum instead — the same source
    // ApiExceptionHandler resolves its problem statuses from.
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    Problems.write(
        response,
        HttpStatus.TOO_MANY_REQUESTS,
        PROBLEM_SLUG,
        "Rate limit exceeded",
        "Too many requests to "
            + limit.path()
            + " from this client. Retry after the interval named in the Retry-After header.");
  }

  /**
   * One rate-limited route: its bucket size, and who it applies to.
   *
   * @param name the bucket-key prefix, so a caller's allowance on one route is never spent by their
   *     calls to the other
   * @param exemptsAuthenticated whether presenting a credential skips the limit entirely. True for
   *     the public validator, where the limit exists to keep anonymous abuse off a free endpoint
   *     and a known tenant is not the threat. <strong>False for {@code /convert}</strong>: that
   *     endpoint requires authentication, so exempting authenticated callers would make the limit
   *     apply to precisely nobody — and a conversion is the most expensive thing this platform
   *     does, being a read, two mappings, a write and a full Peppol XSLT run (M4 hostile review,
   *     finding F9).
   */
  record Limit(
      String name,
      String path,
      long capacity,
      long refillPerMinute,
      boolean exemptsAuthenticated) {}

  /** Ceiling division: the smallest whole-second count that covers {@code nanos} of wait. */
  private static long ceilSeconds(long nanos) {
    return (nanos + 999_999_999L) / 1_000_000_000L;
  }

  /** One bucket plus the nanotime it was last touched, for {@link #evictStale}. */
  private static final class ClientBucket {
    private final Bucket bucket;
    private final AtomicLong lastAccessNanos = new AtomicLong();

    private ClientBucket(Bucket bucket) {
      this.bucket = bucket;
    }
  }

  /**
   * Test-only: lets {@code RateLimitFilterTest} assert the eviction bound without leaking the map.
   */
  int trackedClientCount() {
    return buckets.size();
  }
}
