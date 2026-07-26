package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
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
 *       {@code sub});
 *   <li>an {@link OAuth2AuthenticationToken} — a browser login through the M5 web UI — is mapped
 *       the same way, by {@code sub}.
 * </ul>
 *
 * <p><strong>A browser login and an API token for the same person are the same tenant.</strong>
 * Both resolve through {@code sub}, so signing in to the dashboard and fetching a token for the API
 * land on one tenant row. Keying them differently would have been easy and would have produced a
 * quiet data-partitioning bug: invoices created through the API invisible in the dashboard, and two
 * tenants accumulating for one person.
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
      return provisioning.provision(subject, displayNameFor(preferredUsername, subject));
    }
    if (authentication instanceof OAuth2AuthenticationToken oauth2) {
      // The browser login. The principal is an OidcUser (openid scope), whose "sub" attribute is
      // the
      // same stable Keycloak subject a bearer token carries — read as an attribute rather than by
      // casting to OidcUser, so a non-OIDC provider added later still resolves rather than
      // throwing.
      OAuth2User user = oauth2.getPrincipal();
      String subject = attribute(user, "sub");
      if (!StringUtils.hasText(subject)) {
        throw new IllegalStateException(
            "OAuth2 login carries no 'sub' attribute; cannot resolve a tenant");
      }
      return provisioning.provision(
          subject, displayNameFor(attribute(user, "preferred_username"), subject));
    }
    throw new IllegalStateException(
        "Unsupported authentication type: " + authentication.getClass().getName());
  }

  private static String attribute(OAuth2User user, String name) {
    Object value = user.getAttributes().get(name);
    return value instanceof String text ? text : null;
  }

  /** The display name, falling back to the opaque subject when the IdP supplies no username. */
  private static String displayNameFor(String preferredUsername, String subject) {
    return StringUtils.hasText(preferredUsername) ? preferredUsername : subject;
  }

  /**
   * Resolves the tenant for {@code authentication} if it identifies an authenticated principal (a
   * JWT login, a browser login or an API key); returns {@link Optional#empty()} for an anonymous
   * caller — either no authentication at all, or Spring Security's {@code
   * AnonymousAuthenticationToken} (the principal a {@code permitAll} route still carries).
   *
   * <p>Used only by endpoints reachable by both anonymous and authenticated callers ({@code POST
   * /api/v1/validate}); every tenant-scoped endpoint keeps using {@link #require}.
   */
  public Optional<TenantEntity> resolveIfAuthenticated(Authentication authentication) {
    if (authentication instanceof ApiKeyAuthenticationToken
        || authentication instanceof JwtAuthenticationToken
        || authentication instanceof OAuth2AuthenticationToken) {
      return Optional.of(require(authentication));
    }
    return Optional.empty();
  }
}
