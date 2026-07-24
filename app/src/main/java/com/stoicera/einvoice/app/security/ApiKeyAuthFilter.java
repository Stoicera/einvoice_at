package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.problem.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request presenting an {@code X-Api-Key} header. The presented secret is hashed
 * (SHA-256) and looked up against the active (non-revoked) keys; on a match the request runs as the
 * key's tenant via an {@link ApiKeyAuthenticationToken}.
 *
 * <p>A missing, blank, unknown or revoked key leaves the request unauthenticated — the filter does
 * not reject it. Authorization then decides: a protected route yields 401 through the resource
 * server's entry point, while a public route (e.g. {@code POST /api/v1/validate}) proceeds
 * anonymously. An unresolvable key is logged as a security event before the request moves on.
 *
 * <p>There is exactly one case this filter rejects outright: a request presenting <em>both</em> an
 * API key and a bearer token. This filter runs before the bearer-token filter, whose authentication
 * would simply overwrite this one's — so "a request carries either an API key or an OAuth2 token"
 * used to be a hope about filter ordering rather than a property of the system, and a request with
 * tenant A's key and tenant B's token silently executed as tenant B. It is now enforced here, as
 * RFC 6750 §3.1 prescribes: 400 {@code invalid_request}.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Api-Key";

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

  /**
   * Characters of a presented key echoed into the rejection log — the same non-secret display
   * prefix {@link ApiKeys} persists for exactly this purpose ("which key is this?"). Enough to
   * correlate a failing client against the key listing, far too little to use.
   */
  private static final int LOGGED_PREFIX_LENGTH = 8;

  private final ApiKeyRepository apiKeys;

  public ApiKeyAuthFilter(ApiKeyRepository apiKeys) {
    this.apiKeys = apiKeys;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (presentsTwoCredentials(request)) {
      rejectAmbiguousCredentials(request, response);
      return;
    }
    String presented = request.getHeader(HEADER);
    if (StringUtils.hasText(presented)
        && SecurityContextHolder.getContext().getAuthentication() == null) {
      apiKeys
          .findByKeyHashAndRevokedAtIsNull(ApiKeys.sha256Hex(presented))
          .ifPresentOrElse(
              key -> {
                ApiKeyAuthenticationToken token =
                    ApiKeyAuthenticationToken.authenticated(key.getTenantId(), key.getId());
                token.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(token);
              },
              // A presented key that resolves to nothing is a security event worth seeing: an
              // unknown key, a revoked one still in use, or a credential-stuffing attempt. The
              // request is not rejected here (authorization decides), but it must not pass in
              // silence.
              () ->
                  log.warn(
                      "Rejected API key on {} {} from {} (prefix {}…): unknown or revoked",
                      request.getMethod(),
                      request.getRequestURI(),
                      request.getRemoteAddr(),
                      loggablePrefix(presented)));
    }
    filterChain.doFilter(request, response);
  }

  /**
   * True when the request carries both an {@code X-Api-Key} and a bearer {@code Authorization}
   * header.
   *
   * <p>Only a <em>bearer</em> Authorization competes: any other scheme is not a credential this API
   * accepts at all, so it must not turn an ordinary API-key request into an error. The scheme is
   * compared case-insensitively, as RFC 7235 requires.
   */
  private static boolean presentsTwoCredentials(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    return StringUtils.hasText(request.getHeader(HEADER))
        && authorization != null
        && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ");
  }

  /**
   * Refuses a request that presents two competing credentials, rather than letting filter ordering
   * pick one.
   *
   * <p>The bearer-token filter runs after this one and overwrites the security context, so before
   * this check a request carrying tenant A's key and tenant B's token silently executed as tenant
   * B. Nothing documented or tested that. RFC 6750 §3.1 already answers it: more than one
   * authentication method in a request is {@code invalid_request}, a 400.
   */
  private void rejectAmbiguousCredentials(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    log.warn(
        "Rejected request presenting both an API key and a bearer token on {} {} from {}",
        request.getMethod(),
        request.getRequestURI(),
        request.getRemoteAddr());
    Problems.write(
        response,
        HttpStatus.BAD_REQUEST,
        "multiple-credentials",
        "Multiple credentials presented",
        "Present either an X-Api-Key header or an Authorization: Bearer token, never both.");
  }

  /**
   * The presented value's leading, non-secret display prefix — never the full value. A value too
   * short to have a prefix is reported as such rather than echoed: it cannot be a real key (those
   * are {@code eiv_} plus ~43 characters), and "never log a presented credential in full" is a rule
   * worth keeping free of exceptions.
   */
  private static String loggablePrefix(String presented) {
    return presented.length() > LOGGED_PREFIX_LENGTH
        ? presented.substring(0, LOGGED_PREFIX_LENGTH)
        : "<too short to be a key>";
  }
}
