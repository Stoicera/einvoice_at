package com.stoicera.einvoice.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * {@link InvoiceDraft}'s one real job: turning what a user typed into the canonical JSON {@code
 * InvoiceJsonReader} accepts.
 *
 * <p>Two properties here are easy to get wrong and invisible when you do:
 *
 * <ul>
 *   <li><strong>An optional field left blank must be omitted, not sent as {@code ""}.</strong> The
 *       reader treats a missing {@code dueDate} as absent and an empty string as an unparsable date
 *       — so writing the empty string turns "the user left it blank" into a shape error naming a
 *       field they never filled in.
 *   <li><strong>The JSON must be built, not concatenated.</strong> Every value came from a text
 *       input, so a quote in a company name is a realistic input and would produce malformed JSON.
 * </ul>
 */
class InvoiceDraftTest {

  private final ObjectMapper json = new ObjectMapper();

  // ------------------------------------------------------- optional field omission

  @Test
  void blankOptionalFieldsAreOmittedRatherThanSentEmpty() throws Exception {
    InvoiceDraft draft = minimal();

    JsonNode node = json.readTree(draft.toCanonicalJson());

    assertThat(node.has("dueDate")).isFalse();
    assertThat(node.has("deliveryDate")).isFalse();
    assertThat(node.has("orderReference")).isFalse();
    assertThat(node.has("supplierNumber")).isFalse();
    assertThat(node.has("paymentTerms")).isFalse();
    // paymentMeans is dropped whole when there is no IBAN — an object with a blank iban would fail
    // the checksum check with a message about a value the user never entered.
    assertThat(node.has("paymentMeans")).isFalse();
  }

  @Test
  void presentOptionalFieldsAreIncludedAndTrimmed() throws Exception {
    InvoiceDraft draft = minimal();
    draft.setDueDate("2026-08-23");
    draft.setDeliveryDate("2026-07-24");
    draft.setOrderReference("  BBG-2026-4711  ");
    draft.setSupplierNumber("L-100234");
    draft.setPaymentTerms("30 Tage");

    JsonNode node = json.readTree(draft.toCanonicalJson());

    assertThat(node.get("dueDate").asText()).isEqualTo("2026-08-23");
    assertThat(node.get("deliveryDate").asText()).isEqualTo("2026-07-24");
    assertThat(node.get("orderReference").asText()).isEqualTo("BBG-2026-4711");
    assertThat(node.get("supplierNumber").asText()).isEqualTo("L-100234");
    assertThat(node.get("paymentTerms").asText()).isEqualTo("30 Tage");
  }

  @Test
  void anIbanIsIncludedWithSpacesStrippedAndABlankBicOmitted() throws Exception {
    InvoiceDraft draft = minimal();
    // Users paste IBANs in the grouped form the bank prints them in.
    draft.setIban("AT61 1904 3002 3457 3201");

    JsonNode means = json.readTree(draft.toCanonicalJson()).get("paymentMeans");

    assertThat(means.get("iban").asText()).isEqualTo("AT611904300234573201");
    assertThat(means.has("bic")).isFalse();
  }

  @Test
  void aPresentBicIsIncluded() throws Exception {
    InvoiceDraft draft = minimal();
    draft.setIban("AT611904300234573201");
    draft.setBic("BKAUATWW");

    assertThat(json.readTree(draft.toCanonicalJson()).get("paymentMeans").get("bic").asText())
        .isEqualTo("BKAUATWW");
  }

  @Test
  void aBlankSellerVatIdAndEmailAreOmitted() throws Exception {
    JsonNode seller = json.readTree(minimal().toCanonicalJson()).get("seller");

    assertThat(seller.has("vatId")).isFalse();
    assertThat(seller.has("email")).isFalse();
    assertThat(seller.get("name").asText()).isEqualTo("Stoicera Software GesbR");
  }

