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
 * A tenant API key. Backed by the {@code api_key} table.
 *
 * <p>Only the SHA-256 hash of the key is stored (fixed-length {@code char(64)} hex, bound as SQL
 * {@code CHAR}); the plaintext key is shown once at creation and never persisted. The {@code
 * prefix} is a short, non-secret leading fragment kept for display and support ("which key is
 * this?"). Revocation is a soft state: {@code revokedAt} is stamped rather than the row deleted,
 * and the authentication lookup filters on it.
 */
@Entity
@Table(name = "api_key")
public class ApiKeyEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "key_hash", nullable = false, unique = true, length = 64)
  private String keyHash;

  @Column(name = "prefix", nullable = false, length = 12)
  private String prefix;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  /** JPA-only. */
  protected ApiKeyEntity() {}

  public ApiKeyEntity(UUID tenantId, String name, String keyHash, String prefix) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.name = name;
    this.keyHash = keyHash;
    this.prefix = prefix;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public String getKeyHash() {
    return keyHash;
  }

  public String getPrefix() {
    return prefix;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  /** Marks this key revoked at the given instant; a no-op if already revoked. */
  public void revoke(Instant when) {
    if (revokedAt == null) {
      this.revokedAt = when;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof ApiKeyEntity other && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
