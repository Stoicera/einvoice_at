package com.stoicera.einvoice.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An append-only audit record. Backed by the {@code audit_event} table.
 *
 * <p>Audit rows are written for create/validate/convert actions and never updated or deleted. Only
 * a SHA-256 hash of the payload is stored (fixed-length {@code char(64)} hex, bound as SQL {@code
 * CHAR}), never the payload itself; the hash is nullable for actions that carry no payload.
 */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "action", nullable = false, length = 64)
  private String action;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "payload_sha256", length = 64)
  private String payloadSha256;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /** JPA-only. */
  protected AuditEventEntity() {}

  public AuditEventEntity(UUID tenantId, String action, String payloadSha256) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.action = action;
    this.payloadSha256 = payloadSha256;
    this.occurredAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getAction() {
    return action;
  }

  public String getPayloadSha256() {
    return payloadSha256;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof AuditEventEntity other && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
