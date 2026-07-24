package com.stoicera.einvoice.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A persisted invoice. Backed by the {@code invoice} table.
 *
 * <p>The canonical invoice JSON is the single source of truth (stored in the {@code canonical}
 * JSONB column); the scalar columns are extracted projections used for listing, filtering and the
 * {@code (tenant_id, invoice_number)} uniqueness guarantee. ebInterface / UBL XML is always
 * regenerated from the canonical form and never stored.
 *
 * <p>{@code tenantId} is a plain foreign-key value rather than a mapped association: rows are
 * always accessed tenant-scoped, so the repository finders take the id directly and there is no
 * reason to hydrate a {@code TenantEntity}. The {@code currency} column is fixed-length {@code
 * char(3)} (ISO 4217), so it is bound as SQL {@code CHAR} to satisfy Hibernate's schema validation.
 */
@Entity
@Table(name = "invoice")
public class InvoiceEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "invoice_number", nullable = false, length = 255)
  private String invoiceNumber;

  @Column(name = "type_code", nullable = false, length = 3)
  private String typeCode;

  @Column(name = "issue_date", nullable = false)
  private LocalDate issueDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "payable_amount", nullable = false, precision = 17, scale = 2)
  private BigDecimal payableAmount;

  @Column(name = "seller_name", nullable = false, length = 512)
  private String sellerName;

  @Column(name = "buyer_name", nullable = false, length = 512)
  private String buyerName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "canonical", nullable = false)
  private String canonical;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** JPA-only. */
  protected InvoiceEntity() {}

  public InvoiceEntity(
      UUID tenantId,
      String invoiceNumber,
      String typeCode,
      LocalDate issueDate,
      String currency,
      BigDecimal payableAmount,
      String sellerName,
      String buyerName,
      String canonical) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.invoiceNumber = invoiceNumber;
    this.typeCode = typeCode;
    this.issueDate = issueDate;
    this.currency = currency;
    this.payableAmount = payableAmount;
    this.sellerName = sellerName;
    this.buyerName = buyerName;
    this.canonical = canonical;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public String getTypeCode() {
    return typeCode;
  }

  public LocalDate getIssueDate() {
    return issueDate;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getPayableAmount() {
    return payableAmount;
  }

  public String getSellerName() {
    return sellerName;
  }

  public String getBuyerName() {
    return buyerName;
  }

  public String getCanonical() {
    return canonical;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof InvoiceEntity other && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
