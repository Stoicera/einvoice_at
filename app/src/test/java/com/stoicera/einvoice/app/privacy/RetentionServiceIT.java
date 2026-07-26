package com.stoicera.einvoice.app.privacy;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import com.stoicera.einvoice.app.persistence.InvoiceRepository;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import com.stoicera.einvoice.app.report.ReportService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The retention purge (GDPR Art. 5(1)(e), storage limitation) — the other half of the gap {@code
 * docs/privacy.md} §4 carried from M3.
 *
 * <p><strong>The load-bearing assertion is the negative one:</strong> {@link
 * #anInvoiceIsNeverExpiredHoweverOldItIs}. Expiring invoices alongside reports would be the
 * symmetrical implementation and a serious bug — § 132 BAO obliges an Austrian business to keep its
 * invoices for seven years, so a platform that swept them up after a year would destroy records the
 * user is required to hold. That is a property nobody would notice for a year, which is exactly the
 * kind that needs a test.
 *
 * <p>The service is constructed directly with a chosen window and a fixed clock rather than
 * reconfigured through properties. A retention window is a function of two things — {@code now} and
 * the row's timestamp — and injecting both is what lets these tests state a window in days and mean
 * it, without a second Spring context per window. Rows are backdated with SQL because the entities
 * stamp their own creation time, deliberately (a settable {@code createdAt} would be a production
 * hazard for a test's convenience).
 *
 * <p>{@code @AutoConfigureMockMvc} carries no assertions here; it matches {@code
 * TenantDeletionIT}'s configuration so both share one cached context instead of starting a second.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetentionServiceIT extends AbstractPostgresIT {

  @Autowired private TenantRepository tenants;
  @Autowired private InvoiceRepository invoices;
  @Autowired private ReportRepository reports;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private InvoiceService invoiceService;
  @Autowired private ReportService reportService;
  @Autowired private JdbcTemplate jdbc;

  // ------------------------------------------------------------------- expiring

  @Test
  void aReportPastItsWindowIsPurged() {
    TenantEntity tenant = tenant("purge-report");
    UUID reportId = storeReport(tenant);
    backdateReport(reportId, Duration.ofDays(400));

    RetentionService.Purged purged = service(365, 730).purge();

    Assertions.assertThat(purged.reports()).isPositive();
    Assertions.assertThat(reports.findById(reportId)).isEmpty();
  }

  @Test
  void aReportInsideItsWindowIsKept() {
    TenantEntity tenant = tenant("keep-report");
    UUID reportId = storeReport(tenant);
    backdateReport(reportId, Duration.ofDays(10));

    service(365, 730).purge();

    Assertions.assertThat(reports.findById(reportId)).isPresent();
  }

  @Test
  void anAuditEventPastItsWindowIsPurged() {
    TenantEntity tenant = tenant("purge-audit");
    createInvoice(tenant, "RE-RET-AUDIT"); // writes an INVOICE_CREATED event
    Assertions.assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenant.getId()))
        .isNotEmpty();
    backdateAuditEvents(tenant.getId(), Duration.ofDays(900));

    RetentionService.Purged purged = service(365, 730).purge();

    Assertions.assertThat(purged.auditEvents()).isPositive();
    Assertions.assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenant.getId()))
        .isEmpty();
  }

  @Test
  void anAuditEventInsideItsWindowIsKept() {
    TenantEntity tenant = tenant("keep-audit");
    createInvoice(tenant, "RE-RET-KEEP-AUDIT");
    backdateAuditEvents(tenant.getId(), Duration.ofDays(30));

    service(365, 730).purge();

    Assertions.assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenant.getId()))
        .isNotEmpty();
  }

  // --------------------------------------------------- the invoice never expires

  @Test
  void anInvoiceIsNeverExpiredHoweverOldItIs() {
    TenantEntity tenant = tenant("keep-invoice");
    UUID invoiceId = createInvoice(tenant, "RE-RET-INVOICE");
    // Ten years old — past every window this service knows about, and past the seven § 132 BAO asks
    // for. It still must not be touched: expiry is not this job's business, only erasure-on-request
    // deletes an invoice.
    backdateInvoice(invoiceId, Duration.ofDays(3650));
    backdateReportsOfTenant(tenant.getId(), Duration.ofDays(3650));

    service(365, 730).purge();

    Assertions.assertThat(invoices.findById(invoiceId)).isPresent();
    Assertions.assertThat(
            invoices.findByTenantId(tenant.getId(), PageRequest.of(0, 10)).getTotalElements())
        .isPositive();
  }

  // ------------------------------------------------------------- the off switch

  @Test
  void aWindowOfZeroKeepsEverythingForever() {
    TenantEntity tenant = tenant("keep-forever");
    UUID reportId = storeReport(tenant);
    createInvoice(tenant, "RE-RET-FOREVER");
    backdateReport(reportId, Duration.ofDays(5000));
    backdateAuditEvents(tenant.getId(), Duration.ofDays(5000));

    // Zero is the documented off switch — one mechanism, not a second `enabled` flag that could
    // disagree with the windows.
    RetentionService.Purged purged = service(0, 0).purge();

    Assertions.assertThat(purged.reports()).isZero();
    Assertions.assertThat(purged.auditEvents()).isZero();
    Assertions.assertThat(reports.findById(reportId)).isPresent();
    Assertions.assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenant.getId()))
        .isNotEmpty();
  }

  @Test
  void aNegativeWindowAlsoKeepsEverything() {
    TenantEntity tenant = tenant("keep-negative");
    UUID reportId = storeReport(tenant);
    backdateReport(reportId, Duration.ofDays(5000));

    Assertions.assertThat(service(-1, -1).purge().reports()).isZero();
    Assertions.assertThat(reports.findById(reportId)).isPresent();
  }

  @Test
  void aPurgeWithNothingToDoIsHarmless() {
    // The common case in production: it runs nightly and finds nothing. Must not fail, and must not
    // report having deleted anything.
    RetentionService.Purged purged = service(36500, 36500).purge();

    Assertions.assertThat(purged.reports()).isZero();
    Assertions.assertThat(purged.auditEvents()).isZero();
  }

  // ----------------------------------------------------------------- helpers

  /** A service with the given windows and a clock fixed at the current instant. */
  private RetentionService service(int reportDays, int auditDays) {
    return new RetentionService(
        reports, auditEvents, Clock.fixed(Instant.now(), ZoneOffset.UTC), reportDays, auditDays);
  }

  private void backdateReport(UUID reportId, Duration age) {
    int updated =
        jdbc.update(
            "update report set created_at = created_at - ?::interval where id = ?",
            age.toDays() + " days",
            reportId);
    Assertions.assertThat(updated).as("backdating report %s", reportId).isEqualTo(1);
  }

  private void backdateReportsOfTenant(UUID tenantId, Duration age) {
    jdbc.update(
        "update report set created_at = created_at - ?::interval where tenant_id = ?",
        age.toDays() + " days",
        tenantId);
  }

  private void backdateInvoice(UUID invoiceId, Duration age) {
    int updated =
        jdbc.update(
            "update invoice set created_at = created_at - ?::interval where id = ?",
            age.toDays() + " days",
            invoiceId);
    Assertions.assertThat(updated).as("backdating invoice %s", invoiceId).isEqualTo(1);
  }

  private void backdateAuditEvents(UUID tenantId, Duration age) {
    int updated =
        jdbc.update(
            "update audit_event set occurred_at = occurred_at - ?::interval where tenant_id = ?",
            age.toDays() + " days",
            tenantId);
    Assertions.assertThat(updated).as("backdating audit events of %s", tenantId).isPositive();
  }

  private TenantEntity tenant(String name) {
    String sub = "retention-" + name;
    return tenants
        .findByExternalSubject(sub)
        .orElseGet(() -> tenants.save(new TenantEntity(sub, "Aufbewahrungs-Mandant " + name)));
  }

  private UUID createInvoice(TenantEntity tenant, String number) {
    return invoiceService
        .create(tenant.getId(), invoiceJson(number).getBytes(StandardCharsets.UTF_8))
        .id();
  }

  private UUID storeReport(TenantEntity tenant) {
    byte[] fixture;
    try (var in =
        RetentionServiceIT.class.getResourceAsStream(
            "/fixtures/at-b2g-01-missing-order-reference.xml")) {
      fixture = in.readAllBytes();
    } catch (Exception e) {
      throw new IllegalStateException("fixture unreadable", e);
    }
    return reportService.validate(fixture, Optional.of(tenant.getId())).id();
  }

  private static String invoiceJson(String number) {
    return """
        {
          "invoiceNumber": "%s",
          "type": "INVOICE",
          "issueDate": "2026-07-24",
          "currency": "EUR",
          "orderReference": "BBG-2026-4711",
          "supplierNumber": "L-100234",
          "seller": { "name": "Stoicera Software GesbR", "vatId": "ATU12345678", "email": "office@stoicera-software.at",
            "address": { "street": "Hauptplatz 1", "city": "Linz", "postalCode": "4020", "countryCode": "AT" } },
          "buyer": { "name": "Bundesbeschaffung GmbH", "vatId": "ATU87654321",
            "address": { "street": "Lassallestraße 9b", "city": "Wien", "postalCode": "1020", "countryCode": "AT" } },
          "lines": [
            { "id": "1", "description": "Softwareentwicklung", "quantity": "80", "unitCode": "HUR", "unitPrice": "120.00", "vatCategory": "STANDARD", "vatPercent": "20" }
          ],
          "paymentMeans": { "iban": "AT611904300234573201", "bic": "BKAUATWW" },
          "paymentTerms": "Zahlbar innerhalb von 30 Tagen ohne Abzug"
        }
        """
        .formatted(number);
  }
}