  @Test
  void theBuyerNeverCarriesAnEmailField() throws Exception {
    // The wizard collects no buyer e-mail; the party builder is called with null for it, and a null
    // must be omitted rather than written as JSON null.
    InvoiceDraft draft = minimal();
    draft.setBuyerVatId("ATU87654321");

    JsonNode buyer = json.readTree(draft.toCanonicalJson()).get("buyer");

    assertThat(buyer.has("email")).isFalse();
    assertThat(buyer.get("vatId").asText()).isEqualTo("ATU87654321");
  }

  // ---------------------------------------------------------------------- escaping

  @Test
  void aQuoteInACompanyNameDoesNotBreakTheJson() throws Exception {
    InvoiceDraft draft = minimal();
    draft.setBuyerName("Franz \"Ferdinand\" Handels-GmbH \\ Co");

    // Parses at all — which is the assertion. String concatenation would have produced garbage
    // here.
    JsonNode node = json.readTree(draft.toCanonicalJson());

    assertThat(node.get("buyer").get("name").asText())
        .isEqualTo("Franz \"Ferdinand\" Handels-GmbH \\ Co");
  }

  @Test
  void aNewlineInAFieldSurvivesAsData() throws Exception {
    InvoiceDraft draft = minimal();
    draft.setPaymentTerms("Zahlbar in 30 Tagen.\nSkonto 2 % in 10 Tagen.");

    assertThat(json.readTree(draft.toCanonicalJson()).get("paymentTerms").asText()).contains("\n");
  }

  // -------------------------------------------------------------------- line ids

