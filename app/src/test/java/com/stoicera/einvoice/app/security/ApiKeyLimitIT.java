package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The per-tenant cap on active API keys (M3 hostile review, F12: minting was unbounded).
 *
 * <p>Driven through {@link ApiKeyService} rather than over HTTP, so each run gets a private tenant.
 * The endpoint route is fixed to one Keycloak user and therefore one tenant, so filling that
 * tenant's quota would leave every other test in {@code ApiKeyEndpointIT} order-dependent. The
 * matching 409 {@code api-key-limit-reached} wire shape is pinned separately, and cheaply, by
 * {@code ApiExceptionHandlerTest}.
 *
 * <p>Configuration is read rather than hardcoded: the point is that the cap is enforced and that
 * revocation frees a slot, not that it happens to be 25 today.
 */
@SpringBootTest
class ApiKeyLimitIT extends AbstractPostgresIT {

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ApiKeyRepository apiKeys;
  @Autowired private TenantRepository tenants;

  @Value("${app.api-keys.max-active-per-tenant}")
  private int limit;

  @Test
  void aTenantCannotMintMoreThanTheConfiguredNumberOfActiveKeys() {
    UUID tenantId = freshTenant();

    for (int i = 0; i < limit; i++) {
      apiKeyService.create(tenantId, "key-" + i);
    }
    assertThat(apiKeys.countByTenantIdAndRevokedAtIsNull(tenantId)).isEqualTo(limit);

    assertThatThrownBy(() -> apiKeyService.create(tenantId, "one-too-many"))
        .isInstanceOf(TooManyApiKeysException.class)
        .hasMessageContaining(String.valueOf(limit));

    // The refused mint left nothing behind.
    assertThat(apiKeys.countByTenantIdAndRevokedAtIsNull(tenantId)).isEqualTo(limit);
  }

  @Test
  void revokingAKeyFreesASlotAndTheRevokedRowIsRetained() {
    UUID tenantId = freshTenant();
    UUID firstKeyId = apiKeyService.create(tenantId, "key-0").entity().getId();
    for (int i = 1; i < limit; i++) {
      apiKeyService.create(tenantId, "key-" + i);
    }
    assertThatThrownBy(() -> apiKeyService.create(tenantId, "blocked"))
        .isInstanceOf(TooManyApiKeysException.class);

    apiKeyService.revoke(tenantId, firstKeyId);

    // A slot opened up...
    assertThatCode(() -> apiKeyService.create(tenantId, "after-revoke")).doesNotThrowAnyException();
    // ...without the revoked key's row being deleted: the audit trail survives, it just stops
    // counting towards the cap.
    assertThat(apiKeys.findById(firstKeyId)).isPresent();
    assertThat(apiKeys.findById(firstKeyId).orElseThrow().isRevoked()).isTrue();
  }

  @Test
  void theCapIsPerTenantNotGlobal() {
    UUID busyTenant = freshTenant();
    for (int i = 0; i < limit; i++) {
      apiKeyService.create(busyTenant, "key-" + i);
    }

    // A different tenant is entirely unaffected by the first one exhausting its quota.
    assertThatCode(() -> apiKeyService.create(freshTenant(), "first-key"))
        .doesNotThrowAnyException();
  }

  private UUID freshTenant() {
    return tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Limit Test")).getId();
  }
}
