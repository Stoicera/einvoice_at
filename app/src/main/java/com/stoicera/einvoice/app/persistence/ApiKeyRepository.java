package com.stoicera.einvoice.app.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link ApiKeyEntity}. */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

  /**
   * Resolves an active (non-revoked) API key by the SHA-256 hash of the presented secret. Revoked
   * keys are excluded in the query so authentication never has to re-check the flag.
   */
  Optional<ApiKeyEntity> findByKeyHashAndRevokedAtIsNull(String keyHash);

  /**
   * Lists a tenant's keys (active and revoked), newest first, for the management endpoint. Scoped
   * by tenant so the boundary check lives in the query, not in caller code.
   */
  List<ApiKeyEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  /**
   * Resolves one of a tenant's keys by id for tenant-scoped operations (e.g. revocation). Returns
   * empty when the id belongs to another tenant, so cross-tenant access reads as "not found".
   */
  Optional<ApiKeyEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
