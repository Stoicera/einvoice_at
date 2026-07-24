package com.stoicera.einvoice.app.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link ReportEntity}, with tenant-scoped access. */
public interface ReportRepository extends JpaRepository<ReportEntity, UUID> {

  /** Lists a tenant's reports, paginated (backs {@code GET /reports}). */
  Page<ReportEntity> findByTenantId(UUID tenantId, Pageable pageable);

  /** Loads a single report, but only if it belongs to the given tenant. */
  Optional<ReportEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
