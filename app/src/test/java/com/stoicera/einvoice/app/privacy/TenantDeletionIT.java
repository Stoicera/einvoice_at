package com.stoicera.einvoice.app.privacy;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import com.stoicera.einvoice.app.persistence.InvoiceRepository;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.security.ApiKeys;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * GDPR Art. 17 — the right to erasure, realized.
 *
 * <p>This closes the gap {@code docs/privacy.md} §4 named as unimplemented from M3 through M5:
 * "Voll- ständige Löschung des Mandanten: noch nicht implementiert". Until it existed, that
 * document correctly said the platform was not ready for real customer data.
 *
 * <p>What "vollständig" has to mean, and what these tests therefore assert row by row: after the
 * call, <strong>no row anywhere</strong> references the tenant — not the invoices, not the reports,
 * not the API keys, not the audit events, and not the tenant row itself with its Keycloak subject
 * and display name. A deletion that left the audit trail behind would leave the {@code
 * external_subject} of a person who asked to be forgotten in the database, which is exactly the
 * thing being asked to stop.
 *
 * <p>The counterpart assertion matters as much: another tenant's data is untouched. A delete that
 * over-reached would be a far worse bug than one that under-reached.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantDeletionIT extends AbstractPostgresIT {

  private static final String ACCOUNT = "/app/konto";
  private static final String CONFIRMATION = "LÖSCHEN";

  @Autowired private MockMvc mvc;
  @Autowired private TenantRepository tenants;
  @Autowired private InvoiceRepository invoices;
  @Autowired private ReportRepository reports;
  @Autowired private ApiKeyRepository apiKeys;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private InvoiceService invoiceService;
  @Autowired private ReportService reportService;

  // ------------------------------------------------------------------ the page

  @Test
  void theAccountPageShowsWhatIsStoredAndOffersTheDangerZone() throws Exception {
    String sub = sub("page");
    TenantEntity tenant = tenant(sub);
    seed(tenant, "RE-DEL-PAGE");

    mvc.perform(get(ACCOUNT).with(login(sub)))
        .andExpect(status().isOk())
        // Transparency first (Art. 15): the page says what is held before offering to delete it.
        .andExpect(content().string(containsString("Gespeicherte Daten")))
        .andExpect(content().string(containsString("Konto und alle Daten löschen")))
        .andExpect(content().string(containsString(CONFIRMATION)));
  }

  // -------------------------------------------------------------- the deletion

  @Test
  void deletingTheAccountRemovesEveryRowBelongingToTheTenant() throws Exception {
    String sub = sub("delete-all");
    TenantEntity tenant = tenant(sub);
    UUID tenantId = tenant.getId();
    seed(tenant, "RE-DEL-ALL");
    apiKey(tenant);

    // Everything is there before.
    Assertions.assertThat(invoices.count()).isPositive();
    long invoicesBefore = countInvoices(tenantId);
    Assertions.assertThat(invoicesBefore).isPositive();

    mvc.perform(
            post(ACCOUNT + "/loeschen")
                .param("confirmation", CONFIRMATION)
                .with(login(sub))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());

    // And nothing is there after — every table, including the audit trail and the tenant row.
    Assertions.assertThat(countInvoices(tenantId)).isZero();
    Assertions.assertThat(reports.findByTenantId(tenantId, page())).isEmpty();
    Assertions.assertThat(apiKeys.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
    Assertions.assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).isEmpty();
    Assertions.assertThat(tenants.findById(tenantId)).isEmpty();
    Assertions.assertThat(tenants.findByExternalSubject(sub)).isEmpty();
  }

  @Test
  void deletingOneAccountLeavesEveryOtherTenantUntouched() throws Exception {
    String mineSub = sub("delete-mine");
    String theirsSub = sub("delete-theirs");
    TenantEntity mine = tenant(mineSub);
    TenantEntity theirs = tenant(theirsSub);
    seed(mine, "RE-DEL-MINE");
    seed(theirs, "RE-DEL-THEIRS");
    apiKey(theirs);

    mvc.perform(
            post(ACCOUNT + "/loeschen")
                .param("confirmation", CONFIRMATION)
                .with(login(mineSub))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());

    Assertions.assertThat(countInvoices(theirs.getId())).isPositive();
    Assertions.assertThat(reports.findByTenantId(theirs.getId(), page())).isNotEmpty();
    Assertions.assertThat(apiKeys.findByTenantIdOrderByCreatedAtDesc(theirs.getId())).isNotEmpty();
    Assertions.assertThat(tenants.findById(theirs.getId())).isPresent();
  }

  @Test
  void aWrongConfirmationWordDeletesNothing() throws Exception {
    String sub = sub("wrong-word");
    TenantEntity tenant = tenant(sub);
    seed(tenant, "RE-DEL-WRONG");

    mvc.perform(
            post(ACCOUNT + "/loeschen")
                .param("confirmation", "loeschen bitte")
                .with(login(sub))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(CONFIRMATION)));

    // The whole point of a typed confirmation is that a misclick cannot be irreversible.
    Assertions.assertThat(countInvoices(tenant.getId())).isPositive();
    Assertions.assertThat(tenants.findById(tenant.getId())).isPresent();
  }

  @Test
  void aMissingConfirmationDeletesNothing() throws Exception {
    String sub = sub("no-word");
    TenantEntity tenant = tenant(sub);
    seed(tenant, "RE-DEL-NOWORD");

    mvc.perform(post(ACCOUNT + "/loeschen").with(login(sub)).with(csrf()))
        .andExpect(status().isOk());

    Assertions.assertThat(tenants.findById(tenant.getId())).isPresent();
  }

  @Test
  void deletingWithoutTheCsrfTokenIsRefused() throws Exception {
    String sub = sub("nocsrf");
    TenantEntity tenant = tenant(sub);
    seed(tenant, "RE-DEL-NOCSRF");

    mvc.perform(post(ACCOUNT + "/loeschen").param("confirmation", CONFIRMATION).with(login(sub)))
        .andExpect(status().isForbidden());

    Assertions.assertThat(tenants.findById(tenant.getId())).isPresent();
  }

  @Test
  void theDeletionIsIdempotentEnoughToSurviveADoubleSubmit() throws Exception {
    // The tenant row is gone after the first call, so the second must not 500. CurrentTenant
    // re-provisions a tenant for a still-valid browser session, which is correct — it is a new,
    // empty tenant with the same subject, not a resurrection of the deleted data.
    String sub = sub("twice");
    TenantEntity tenant = tenant(sub);
    seed(tenant, "RE-DEL-TWICE");

    mvc.perform(
            post(ACCOUNT + "/loeschen")
                .param("confirmation", CONFIRMATION)
                .with(login(sub))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    mvc.perform(
            post(ACCOUNT + "/loeschen")
                .param("confirmation", CONFIRMATION)
                .with(login(sub))
                .with(csrf()))
        .andExpect(status().is3xxRedirection());

    Assertions.assertThat(countInvoices(tenant.getId())).isZero();
  }

  // ----------------------------------------------------------------- helpers

  private long countInvoices(UUID tenantId) {
    return invoices.findByTenantId(tenantId, page()).getTotalElements();
  }

  private static org.springframework.data.domain.PageRequest page() {
    return org.springframework.data.domain.PageRequest.of(0, 100);
  }

  private static String sub(String test) {
    return "tenant-deletion-" + test;
  }

  private static RequestPostProcessor login(String sub) {
    return oauth2Login().attributes(attributes -> attributes.put("sub", sub));
  }

  private TenantEntity tenant(String sub) {
    return tenants
        .findByExternalSubject(sub)
        .orElseGet(() -> tenants.save(new TenantEntity(sub, "Lösch-Mandant " + sub)));
  }

  /** An invoice (which also writes a report and an audit event) plus an ad-hoc report. */
  private void seed(TenantEntity tenant, String number) throws Exception {
    invoiceService.create(tenant.getId(), invoiceJson(number).getBytes(StandardCharsets.UTF_8));
    byte[] fixture;
    try (var in =
        TenantDeletionIT.class.getResourceAsStream(
            "/fixtures/at-b2g-01-missing-order-reference.xml")) {
      fixture = in.readAllBytes();
    }
    reportService.validate(fixture, Optional.of(tenant.getId()));
  }

  private void apiKey(TenantEntity tenant) {
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "Lösch-Test", generated.keyHash(), generated.prefix()));
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
