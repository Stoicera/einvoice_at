package com.stoicera.einvoice.app.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AuditEventEntity}.
 *
 * <p>Audit events are append-only: callers use {@code save} to record an action and never update or
 * delete rows. No custom finders are declared here; the standard {@code JpaRepository} surface is
 * sufficient for the insert-only usage this milestone needs.
 */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {}
