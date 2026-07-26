package com.stoicera.einvoice.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.persistence.InvoiceRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The create-invoice wizard: four server-rendered steps, no JavaScript, no htmx (ADR-0009
 * Entscheidung 5).
 *
 * <h2>What the draft is, and why it lives in the session</h2>
 *
 * <p>A four-step form has to carry partial state somewhere. The two candidates were hidden fields
 * accumulated forward and a session-held draft; the draft won because step 3 collects a
 * <em>list</em> of invoice lines, and re-serialising a growing list through hidden inputs on every
 * step is both more markup and more ways to lose a line. The session already exists — the browser
 * chain has one for the login and for CSRF — so this adds no new mechanism.
 *
 * <p>The consequence is a property worth asserting rather than assuming: the draft is <strong>per
 * session</strong>, so a second browser cannot see a half-finished invoice ({@link
 * #aDraftIsNotVisibleToAnotherSession}).
 *
 * <h2>The last step goes through the same service as the API</h2>
 *
 * <p>Step 4 serialises the draft to canonical JSON and calls {@code InvoiceService.create} — the
 * same method {@code POST /api/v1/invoices} calls, with the same duplicate detection, the same
 * generated- and-validated report and the same audit event. A wizard-specific creation path would
 * have been a second place for invoice creation to drift, which is the same argument the public
 * validator's upload already settled for validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceWizardIT extends AbstractPostgresIT {

  private static final String NEW = "/app/rechnungen/neu";

  @Autowired private MockMvc mvc;
  @Autowired private TenantRepository tenants;
  @Autowired private InvoiceRepository invoices;

  // ------------------------------------------------------------------- step flow

  @Test
  void theWizardOpensOnTheHeaderStep() throws Exception {
    String sub = sub("open");
    tenant(sub);

    mvc.perform(get(NEW).with(login(sub)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Rechnungsnummer")))
        // The step indicator is what makes it a wizard rather than four unrelated forms.
        .andExpect(content().string(containsString("Kopfdaten")))
        .andExpect(content().string(containsString("Parteien")))
        .andExpect(content().string(containsString("Positionen")));
  }

  @Test
  void theStepsAdvanceInOrderAndTheDraftSurvivesEachHop() throws Exception {
    String sub = sub("advance");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();

    mvc.perform(header(sub, session, "RE-WIZ-ADVANCE")).andExpect(redirectedUrl(NEW + "/parteien"));
    mvc.perform(parties(sub, session)).andExpect(redirectedUrl(NEW + "/positionen"));
    mvc.perform(addLine(sub, session)).andExpect(redirectedUrl(NEW + "/positionen"));
    mvc.perform(post(NEW + "/positionen").with(login(sub)).with(csrf()).session(session))
        .andExpect(redirectedUrl(NEW + "/zahlung"));

    // The payment step doubles as the review: everything entered so far has to be on it, or the
    // user is asked to confirm something they cannot see.
    mvc.perform(get(NEW + "/zahlung").with(login(sub)).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("RE-WIZ-ADVANCE")))
        .andExpect(content().string(containsString("Bundesbeschaffung GmbH")))
        .andExpect(content().string(containsString("Softwareentwicklung")));
  }

  @Test
  void aBlankInvoiceNumberIsRefusedOnTheStepThatAsksForIt() throws Exception {
    String sub = sub("blank-number");
    tenant(sub);

    mvc.perform(
            post(NEW + "/kopf")
                .param("invoiceNumber", "  ")
                .param("type", "INVOICE")
                .param("issueDate", "2026-07-24")
                .param("currency", "EUR")
                .with(login(sub))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Rechnungsnummer")))
        .andExpect(content().string(containsString("erforderlich")));
  }

  @Test
  void aMissingIssueDateIsRefusedOnTheHeaderStep() throws Exception {
    String sub = sub("blank-date");
    tenant(sub);

    mvc.perform(
            post(NEW + "/kopf")
                .param("invoiceNumber", "RE-NO-DATE")
                .param("type", "INVOICE")
                .param("currency", "EUR")
                .with(login(sub))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Rechnungsdatum")))
        .andExpect(content().string(containsString("erforderlich")));
  }

  @Test
  void aMissingPartyNameIsRefusedOnThePartiesStep() throws Exception {
    String sub = sub("blank-party");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-NOPARTY"));

    // Buyer supplied, seller blank: core requires both, and finding out at step 4 would send the
    // user back two pages.
    mvc.perform(
            post(NEW + "/parteien")
                .param("sellerName", "   ")
                .param("buyerName", "Bundesbeschaffung GmbH")
                .with(login(sub))
                .with(csrf())
                .session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("erforderlich")));
  }

  @Test
  void aLineWithNoDescriptionOrAmountIsRefused() throws Exception {
    String sub = sub("blank-line");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-BADLINE"));
    mvc.perform(parties(sub, session));

    mvc.perform(
            post(NEW + "/positionen/hinzufuegen")
                .param("description", "")
                .param("quantity", "")
                .param("unitPrice", "")
                .with(login(sub))
                .with(csrf())
                .session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Beschreibung, Menge und Einzelpreis")));
  }

  @Test
  void aLineOmittingTheOptionalFieldsGetsTheAustrianDefaults() throws Exception {
    String sub = sub("line-defaults");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-DEFAULTS"));
    mvc.perform(parties(sub, session));

    // Unit, VAT category and rate are the three a user is most likely to leave alone. Defaulting to
    // C62 (each) / STANDARD / 20 % is the overwhelmingly common Austrian case, and it must be
    // applied
    // rather than producing a line the reader then rejects.
    mvc.perform(
            post(NEW + "/positionen/hinzufuegen")
                .param("description", "Beratung")
                .param("quantity", "1")
                .param("unitPrice", "100.00")
                .with(login(sub))
                .with(csrf())
                .session(session))
        .andExpect(redirectedUrl(NEW + "/positionen"));

    mvc.perform(get(NEW + "/positionen").with(login(sub)).session(session))
        .andExpect(content().string(containsString("Beratung")))
        .andExpect(content().string(containsString("C62")))
        .andExpect(content().string(containsString("20 %")));
  }

  @Test
  void advancingPastThePositionsStepWithNoLinesIsRefused() throws Exception {
    String sub = sub("no-lines");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-NOLINES"));
    mvc.perform(parties(sub, session));

    // core requires at least one line; catching it here means a clear message on the step that can
    // fix it, rather than an InvariantViolationException surfacing three steps later.
    mvc.perform(post(NEW + "/positionen").with(login(sub)).with(csrf()).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("mindestens eine Position")));
  }

  @Test
  void aLineCanBeRemovedAgain() throws Exception {
    String sub = sub("remove-line");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-REMOVE"));
    mvc.perform(parties(sub, session));
    mvc.perform(addLine(sub, session));

    mvc.perform(get(NEW + "/positionen").with(login(sub)).session(session))
        .andExpect(content().string(containsString("Softwareentwicklung")));

    mvc.perform(
            post(NEW + "/positionen/0/entfernen").with(login(sub)).with(csrf()).session(session))
        .andExpect(redirectedUrl(NEW + "/positionen"));

    mvc.perform(get(NEW + "/positionen").with(login(sub)).session(session))
        .andExpect(content().string(not(containsString("Softwareentwicklung"))));
  }

  // --------------------------------------------------------------------- creation

  @Test
  void completingTheWizardCreatesTheInvoiceAndRedirectsToIt() throws Exception {
    String sub = sub("create");
    TenantEntity tenant = tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-CREATE"));
    mvc.perform(parties(sub, session));
    mvc.perform(addLine(sub, session));

    mvc.perform(create(sub, session))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/app/rechnungen/????????-????-????-????-????????????"));

    Assertions.assertThat(
            invoices.findByTenantId(
                tenant.getId(), org.springframework.data.domain.PageRequest.of(0, 10)))
        .anySatisfy(
            invoice ->
                Assertions.assertThat(invoice.getInvoiceNumber()).isEqualTo("RE-WIZ-CREATE"));
  }

  @Test
  void theCreatedInvoiceCarriesTheEnteredDataThroughToTheStoredCanonicalJson() throws Exception {
    String sub = sub("create-data");
    TenantEntity tenant = tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-DATA"));
    mvc.perform(parties(sub, session));
    mvc.perform(addLine(sub, session));
    mvc.perform(create(sub, session));

    // 80 h × 120.00 + 20 % VAT = 11 520.00. The wizard's job is to produce canonical JSON that core
    // then does the arithmetic on; asserting the total proves the numbers survived the form.
    Assertions.assertThat(
            invoices
                .findByTenantId(
                    tenant.getId(), org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent())
        .anySatisfy(
            invoice -> {
              Assertions.assertThat(invoice.getInvoiceNumber()).isEqualTo("RE-WIZ-DATA");
              Assertions.assertThat(invoice.getBuyerName()).isEqualTo("Bundesbeschaffung GmbH");
              Assertions.assertThat(invoice.getPayableAmount())
                  .isEqualByComparingTo(new java.math.BigDecimal("11520.00"));
            });
  }

  @Test
  void theDraftIsClearedAfterASuccessfulCreation() throws Exception {
    String sub = sub("clear-draft");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-CLEAR"));
    mvc.perform(parties(sub, session));
    mvc.perform(addLine(sub, session));
    mvc.perform(create(sub, session));

    // Reopening must offer an empty form, not the invoice just created — otherwise the next invoice
    // silently starts as a copy and duplicates a number.
    mvc.perform(get(NEW).with(login(sub)).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("RE-WIZ-CLEAR"))));
  }

  @Test
  void aDuplicateInvoiceNumberIsReportedOnThePageRatherThanAs409() throws Exception {
    String sub = sub("duplicate");
    tenant(sub);

    MockHttpSession first = new MockHttpSession();
    mvc.perform(header(sub, first, "RE-WIZ-DUP"));
    mvc.perform(parties(sub, first));
    mvc.perform(addLine(sub, first));
    mvc.perform(create(sub, first)).andExpect(status().is3xxRedirection());

    MockHttpSession second = new MockHttpSession();
    mvc.perform(header(sub, second, "RE-WIZ-DUP"));
    mvc.perform(parties(sub, second));
    mvc.perform(addLine(sub, second));

    mvc.perform(create(sub, second))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("bereits eine Rechnung")));
  }

  @Test
  void aRejectedIbanIsShownAsAFormErrorAndNotAsA500() throws Exception {
    String sub = sub("bad-iban");
    tenant(sub);
    MockHttpSession session = new MockHttpSession();
    mvc.perform(header(sub, session, "RE-WIZ-IBAN"));
    mvc.perform(parties(sub, session));
    mvc.perform(addLine(sub, session));

    // core validates the IBAN checksum and throws; the wizard must translate that into a message on
    // the step that owns the field.
    mvc.perform(
            post(NEW + "/anlegen")
                .param("iban", "AT611904300234573202")
                .param("bic", "BKAUATWW")
                .param("paymentTerms", "30 Tage")
                .with(login(sub))
                .with(csrf())
                .session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(matchesRegex("(?s).*(IBAN|Prüfsumme|ungültig).*")));
  }

  // ---------------------------------------------------------------- session scope

  @Test
  void aDraftIsNotVisibleToAnotherSession() throws Exception {
    String sub = sub("session-scope");
    tenant(sub);
    MockHttpSession mine = new MockHttpSession();
    mvc.perform(header(sub, mine, "RE-WIZ-PRIVATE"));

    // Same user, different browser session: the half-finished draft must not appear.
    mvc.perform(get(NEW).with(login(sub)).session(new MockHttpSession()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("RE-WIZ-PRIVATE"))));
  }

  @Test
  void everyWizardPostRequiresTheCsrfToken() throws Exception {
    String sub = sub("csrf");
    tenant(sub);

    mvc.perform(post(NEW + "/kopf").param("invoiceNumber", "RE-NO-TOKEN").with(login(sub)))
        .andExpect(status().isForbidden());
  }

  // -------------------------------------------------------------------- fixtures

  private MockHttpServletRequestBuilder header(String sub, MockHttpSession session, String number) {
    return post(NEW + "/kopf")
        .param("invoiceNumber", number)
        .param("type", "INVOICE")
        .param("issueDate", "2026-07-24")
        .param("dueDate", "2026-08-23")
        .param("deliveryDate", "2026-07-24")
        .param("currency", "EUR")
        .param("orderReference", "BBG-2026-4711")
        .param("supplierNumber", "L-100234")
        .with(login(sub))
        .with(csrf())
        .session(session);
  }

  private MockHttpServletRequestBuilder parties(String sub, MockHttpSession session) {
    return post(NEW + "/parteien")
        .param("sellerName", "Stoicera Software GesbR")
        .param("sellerVatId", "ATU12345678")
        .param("sellerEmail", "office@stoicera-software.at")
        .param("sellerStreet", "Hauptplatz 1")
        .param("sellerCity", "Linz")
        .param("sellerPostalCode", "4020")
        .param("sellerCountryCode", "AT")
        .param("buyerName", "Bundesbeschaffung GmbH")
        .param("buyerVatId", "ATU87654321")
        .param("buyerStreet", "Lassallestraße 9b")
        .param("buyerCity", "Wien")
        .param("buyerPostalCode", "1020")
        .param("buyerCountryCode", "AT")
        .with(login(sub))
        .with(csrf())
        .session(session);
  }

  private MockHttpServletRequestBuilder addLine(String sub, MockHttpSession session) {
    return post(NEW + "/positionen/hinzufuegen")
        .param("description", "Softwareentwicklung Juli 2026")
        .param("quantity", "80")
        .param("unitCode", "HUR")
        .param("unitPrice", "120.00")
        .param("vatCategory", "STANDARD")
        .param("vatPercent", "20")
        .with(login(sub))
        .with(csrf())
        .session(session);
  }

  private MockHttpServletRequestBuilder create(String sub, MockHttpSession session) {
    return post(NEW + "/anlegen")
        .param("iban", "AT611904300234573201")
        .param("bic", "BKAUATWW")
        .param("paymentTerms", "Zahlbar innerhalb von 30 Tagen ohne Abzug")
        .with(login(sub))
        .with(csrf())
        .session(session);
  }

  private static String sub(String test) {
    return "wizard-" + test;
  }

  private static RequestPostProcessor login(String sub) {
    return oauth2Login().attributes(attributes -> attributes.put("sub", sub));
  }

  private TenantEntity tenant(String sub) {
    return tenants
        .findByExternalSubject(sub)
        .orElseGet(() -> tenants.save(new TenantEntity(sub, "Assistent-Mandant " + sub)));
  }
}
