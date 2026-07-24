package com.stoicera.einvoice.app.persistence;

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
}
