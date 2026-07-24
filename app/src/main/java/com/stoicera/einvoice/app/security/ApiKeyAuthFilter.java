package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request presenting an {@code X-Api-Key} header. The presented secret is hashed
 * (SHA-256) and looked up against the active (non-revoked) keys; on a match the request runs as the
 * key's tenant via an {@link ApiKeyAuthenticationToken}.
 *
 * <p>A missing, blank, unknown or revoked key leaves the request unauthenticated — the filter never
 * rejects. Authorization then decides: a protected route yields 401 through the resource server's
 * entry point, while a public route (e.g. {@code POST /api/v1/validate}) proceeds anonymously. This
 * runs before the bearer-token filter, so a request carries either an API key or an OAuth2 token.
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
