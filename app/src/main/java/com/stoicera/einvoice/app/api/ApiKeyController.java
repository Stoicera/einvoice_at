package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.audit.AuditAction;
import com.stoicera.einvoice.app.audit.AuditService;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.security.ApiKeys;
import com.stoicera.einvoice.app.security.CurrentTenant;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant API-key management: create, list and revoke. Mapped under {@code /api/v1/api-keys}, which
 * {@link com.stoicera.einvoice.app.security.SecurityConfig} restricts to OAuth2 (JWT) logins — an
 * API key cannot reach these endpoints, so it can neither mint nor revoke keys.
 *
 * <p>The tenant is resolved from the authenticated principal (provisioned on first sight for a
 * JWT). Creation returns the plaintext key once; listing never does.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

  private final ApiKeyRepository apiKeys;
  private final CurrentTenant currentTenant;
  private final AuditService audit;

  public ApiKeyController(
      ApiKeyRepository apiKeys, CurrentTenant currentTenant, AuditService audit) {
    this.apiKeys = apiKeys;
    this.currentTenant = currentTenant;
    this.audit = audit;
  }

  /** Mints a new key for the caller's tenant and returns its plaintext exactly once. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedApiKeyResponse create(
      @Valid @RequestBody CreateApiKeyRequest request, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    ApiKeyEntity saved =
        apiKeys.save(
            new ApiKeyEntity(
                tenant.getId(), request.name(), generated.keyHash(), generated.prefix()));
    audit.record(tenant.getId(), AuditAction.API_KEY_CREATED, generated.keyHash());
    return CreatedApiKeyResponse.of(saved, generated);
  }

  /** Lists the caller's tenant's keys (active and revoked), newest first, without secrets. */
  @GetMapping
  public List<ApiKeyResponse> list(Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return apiKeys.findByTenantIdOrderByCreatedAtDesc(tenant.getId()).stream()
        .map(ApiKeyResponse::of)
        .toList();
  }

  /**
   * Revokes one of the caller's tenant's keys (soft: {@code revoked_at} is stamped, row retained).
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    ApiKeyEntity key =
        apiKeys
            .findByIdAndTenantId(id, tenant.getId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
    key.revoke(Instant.now());
    apiKeys.save(key);
    audit.record(tenant.getId(), AuditAction.API_KEY_REVOKED, key.getKeyHash());
  }
}
