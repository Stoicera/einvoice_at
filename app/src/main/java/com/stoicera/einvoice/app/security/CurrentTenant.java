package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the {@link TenantEntity} behind the current request's {@link Authentication}. This is
 * the seam every controller uses to obtain its tenant boundary; it hides how the two authentication
 * kinds map to a tenant:
 *
 * <ul>
 *   <li>an {@link ApiKeyAuthenticationToken} already carries its tenant id (from the key row);
 *   <li>a {@link JwtAuthenticationToken} is mapped by its {@code sub} claim, provisioning the
 *       tenant on first sight ({@code preferred_username} becomes the display name, falling back to
 *       {@code sub}).
 * </ul>
 */
@Component
public class CurrentTenant {

  private final TenantProvisioningService provisioning;
  private final TenantRepository tenants;

  public CurrentTenant(TenantProvisioningService provisioning, TenantRepository tenants) {
    this.provisioning = provisioning;
    this.tenants = tenants;
  }

  /**
   * Returns the tenant for {@code authentication}.
   *
   * @throws AuthenticationCredentialsNotFoundException if there is no authenticated principal
   * @throws IllegalStateException if the authentication kind is unsupported, or an API key points
   *     at a tenant that no longer exists
   */
  public TenantEntity require(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new AuthenticationCredentialsNotFoundException(
          "No authenticated principal on the request");
    }
    if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
      return tenants
          .findById(apiKey.getTenantId())
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "API key references a tenant that no longer exists: "
                          + apiKey.getTenantId()));
    }
    if (authentication instanceof JwtAuthenticationToken jwt) {
      Jwt token = jwt.getToken();
      String subject = token.getSubject();
      String preferredUsername = token.getClaimAsString("preferred_username");
      String displayName = StringUtils.hasText(preferredUsername) ? preferredUsername : subject;
      return provisioning.provision(subject, displayName);
    }
    throw new IllegalStateException(
        "Unsupported authentication type: " + authentication.getClass().getName());
  }

  /**
   * Resolves the tenant for {@code authentication} if it identifies an authenticated principal (a
   * JWT login or an API key); returns {@link Optional#empty()} for an anonymous caller — either no
   * authentication at all, or Spring Security's {@code AnonymousAuthenticationToken} (the principal
   * a {@code permitAll} route still carries).
   *
   * <p>Used only by endpoints reachable by both anonymous and authenticated callers ({@code POST
   * /api/v1/validate}); every tenant-scoped endpoint keeps using {@link #require}.
   */
  public Optional<TenantEntity> resolveIfAuthenticated(Authentication authentication) {
    if (authentication instanceof ApiKeyAuthenticationToken
        || authentication instanceof JwtAuthenticationToken) {
      return Optional.of(require(authentication));
    }
    return Optional.empty();
  }
}
