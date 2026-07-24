package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.audit.AuditService;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the deliberate atomicity guarantee documented on {@link ApiKeyService#create} and {@link
 * ApiKeyService#revoke}: the key write and the audit write share one transaction, so a failure
 * recording the audit event rolls the key write back too — no key is ever left persisted (or left
 * revoked) without a corresponding audit trail.
 *
 * <p>Targets {@link ApiKeyService} directly against the real database (no Keycloak needed, unlike
 * {@link ApiKeyEndpointIT}) with {@link AuditService} replaced by a {@link MockitoBean} stub that
 * always throws on {@code record(...)} — isolating the transactional boundary from anything else
 * that could fail.
 */
@SpringBootTest
class ApiKeyServiceTransactionIT extends AbstractPostgresIT {

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ApiKeyRepository apiKeys;
  @Autowired private TenantRepository tenants;

  @MockitoBean private AuditService audit;

  @Test
  void aFailedAuditWriteOnCreateRollsBackTheKeyInsert() {
    UUID tenantId =
        tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Tx Test")).getId();
    doThrow(new RuntimeException("audit backend unavailable"))
        .when(audit)
        .record(any(), any(), any());
    long before = apiKeys.count();

    assertThatThrownBy(() -> apiKeyService.create(tenantId, "boom"))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("audit backend unavailable");

    // The @Transactional boundary on ApiKeyService.create rolled the key insert back with the
    // failed audit write, not just the caller's in-memory view of it: re-read via the repository.
    assertThat(apiKeys.count()).isEqualTo(before);
  }

  @Test
  void aFailedAuditWriteOnRevokeRollsBackTheRevocation() {
    UUID tenantId =
        tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Tx Test")).getId();
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    ApiKeyEntity key =
        apiKeys.save(
            new ApiKeyEntity(tenantId, "pre-existing", generated.keyHash(), generated.prefix()));
    assertThat(key.isRevoked()).isFalse();

    doThrow(new RuntimeException("audit backend unavailable"))
        .when(audit)
        .record(any(), any(), any());

    assertThatThrownBy(() -> apiKeyService.revoke(tenantId, key.getId()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("audit backend unavailable");

    // The revocation stamp itself must have rolled back too, not just the audit event.
    assertThat(apiKeys.findById(key.getId()).orElseThrow().isRevoked()).isFalse();
  }
}
