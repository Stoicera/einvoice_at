package com.stoicera.einvoice.app.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Spring Data repository for {@link InvoiceEntity}, with tenant-scoped access. */
public interface InvoiceRepository extends JpaRepository<InvoiceEntity, UUID> {

  /** Lists a tenant's invoices, paginated (backs {@code GET /invoices}). */
  Page<InvoiceEntity> findByTenantId(UUID tenantId, Pageable pageable);

  /**
   * Loads a single invoice, but only if it belongs to the given tenant — the boundary check for
   * tenant-scoped reads is expressed in the query, not left to the caller.
   */
  Optional<InvoiceEntity> findByIdAndTenantId(UUID id, UUID tenantId);

  /**
   * Erases one tenant's invoices (GDPR Art. 17). Must run <em>after</em> {@code
   * ReportRepository.deleteByTenantId}, since {@code report.invoice_id} references these rows.
   *
   * <p>There is deliberately no "delete invoices older than" counterpart: the retention job never
   * expires an invoice (§ 132 BAO — seven years). Erasure on request is a different thing from
   * expiry, and only the former may touch this table.
   */
  @Modifying
  @Transactional
  @Query("delete from InvoiceEntity i where i.tenantId = :tenantId")
  long deleteByTenantId(@Param("tenantId") UUID tenantId);
}
