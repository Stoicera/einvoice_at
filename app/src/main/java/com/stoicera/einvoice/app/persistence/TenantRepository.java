package com.stoicera.einvoice.app.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link TenantEntity}. */
public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

  /**
   * Resolves the tenant for a signed-in principal by its external identity (Keycloak {@code sub}
   * claim). Used to map an authenticated request to its account boundary.
   */
  Optional<TenantEntity> findByExternalSubject(String externalSubject);
}
