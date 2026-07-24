package com.stoicera.einvoice.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A tenant: the account boundary every other row is scoped to. Backed by the {@code tenant} table.
 *
 * <p>The {@code externalSubject} is the Keycloak {@code sub} claim, the stable external identity a
 * signed-in principal is resolved to. Identifiers are application-assigned at construction; the
 * database holds no sequences.
 */
@Entity
@Table(name = "tenant")
public class TenantEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "external_subject", nullable = false, unique = true, length = 255)
  private String externalSubject;

  @Column(name = "display_name", nullable = false, length = 255)
  private String displayName;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only. */
  protected TenantEntity() {}

  public TenantEntity(String externalSubject, String displayName) {
    this.id = UUID.randomUUID();
    this.externalSubject = externalSubject;
    this.displayName = displayName;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getExternalSubject() {
    return externalSubject;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof TenantEntity other && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
