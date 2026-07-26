package com.stoicera.einvoice.app.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * Counts a tenant's active (non-revoked) keys, backing the per-tenant minting cap. Counted in the
   * database rather than by loading the rows, so the cap costs one aggregate query.
   */
  long countByTenantIdAndRevokedAtIsNull(UUID tenantId);

  /**
   * Erases one tenant's keys, revoked ones included (GDPR Art. 17).
   *
   * <p>Note the deliberate asymmetry with {@code ApiKeyService.revoke}, which is a <em>soft</em>
   * revoke that keeps the row for the audit trail: that trade-off exists because the tenant is
   * still there to be accountable to. Erasing the tenant removes the reason to keep it.
   */
  @Modifying
  @Transactional
  @Query("delete from ApiKeyEntity k where k.tenantId = :tenantId")
  long deleteByTenantId(@Param("tenantId") UUID tenantId);
}
