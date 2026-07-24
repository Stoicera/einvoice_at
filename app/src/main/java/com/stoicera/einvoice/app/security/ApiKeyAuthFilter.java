package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
          .ifPresent(
              key -> {
                ApiKeyAuthenticationToken token =
                    ApiKeyAuthenticationToken.authenticated(key.getTenantId(), key.getId());
                token.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(token);
              });
    }
    filterChain.doFilter(request, response);
  }
}
