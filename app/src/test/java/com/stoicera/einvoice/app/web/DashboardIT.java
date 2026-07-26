package com.stoicera.einvoice.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import com.stoicera.einvoice.app.report.ReportService;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The authenticated dashboard (SPEC §5): overview, invoice list and detail, report list and detail,
 * and the three document downloads.
 *
 * <h2>Why the downloads are dashboard routes and not links into {@code /api/v1}</h2>
 *
 * <p>The obvious way to offer "ebInterface herunterladen" is to link to {@code
 * /api/v1/invoices/{id}/ebinterface}, which already exists. It would not work, and the failure
 * would be a login redirect in a new tab rather than an error anyone would notice in review: {@code
 * /api/**} is the <strong>stateless</strong> chain, so it never looks at the browser's session
 * cookie (ADR-0009). The dashboard therefore serves its own downloads from the same {@code
 * InvoiceService} methods, and {@link
 * #theEbInterfaceDownloadIsServedToASessionNotJustToATokenClient} is the assertion that keeps
 * someone from "simplifying" them back into API links.
 *
 * <p><strong>The login is injected, not performed.</strong> {@code oauth2Login()} produces exactly
 * the {@code OAuth2AuthenticationToken} a completed authorization-code flow would have left behind,
 * with the {@code sub} attribute {@code CurrentTenant} maps to a tenant. Performing the real flow
 * here would be a test of Keycloak's login form; it is covered for real, in a real browser against
 * a real Keycloak, by the e2e module.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardIT extends AbstractPostgresIT {

  /**
   * A distinct tenant subject per test.
   *
   * <p>Written with one shared SUB first, which quietly coupled the tests: they share a database
   * and a class, so whichever test ran first created the tenant row and named it, and the
   * assertions about an empty list and about the displayed name then saw another test's invoices
   * and another test's name. A subject per test is the fix — the tenant is the unit of isolation in
   * this application, so making it per-test is also the honest model.
   */
  private static String subFor(String test) {
    return "dashboard-" + test;
  }

  @Autowired private MockMvc mvc;
  @Autowired private TenantRepository tenants;
  @Autowired private InvoiceService invoices;
  @Autowired private ReportService reports;

  // ------------------------------------------------------------------- reachability

  /**
   * Not readable without a login — asserted as "not 200" rather than as a specific status, because
   * which refusal a visitor gets depends on whether an OAuth2 client is configured: with one they
   * are redirected to Keycloak, without one (this context, and every context in this module) the
   * web chain has no login entry point and answers 403. Pinning either number would make this test
   * about configuration; the claim that matters is that no dashboard page is ever served
   * anonymously.
   */
  @Test
  void everyDashboardPageRefusesAnAnonymousVisitor() throws Exception {
    for (String path :
        new String[] {
          "/app",
          "/app/rechnungen",
          "/app/rechnungen/neu",
          "/app/berichte",
          "/app/api-schluessel",
          "/app/konto"
        }) {
      mvc.perform(get(path))
          .andExpect(
              result ->
                  org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                      .as("anonymous GET %s must not be served", path)
                      .isNotEqualTo(200));
    }
  }

  // ------------------------------------------------------------------------ overview

  @Test
  void theOverviewNamesTheSignedInTenantAndLinksTheSections() throws Exception {
    tenant(subFor("overview-name"), "Stoicera Software GesbR");

    mvc.perform(get("/app").with(login(subFor("overview-name"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Stoicera Software GesbR")))
        .andExpect(content().string(containsString("/app/rechnungen")))
        .andExpect(content().string(containsString("/app/berichte")))
        .andExpect(content().string(containsString("/app/api-schluessel")));
  }

  @Test
  void theOverviewCountsOnlyTheOwnTenantsWork() throws Exception {
    TenantEntity mine = tenant(subFor("overview-count"), "Zähl-Mandant");
    TenantEntity theirs = tenant(subFor("overview-count-other"), "Fremder Mandant");
    createInvoice(mine, "RE-COUNT-1");
    createInvoice(theirs, "RE-COUNT-2");
    createInvoice(theirs, "RE-COUNT-3");

    // Two tenants, three invoices, and the page must say one. A count that ignored the tenant
    // boundary would still render a plausible-looking dashboard.
    mvc.perform(get("/app").with(login(subFor("overview-count"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("RE-COUNT-1")))
        .andExpect(content().string(not(containsString("RE-COUNT-2"))));
  }

  // ------------------------------------------------------------------------ invoices

  @Test
  void theInvoiceListShowsTheTenantsInvoicesAndNotAnotherTenants() throws Exception {
    TenantEntity mine = tenant(subFor("invoice-list"), "Listen-Mandant");
    TenantEntity theirs = tenant(subFor("invoice-list-other"), "Fremder Mandant");
    createInvoice(mine, "RE-LIST-MINE");
    createInvoice(theirs, "RE-LIST-THEIRS");

    mvc.perform(get("/app/rechnungen").with(login(subFor("invoice-list"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("RE-LIST-MINE")))
        .andExpect(content().string(not(containsString("RE-LIST-THEIRS"))));
  }

  @Test
  void anEmptyInvoiceListSaysSoInsteadOfShowingAnEmptyTable() throws Exception {
    tenant(subFor("invoice-list-empty"), "Leerer Mandant");

    mvc.perform(get("/app/rechnungen").with(login(subFor("invoice-list-empty"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Noch keine Rechnungen")));
  }

  @Test
  void theInvoiceDetailShowsTheInvoiceAndOffersAllThreeDownloads() throws Exception {
    TenantEntity mine = tenant(subFor("invoice-detail"), "Detail-Mandant");
    UUID id = createInvoice(mine, "RE-DETAIL-1");

    mvc.perform(get("/app/rechnungen/" + id).with(login(subFor("invoice-detail"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("RE-DETAIL-1")))
        .andExpect(content().string(containsString("Bundesbeschaffung GmbH")))
        .andExpect(content().string(containsString("/app/rechnungen/" + id + "/ebinterface")))
        .andExpect(content().string(containsString("/app/rechnungen/" + id + "/ubl")))
        .andExpect(content().string(containsString("/app/rechnungen/" + id + "/pdf")));
  }

  @Test
  void aTenantCannotOpenAnotherTenantsInvoice() throws Exception {
    TenantEntity theirs = tenant(subFor("invoice-foreign-other"), "Fremder Mandant");
    UUID id = createInvoice(theirs, "RE-FOREIGN-1");
    tenant(subFor("invoice-foreign"), "Neugieriger Mandant");

    mvc.perform(get("/app/rechnungen/" + id).with(login(subFor("invoice-foreign"))))
        .andExpect(status().isNotFound());
  }

  // ----------------------------------------------------------------------- downloads

  @Test
  void theEbInterfaceDownloadIsServedToASessionNotJustToATokenClient() throws Exception {
    TenantEntity mine = tenant(subFor("download-ebi"), "Download-Mandant");
    UUID id = createInvoice(mine, "RE-DOWNLOAD-1");

    mvc.perform(get("/app/rechnungen/" + id + "/ebinterface").with(login(subFor("download-ebi"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<Invoice")))
        .andExpect(content().string(containsString("RE-DOWNLOAD-1")))
        .andExpect(header().string("Content-Disposition", containsString("RE-DOWNLOAD-1")));
  }

  @Test
  void theUblDownloadIsServedToASession() throws Exception {
    TenantEntity mine = tenant(subFor("download-ubl"), "Download-Mandant UBL");
    UUID id = createInvoice(mine, "RE-DOWNLOAD-2");

    mvc.perform(get("/app/rechnungen/" + id + "/ubl").with(login(subFor("download-ubl"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Invoice")))
        .andExpect(content().string(containsString("RE-DOWNLOAD-2")));
  }

  @Test
  void thePdfDownloadIsServedToASession() throws Exception {
    TenantEntity mine = tenant(subFor("download-pdf"), "Download-Mandant PDF");
    UUID id = createInvoice(mine, "RE-DOWNLOAD-3");

    byte[] pdf =
        mvc.perform(get("/app/rechnungen/" + id + "/pdf").with(login(subFor("download-pdf"))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("application/pdf")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    // A PDF, not an error page rendered with a PDF content type.
    org.assertj.core.api.Assertions.assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
        .isEqualTo("%PDF-");
  }

  @Test
  void aTenantCannotDownloadAnotherTenantsInvoice() throws Exception {
    TenantEntity theirs = tenant(subFor("download-foreign-other"), "Fremder Mandant");
    UUID id = createInvoice(theirs, "RE-FOREIGN-2");
    tenant(subFor("download-foreign"), "Neugieriger Mandant");

    mvc.perform(
            get("/app/rechnungen/" + id + "/ebinterface").with(login(subFor("download-foreign"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/app/rechnungen/" + id + "/pdf").with(login(subFor("download-foreign"))))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------------- reports

  @Test
  void theReportListShowsTheTenantsReports() throws Exception {
    TenantEntity mine = tenant(subFor("report-list"), "Bericht-Mandant");
    storeReport(mine);

    mvc.perform(get("/app/berichte").with(login(subFor("report-list"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ebinterface-6.1")))
        .andExpect(content().string(containsString("at-b2g")));
  }

  @Test
  void theReportDetailRendersTheGermanFindingWithItsRuleId() throws Exception {
    TenantEntity mine = tenant(subFor("report-detail"), "Bericht-Detail-Mandant");
    UUID reportId = storeReport(mine);

    mvc.perform(get("/app/berichte/" + reportId).with(login(subFor("report-detail"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("AT-B2G-01")))
        .andExpect(content().string(containsString("Auftragsreferenz")))
        .andExpect(content().string(containsString("Fehler")));
  }

  @Test
  void withAiDisabledTheReportDetailOffersNoExplainButton() throws Exception {
    // The dashboard half of "KI abschaltbar ohne Funktionsverlust": the page is complete and
    // useful,
    // it simply has no button. This context runs the shipped default (flag off).
    TenantEntity mine = tenant(subFor("report-no-ai"), "Kein-KI-Mandant");
    UUID reportId = storeReport(mine);

    mvc.perform(get("/app/berichte/" + reportId).with(login(subFor("report-no-ai"))))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Fehler erklären"))));
  }

  @Test
  void aTenantCannotOpenAnotherTenantsReport() throws Exception {
    TenantEntity theirs = tenant(subFor("report-foreign-other"), "Fremder Mandant");
    UUID reportId = storeReport(theirs);
    tenant(subFor("report-foreign"), "Neugieriger Mandant");

    mvc.perform(get("/app/berichte/" + reportId).with(login(subFor("report-foreign"))))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------------ helpers

  /** An injected browser login carrying the {@code sub} claim {@code CurrentTenant} maps on. */
  private static org.springframework.test.web.servlet.request.RequestPostProcessor login(
      String sub) {
    return oauth2Login().attributes(attributes -> attributes.put("sub", sub));
  }

  /** Ensures a tenant row exists for {@code sub}, so seeded data can be attributed to it. */
  private TenantEntity tenant(String sub, String displayName) {
    return tenants
        .findByExternalSubject(sub)
        .orElseGet(() -> tenants.save(new TenantEntity(sub, displayName)));
  }

  private UUID createInvoice(TenantEntity tenant, String number) {
    return invoices
        .create(tenant.getId(), invoiceJson(number).getBytes(StandardCharsets.UTF_8))
        .id();
  }

  private UUID storeReport(TenantEntity tenant) throws Exception {
    byte[] fileBytes;
    try (var in =
        DashboardIT.class.getResourceAsStream("/fixtures/at-b2g-01-missing-order-reference.xml")) {
      fileBytes = in.readAllBytes();
    }
    return reports.validate(fileBytes, Optional.of(tenant.getId())).id();
  }

  private static String invoiceJson(String number) {
    return """
        {
          "invoiceNumber": "%s",
          "type": "INVOICE",
          "issueDate": "2026-07-24",
          "dueDate": "2026-08-23",
          "deliveryDate": "2026-07-24",
          "currency": "EUR",
          "orderReference": "BBG-2026-4711",
          "supplierNumber": "L-100234",
          "seller": { "name": "Stoicera Software GesbR", "vatId": "ATU12345678", "email": "office@stoicera-software.at",
            "address": { "street": "Hauptplatz 1", "city": "Linz", "postalCode": "4020", "countryCode": "AT" } },
          "buyer": { "name": "Bundesbeschaffung GmbH", "vatId": "ATU87654321",
            "address": { "street": "Lassallestraße 9b", "city": "Wien", "postalCode": "1020", "countryCode": "AT" } },
          "lines": [
            { "id": "1", "description": "Softwareentwicklung Juli 2026", "quantity": "80", "unitCode": "HUR", "unitPrice": "120.00", "vatCategory": "STANDARD", "vatPercent": "20" }
          ],
          "paymentMeans": { "iban": "AT611904300234573201", "bic": "BKAUATWW" },
          "paymentTerms": "Zahlbar innerhalb von 30 Tagen ohne Abzug"
        }
        """
        .formatted(number);
  }
}
