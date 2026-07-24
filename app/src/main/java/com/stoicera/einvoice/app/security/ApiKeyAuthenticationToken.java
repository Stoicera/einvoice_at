package com.stoicera.einvoice.app.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The {@link org.springframework.security.core.Authentication} produced by a valid {@code
 * X-Api-Key} header. It carries the tenant the key belongs to and the key's own id, and grants
 * exactly {@code ROLE_API_KEY} — the marker authority that lets {@link SecurityConfig} keep machine
 * keys out of the API-key management endpoints (an API key must never mint or revoke API keys).
 *
 * <p>The presented secret is intentionally not retained: authentication has already succeeded, so
 * {@link #getCredentials()} returns {@code null} and the plaintext key never lives in the security
 * context.
 */
public final class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

  private final UUID tenantId;
  private final UUID apiKeyId;

  private ApiKeyAuthenticationToken(UUID tenantId, UUID apiKeyId) {
    super(List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
    this.tenantId = tenantId;
    this.apiKeyId = apiKeyId;
    setAuthenticated(true);
  }

  /** Creates an authenticated token for a resolved, active key. */
  public static ApiKeyAuthenticationToken authenticated(UUID tenantId, UUID apiKeyId) {
    return new ApiKeyAuthenticationToken(tenantId, apiKeyId);
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getApiKeyId() {
    return apiKeyId;
  }

  @Override
  public Object getCredentials() {
    return null; // the secret is never kept once the key has been resolved
  }

  @Override
  public Object getPrincipal() {
    return tenantId;
  }
}
