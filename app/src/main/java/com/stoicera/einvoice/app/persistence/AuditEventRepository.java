package com.stoicera.einvoice.app.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data repository for {@link AuditEventEntity}.
 *
 * <p>Audit events are append-only <em>in normal operation</em>: callers use {@code save} to record
 * an action and never update a row. M5 adds the two deletes that privacy law requires and that
 * append- only cannot accommodate — erasing one tenant (GDPR Art. 17) and expiring old events
 * (retention). Both are bulk operations, and neither is reachable from a request path that a
 * tenant's own data flows through.
 */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

  /** One tenant's events, newest first — the account page's "what is stored about me". */
  List<AuditEventEntity> findByTenantIdOrderByOccurredAtDesc(UUID tenantId);

  /**
   * Erases one tenant's audit trail.
   *
   * <p>A bulk {@code @Modifying} delete rather than a derived {@code deleteByTenantId}: the derived
   * form loads every matching entity into the persistence context before deleting, which for a
   * long-lived tenant's audit trail is an unbounded read performed in order to delete. Returns the
   * row count so the caller can log what it erased.
   */
  @Modifying
  @Transactional
  @Query("delete from AuditEventEntity a where a.tenantId = :tenantId")
  long deleteByTenantId(@Param("tenantId") UUID tenantId);

  /** Deletes events that occurred before {@code cutoff} — the retention purge. */
  @Modifying
  @Transactional
  @Query("delete from AuditEventEntity a where a.occurredAt < :cutoff")
  long deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
