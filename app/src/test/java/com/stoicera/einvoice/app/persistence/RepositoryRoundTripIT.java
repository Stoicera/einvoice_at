package com.stoicera.einvoice.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/**
 * Round-trips every persistence entity through the real database: save in one transaction, read
 * back in another, and assert the non-trivial column mappings survive — JSONB text, fixed-length
 * {@code char(64)} hashes and {@code char(3)} currency, {@code numeric(17,2)} money, and {@code
 * timestamptz} instants. The test is deliberately <em>not</em> {@code @Transactional}: each Spring
 * Data call runs and commits in its own transaction, so reads come from Postgres rather than the
 * first-level cache.
 *
 * <p>Each test seeds its own tenant with unique natural keys so the runs are independent of
 * uniqueness constraints and of any rows a sibling test left behind in the shared container.
 */
@SpringBootTest
class RepositoryRoundTripIT extends AbstractPostgresIT {

  private static final String SHA256_HEX = "0123456789abcdef".repeat(4); // exactly 64 chars

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private TenantRepository tenantRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private ReportRepository reportRepository;
  @Autowired private ApiKeyRepository apiKeyRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private TenantEntity newTenant() {
    return tenantRepository.save(
        new TenantEntity("kc-sub-" + UUID.randomUUID(), "Acme Handels GmbH"));
  }

  @Test
  void tenantRoundTripsAndResolvesByExternalSubject() {
    TenantEntity saved = newTenant();

    TenantEntity found = tenantRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getExternalSubject()).isEqualTo(saved.getExternalSubject());
    assertThat(found.getDisplayName()).isEqualTo("Acme Handels GmbH");
    assertThat(found.getCreatedAt()).isNotNull();

    assertThat(tenantRepository.findByExternalSubject(saved.getExternalSubject()))
        .get()
        .extracting(TenantEntity::getId)
        .isEqualTo(saved.getId());
  }

  @Test
  void invoiceRoundTripsPreservingJsonbCurrencyAndMoney() throws Exception {
    TenantEntity tenant = newTenant();
    String invoiceNumber = "INV-" + UUID.randomUUID();
    String canonical = "{\"invoiceNumber\":\"" + invoiceNumber + "\",\"currency\":\"EUR\"}";

    InvoiceEntity saved =
        invoiceRepository.save(
            new InvoiceEntity(
                tenant.getId(),
                invoiceNumber,
                "380",
                LocalDate.of(2026, 1, 15),
                "EUR",
                new BigDecimal("1234.56"),
                "Verkäufer GmbH",
                "Käufer AG",
                canonical));

    InvoiceEntity found =
        invoiceRepository.findByIdAndTenantId(saved.getId(), tenant.getId()).orElseThrow();
    assertThat(found.getInvoiceNumber()).isEqualTo(invoiceNumber);
    assertThat(found.getTypeCode()).isEqualTo("380");
    assertThat(found.getIssueDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(found.getCurrency()).isEqualTo("EUR"); // char(3), exact length, no padding
    assertThat(found.getPayableAmount()).isEqualByComparingTo("1234.56");
    assertThat(found.getSellerName()).isEqualTo("Verkäufer GmbH");
    assertThat(found.getBuyerName()).isEqualTo("Käufer AG");
    assertThat(json.readTree(found.getCanonical())).isEqualTo(json.readTree(canonical));

    // Scoping finders: wrong tenant sees nothing; the tenant listing sees the row.
    assertThat(invoiceRepository.findByIdAndTenantId(saved.getId(), UUID.randomUUID())).isEmpty();
    assertThat(invoiceRepository.findByTenantId(tenant.getId(), PageRequest.of(0, 10)))
        .extracting(InvoiceEntity::getId)
        .contains(saved.getId());
  }

  @Test
  void reportRoundTripsPreservingFindingsJsonb() throws Exception {
    TenantEntity tenant = newTenant();
    String findings = "[{\"code\":\"AT-B2G-01\",\"severity\":\"ERROR\"}]";

    ReportEntity saved =
        reportRepository.save(
            new ReportEntity(
                tenant.getId(), null, "ebInterface", "ebinterface-6.1", false, findings));

    ReportEntity found =
        reportRepository.findByIdAndTenantId(saved.getId(), tenant.getId()).orElseThrow();
    assertThat(found.getSourceFormat()).isEqualTo("ebInterface");
    assertThat(found.getProfile()).isEqualTo("ebinterface-6.1");
    assertThat(found.isValid()).isFalse();
    assertThat(found.getInvoiceId()).isNull();
    assertThat(json.readTree(found.getFindings())).isEqualTo(json.readTree(findings));

    assertThat(reportRepository.findByTenantId(tenant.getId(), PageRequest.of(0, 10)))
        .extracting(ReportEntity::getId)
        .contains(saved.getId());
  }

  @Test
  void apiKeyRoundTripsAndRevocationHidesItFromActiveLookup() {
    TenantEntity tenant = newTenant();

    ApiKeyEntity saved =
        apiKeyRepository.save(
            new ApiKeyEntity(tenant.getId(), "CI key", SHA256_HEX, "eiv_live_ab"));

    ApiKeyEntity found = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(SHA256_HEX).orElseThrow();
    assertThat(found.getId()).isEqualTo(saved.getId());
    assertThat(found.getKeyHash()).isEqualTo(SHA256_HEX); // char(64), exact length
    assertThat(found.getPrefix()).isEqualTo("eiv_live_ab");
    assertThat(found.isRevoked()).isFalse();

    found.revoke(Instant.now());
    apiKeyRepository.save(found);

    assertThat(apiKeyRepository.findByKeyHashAndRevokedAtIsNull(SHA256_HEX)).isEmpty();
    assertThat(apiKeyRepository.findById(saved.getId()).orElseThrow().isRevoked()).isTrue();
  }

  @Test
  void auditEventRoundTripsPreservingPayloadHash() {
    TenantEntity tenant = newTenant();

    AuditEventEntity saved =
        auditEventRepository.save(
            new AuditEventEntity(tenant.getId(), "INVOICE_CREATE", SHA256_HEX));

    AuditEventEntity found = auditEventRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getTenantId()).isEqualTo(tenant.getId());
    assertThat(found.getAction()).isEqualTo("INVOICE_CREATE");
    assertThat(found.getPayloadSha256()).isEqualTo(SHA256_HEX);
    assertThat(found.getOccurredAt()).isNotNull();
  }
}