  @Test
  void lineIdsArePositionalAndOneBased() throws Exception {
    InvoiceDraft draft = minimal();
    draft.addLine(line("Zweite"));

    JsonNode lines = json.readTree(draft.toCanonicalJson()).get("lines");

    // BT-126 must be unique within the document; generating it removes the chance of a user not.
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).get("id").asText()).isEqualTo("1");
    assertThat(lines.get(1).get("id").asText()).isEqualTo("2");
    assertThat(lines.get(1).get("description").asText()).isEqualTo("Zweite");
  }

  @Test
  void removingALineRenumbersTheRemainingOnes() throws Exception {
    InvoiceDraft draft = minimal();
    draft.addLine(line("Zweite"));
    draft.addLine(line("Dritte"));

    draft.removeLine(0);
    JsonNode lines = json.readTree(draft.toCanonicalJson()).get("lines");

    // Positional ids mean removal must not leave a hole; a document with lines 2 and 3 and no 1 is
    // legal but confusing, and a gap is the kind of thing a downstream system complains about.
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).get("id").asText()).isEqualTo("1");
    assertThat(lines.get(0).get("description").asText()).isEqualTo("Zweite");
    assertThat(lines.get(1).get("id").asText()).isEqualTo("2");
  }

  @Test
  void removingAnIndexThatIsNotThereChangesNothing() {
    InvoiceDraft draft = minimal();

    draft.removeLine(7);
    draft.removeLine(-1);

    // A stale form re-submitted after another tab removed a line must not blow up.
    assertThat(draft.getLines()).hasSize(1);
  }

  @Test
  void theLineListIsNotModifiableThroughTheGetter() {
    InvoiceDraft draft = minimal();

    assertThat(draft.getLines()).isUnmodifiable();
  }

  @Test
  void hasLinesReflectsTheList() {
    InvoiceDraft empty = new InvoiceDraft();

    assertThat(empty.hasLines()).isFalse();
    assertThat(minimal().hasLines()).isTrue();
  }

  // --------------------------------------------------------------- setter defaults

  @Test
  void aBlankTypeCurrencyOrCountryFallsBackToItsDefault() {
    InvoiceDraft draft = new InvoiceDraft();

    draft.setType("");
    draft.setCurrency("  ");
    draft.setSellerCountryCode(null);
    draft.setBuyerCountryCode("");

    // These four have a sensible default and no reason to ever be empty: an Austrian invoice in EUR
    // is the overwhelming case, and a blank currency would fail with a message about ISO 4217.
    assertThat(draft.getType()).isEqualTo("INVOICE");
    assertThat(draft.getCurrency()).isEqualTo("EUR");
    assertThat(draft.getSellerCountryCode()).isEqualTo("AT");
    assertThat(draft.getBuyerCountryCode()).isEqualTo("AT");
  }

  @Test
  void anExplicitTypeCurrencyOrCountryIsKept() {
    InvoiceDraft draft = new InvoiceDraft();

    draft.setType("CREDIT_NOTE");
    draft.setCurrency("CHF");
    draft.setSellerCountryCode("DE");
    draft.setBuyerCountryCode("CH");

    assertThat(draft.getType()).isEqualTo("CREDIT_NOTE");
    assertThat(draft.getCurrency()).isEqualTo("CHF");
    assertThat(draft.getSellerCountryCode()).isEqualTo("DE");
    assertThat(draft.getBuyerCountryCode()).isEqualTo("CH");
  }

  @Test
  void aNullFromAnAbsentFormFieldBecomesTheEmptyString() {
    // Every setter is fed straight from a @RequestParam(required = false), so null is the normal
    // input for a field the browser did not send. It must never become the literal "null".
    InvoiceDraft draft = new InvoiceDraft();

    draft.setInvoiceNumber(null);
    draft.setIssueDate(null);
    draft.setDueDate(null);
    draft.setDeliveryDate(null);
    draft.setOrderReference(null);
    draft.setSupplierNumber(null);
    draft.setSellerName(null);
    draft.setSellerVatId(null);
    draft.setSellerEmail(null);
    draft.setSellerStreet(null);
    draft.setSellerCity(null);
    draft.setSellerPostalCode(null);
    draft.setBuyerName(null);
    draft.setBuyerVatId(null);
    draft.setBuyerStreet(null);
    draft.setBuyerCity(null);
    draft.setBuyerPostalCode(null);
    draft.setIban(null);
    draft.setBic(null);
    draft.setPaymentTerms(null);

    assertThat(draft.getInvoiceNumber()).isEmpty();
    assertThat(draft.getIssueDate()).isEmpty();
    assertThat(draft.getDueDate()).isEmpty();
    assertThat(draft.getDeliveryDate()).isEmpty();
    assertThat(draft.getOrderReference()).isEmpty();
    assertThat(draft.getSupplierNumber()).isEmpty();
    assertThat(draft.getSellerName()).isEmpty();
    assertThat(draft.getSellerVatId()).isEmpty();
    assertThat(draft.getSellerEmail()).isEmpty();
    assertThat(draft.getSellerStreet()).isEmpty();
    assertThat(draft.getSellerCity()).isEmpty();
    assertThat(draft.getSellerPostalCode()).isEmpty();
    assertThat(draft.getBuyerName()).isEmpty();
    assertThat(draft.getBuyerVatId()).isEmpty();
    assertThat(draft.getBuyerStreet()).isEmpty();
    assertThat(draft.getBuyerCity()).isEmpty();
    assertThat(draft.getBuyerPostalCode()).isEmpty();
    assertThat(draft.getIban()).isEmpty();
    assertThat(draft.getBic()).isEmpty();
    assertThat(draft.getPaymentTerms()).isEmpty();
  }

  // ---------------------------------------------------------------------- fixtures

  /** The smallest draft that produces well-formed canonical JSON: header, parties, one line. */
  private static InvoiceDraft minimal() {
    InvoiceDraft draft = new InvoiceDraft();
    draft.setInvoiceNumber("  RE-2026-0001  ");
    draft.setIssueDate("2026-07-24");
    draft.setSellerName("Stoicera Software GesbR");
    draft.setSellerStreet("Hauptplatz 1");
    draft.setSellerCity("Linz");
    draft.setSellerPostalCode("4020");
    draft.setBuyerName("Bundesbeschaffung GmbH");
    draft.setBuyerStreet("Lassallestraße 9b");
    draft.setBuyerCity("Wien");
    draft.setBuyerPostalCode("1020");
    draft.addLine(line("Softwareentwicklung"));
    return draft;
  }

  private static InvoiceDraft.Line line(String description) {
    return new InvoiceDraft.Line(description, "80", "HUR", "120.00", "STANDARD", "20");
  }
}
