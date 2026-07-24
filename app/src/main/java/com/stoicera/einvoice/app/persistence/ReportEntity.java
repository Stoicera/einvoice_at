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
 * A persisted validation report. Backed by the {@code report} table.
 *
 * <p>Reports are only stored for authenticated calls; anonymous {@code POST /validate} leaves zero
 * rows (GDPR stance, SPEC section 8). {@code invoiceId} is nullable: a report may be produced for
 * an uploaded document that was never persisted as an invoice. The full findings list is kept as
 * JSONB; {@code valid} and the source format/profile are extracted for listing.
 */
@Entity
@Table(name = "report")
public class ReportEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "invoice_id")
  private UUID invoiceId;

  @Column(name = "source_format", nullable = false, length = 64)
  private String sourceFormat;

  @Column(name = "profile", nullable = false, length = 64)
  private String profile;

  @Column(name = "valid", nullable = false)
  private boolean valid;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "findings", nullable = false)
  private String findings;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only. */
  protected ReportEntity() {}

  public ReportEntity(
      UUID tenantId,
      UUID invoiceId,
      String sourceFormat,
      String profile,
      boolean valid,
      String findings) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.invoiceId = invoiceId;
    this.sourceFormat = sourceFormat;
    this.profile = profile;
    this.valid = valid;
    this.findings = findings;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getInvoiceId() {
    return invoiceId;
  }

  public String getSourceFormat() {
    return sourceFormat;
  }

  public String getProfile() {
    return profile;
  }

  public boolean isValid() {
    return valid;
  }

  public String getFindings() {
    return findings;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof ReportEntity other && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
