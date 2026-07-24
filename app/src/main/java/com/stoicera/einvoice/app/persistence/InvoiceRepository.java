package com.stoicera.einvoice.app.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link InvoiceEntity}, with tenant-scoped access. */
public interface InvoiceRepository extends JpaRepository<InvoiceEntity, UUID> {

  /** Lists a tenant's invoices, paginated (backs {@code GET /invoices}). */
  Page<InvoiceEntity> findByTenantId(UUID tenantId, Pageable pageable);

  /**
   * Loads a single invoice, but only if it belongs to the given tenant — the boundary check for
   * tenant-scoped reads is expressed in the query, not left to the caller.
   */
  Optional<InvoiceEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
