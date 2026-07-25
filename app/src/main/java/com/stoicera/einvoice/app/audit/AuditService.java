package com.stoicera.einvoice.app.audit;

import com.stoicera.einvoice.app.persistence.AuditEventEntity;
import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only audit log for business actions (who — the tenant, what — the {@link
 * AuditAction}, when — stamped by the entity). Only a SHA-256 hash of any payload is recorded,
 * never the payload itself; {@code payloadSha256} is null for actions that carry no payload.
 *
 * <p>The one seam every audited action goes through: {@link #record} is called from the invoice
 * creation flow, the validation flow and API-key creation/revocation. Each caller invokes it inside
 * its own transaction, so the audit row commits or rolls back with the action it records.
 */
@Service
public class AuditService {

  private final AuditEventRepository auditEvents;

  public AuditService(AuditEventRepository auditEvents) {
    this.auditEvents = auditEvents;
  }

  /**
   * Records one audit event.
   *
   * @param tenantId the tenant the action belongs to
   * @param action the business action
   * @param payloadSha256 64-char hex SHA-256 of the payload, or {@code null} if the action has none
   */
  @Transactional
  public void record(UUID tenantId, AuditAction action, String payloadSha256) {
    auditEvents.save(new AuditEventEntity(tenantId, action.name(), payloadSha256));
  }
}
