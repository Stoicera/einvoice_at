package com.stoicera.einvoice.app.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Spring Data repository for {@link ReportEntity}, with tenant-scoped access. */
public interface ReportRepository extends JpaRepository<ReportEntity, UUID> {

  /** Lists a tenant's reports, paginated (backs {@code GET /reports}). */
  Page<ReportEntity> findByTenantId(UUID tenantId, Pageable pageable);

  /** Loads a single report, but only if it belongs to the given tenant. */
  Optional<ReportEntity> findByIdAndTenantId(UUID id, UUID tenantId);

  /**
   * Loads the reports linked to the given invoices in one query — the invoice listing joins each
   * row to its report's {@code valid} flag without an N+1 per-row lookup. Callers pass an already
   * tenant-scoped set of invoice ids.
   */
  List<ReportEntity> findByInvoiceIdIn(Collection<UUID> invoiceIds);

  /**
   * Lists one invoice's reports, newest first — the dashboard's invoice detail shows them as a
   * history. Scoped by tenant <em>and</em> invoice in the query rather than filtering a tenant page
   * in memory, so a tenant with many reports does not pay for a page fetch to display two rows.
   */
  List<ReportEntity> findByTenantIdAndInvoiceIdOrderByCreatedAtDesc(UUID tenantId, UUID invoiceId);

  /**
   * Erases one tenant's reports (GDPR Art. 17). Must run <em>before</em> the invoice delete: {@code
   * report.invoice_id} references {@code invoice(id)}, so deleting invoices first violates the
   * constraint.
   */
  @Modifying
  @Transactional
  @Query("delete from ReportEntity r where r.tenantId = :tenantId")
  long deleteByTenantId(@Param("tenantId") UUID tenantId);

  /**
   * Deletes reports created before {@code cutoff}, across all tenants — the retention purge.
   *
   * <p>Reports only. Invoices are deliberately never expired by the retention job: an Austrian
   * business must keep its invoices for seven years (§ 132 BAO), and a platform that quietly
   * deleted them would be actively harmful. See {@code RetentionService}.
   */
  @Modifying
  @Transactional
  @Query("delete from ReportEntity r where r.createdAt < :cutoff")
  long deleteByCreatedAtBefore(@Param("cutoff") java.time.Instant cutoff);
}
