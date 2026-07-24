package com.stoicera.einvoice.mapping.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.diagnostics.error.list.ErrorList;
import com.helger.ebinterface.EbInterface61Marshaller;
import com.helger.ebinterface.v61.Ebi61AccountType;
import com.helger.ebinterface.v61.Ebi61DocumentTypeType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61ListLineItemType;
import com.helger.ebinterface.v61.Ebi61TaxItemType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.mapping.Fixtures;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Row-by-row verification of the canonical → ebInterface 6.1 mapping table. Assertions walk the
 * mapped JAXB object graph field by field; XML string matching is the property test's job, not this
 * one's.
 */
class InvoiceToEbInterface61MapperTest {

  private final InvoiceToEbInterface61Mapper mapper = new InvoiceToEbInterface61Mapper();
  private static final EbInterface61Strategy STRATEGY = new EbInterface61Strategy();

  // --- Header --------------------------------------------------------------------------------

  @Test
  void mapsInvoiceNumberAndDate() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    assertThat(ebi.getInvoiceNumber()).isEqualTo("2026-000123");
    assertThat(ebi.getInvoiceDateLocal()).isEqualTo("2026-07-01");
  }

  @Test
  void mapsCommercialInvoiceToInvoiceDocumentType() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    assertThat(ebi.getDocumentType()).isEqualTo(Ebi61DocumentTypeType.INVOICE);
  }

  @Test
  void mapsCreditNoteToCreditMemoDocumentType() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.reverseChargeCreditNote());

    assertThat(ebi.getDocumentType()).isEqualTo(Ebi61DocumentTypeType.CREDIT_MEMO);
  }

  @Test
  void mapsCurrencyGeneratingSystemAndLanguage() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    assertThat(ebi.getInvoiceCurrency()).isEqualTo("EUR");
    assertThat(ebi.getGeneratingSystem()).isEqualTo("einvoice-at");
    // LanguageType is a 2-char ISO 639-1 token in the XSD -> "de".
    assertThat(ebi.getLanguage()).isEqualTo("de");
  }

  // --- Biller (seller) -----------------------------------------------------------------------

  @Test
  void mapsBillerVatAddressAndSupplierNumber() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    var biller = ebi.getBiller();
    assertThat(biller.getVATIdentificationNumber()).isEqualTo("ATU12345678");
    // supplierNumber (Lieferantennummer) -> InvoiceRecipientsBillerID.
    assertThat(biller.getInvoiceRecipientsBillerID()).isEqualTo("LF-4711");

    var address = biller.getAddress();
    assertThat(address.getName()).isEqualTo("Ökostrom & Wärme GmbH");
    assertThat(address.getStreet()).isEqualTo("Grünmarktgasse 5");
    assertThat(address.getTown()).isEqualTo("Wien");
    assertThat(address.getZIP()).isEqualTo("1010");
    // A6: the element text is the German country name; the ISO code stays on @CountryCode.
    assertThat(address.getCountry().getValue()).isEqualTo("Österreich");
    assertThat(address.getCountry().getCountryCode()).isEqualTo("AT");
  }

  // --- Country display name (A6) -------------------------------------------------------------

  @Test
  void mapsCountryElementTextToGermanDisplayNameKeepingIsoCodeAttribute() {
    // AT -> "Österreich"/@CountryCode "AT" (seller) and IT -> "Italien"/@CountryCode "IT" (the
    // cross-border recipient), so the human-readable name is in the element content — AUSTRIAPRO's
    // own convention — while the ISO code stays on the attribute.
    Ebi61InvoiceType ebi = mapper.map(Fixtures.exemptInvoice());

    var billerCountry = ebi.getBiller().getAddress().getCountry();
    assertThat(billerCountry.getValue()).isEqualTo("Österreich");
    assertThat(billerCountry.getCountryCode()).isEqualTo("AT");

    var recipientCountry = ebi.getInvoiceRecipient().getAddress().getCountry();
    assertThat(recipientCountry.getValue()).isEqualTo("Italien");
    assertThat(recipientCountry.getCountryCode()).isEqualTo("IT");
  }

  @Test
  void omitsSupplierNumberWhenAbsent() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.minimalB2bInvoice());

    assertThat(ebi.getBiller().getInvoiceRecipientsBillerID()).isNull();
  }

  // --- InvoiceRecipient (buyer) --------------------------------------------------------------

  @Test
  void mapsRecipientVatAddressAndOrderReference() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    var recipient = ebi.getInvoiceRecipient();
    assertThat(recipient.getVATIdentificationNumber()).isEqualTo("ATU87654321");
    assertThat(recipient.getAddress().getName()).isEqualTo("Bundesministerium für Öffentliches");
    // orderReference (Auftragsreferenz) -> InvoiceRecipient/OrderReference/OrderID.
    assertThat(recipient.getOrderReference().getOrderID()).isEqualTo("BBG-2026-4711");
  }

  @Test
  void omitsOrderReferenceWhenAbsent() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.minimalB2bInvoice());

    assertThat(ebi.getInvoiceRecipient().getOrderReference()).isNull();
  }

  // --- No-UID convention (e-rechnung.gv.at ATU00000000) --------------------------------------

  @Test
  void mapsAtu00000000ConventionWhenPartiesHaveNoVatId() {
    // core permits Party.vatId == null (Kleinunternehmer/private buyer); the 6.1 XSD requires
    // VATIdentificationNumber on both Biller and InvoiceRecipient. e-rechnung.gv.at resolves this
    // with the placeholder ATU00000000 on each party lacking a UID.
    Ebi61InvoiceType ebi = mapper.map(Fixtures.invoiceWithoutVatIds());

    assertThat(ebi.getBiller().getVATIdentificationNumber()).isEqualTo("ATU00000000");
    assertThat(ebi.getInvoiceRecipient().getVATIdentificationNumber()).isEqualTo("ATU00000000");
  }

  @Test
  void noUidInvoiceReReadsWithoutSchemaErrors() {
    // The regression guard for A1: before the convention, a null vatId marshalled to a document
    // simply MISSING the XSD-required element, and write() reported success. Re-read with the
    // schema on so the bundled ebInterface 6.1 XSD is the judge.
    String xml = STRATEGY.write(mapper.map(Fixtures.invoiceWithoutVatIds()));

    ErrorList errors = new ErrorList();
    new EbInterface61Marshaller()
        .setUseSchema(true)
        .setCollectErrors(errors)
        .read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", errors, xml)
        .isFalse();
  }

  // --- Details / line items ------------------------------------------------------------------

  @Test
  void mapsLinesToPositionedListLineItems() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    assertThat(ebi.getDetails().getItemListCount()).isEqualTo(1);
    var items = ebi.getDetails().getItemListAtIndex(0).getListLineItem();
    assertThat(items).hasSize(2);

    Ebi61ListLineItemType first = items.get(0);
    assertThat(first.getPositionNumber()).isEqualTo(BigInteger.ONE);
    assertThat(first.getDescription()).containsExactly("Beratungsleistung März");
    assertThat(first.getQuantity().getValue()).isEqualByComparingTo("2");
    assertThat(first.getQuantity().getUnit()).isEqualTo("HUR");
    assertThat(first.getUnitPrice().getValue()).isEqualByComparingTo("100.00");
    assertThat(first.getLineItemAmount()).isEqualByComparingTo("200.00");
    assertThat(first.getTaxItem().getTaxableAmount()).isEqualByComparingTo("200.00");
    assertThat(first.getTaxItem().getTaxPercent().getValue()).isEqualByComparingTo("20.00");
    assertThat(first.getTaxItem().getTaxPercent().getTaxCategoryCode()).isEqualTo("S");

    Ebi61ListLineItemType second = items.get(1);
    assertThat(second.getPositionNumber()).isEqualTo(BigInteger.TWO);
    assertThat(second.getLineItemAmount()).isEqualByComparingTo("150.00");
    assertThat(second.getTaxItem().getTaxPercent().getValue()).isEqualByComparingTo("10.00");
    assertThat(second.getTaxItem().getTaxPercent().getTaxCategoryCode()).isEqualTo("S");
  }

  // --- Tax breakdown -------------------------------------------------------------------------

  @Test
  void mapsVatBreakdownToDocumentLevelTaxItems() {
    Invoice invoice = Fixtures.sampleB2gInvoice();
    Ebi61InvoiceType ebi = mapper.map(invoice);

    assertThat(ebi.getTax().getTaxItemCount())
        .isEqualTo(invoice.vatBreakdown().size())
        .isEqualTo(2);

    // Breakdown order follows VatRate ordering: standard 20 % before standard 10 %.
    Ebi61TaxItemType twenty = ebi.getTax().getTaxItemAtIndex(0);
    assertThat(twenty.getTaxableAmount()).isEqualByComparingTo("200.00");
    assertThat(twenty.getTaxPercent().getValue()).isEqualByComparingTo("20.00");
    assertThat(twenty.getTaxPercent().getTaxCategoryCode()).isEqualTo("S");
    assertThat(twenty.getTaxAmount()).isEqualByComparingTo("40.00");
    assertThat(twenty.getComment()).isNull();

    Ebi61TaxItemType ten = ebi.getTax().getTaxItemAtIndex(1);
    assertThat(ten.getTaxableAmount()).isEqualByComparingTo("150.00");
    assertThat(ten.getTaxAmount()).isEqualByComparingTo("15.00");
  }

  @Test
  void echoesReverseChargeExemptionReasonIntoTaxItemComment() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.reverseChargeCreditNote());

    Ebi61TaxItemType taxItem = ebi.getTax().getTaxItemAtIndex(0);
    assertThat(taxItem.getTaxPercent().getTaxCategoryCode()).isEqualTo("AE");
    // Reverse charge is NOT a Steuerbefreiung: § 11 Abs 1a UStG requires the Hinweis auf den
    // Übergang der Steuerschuld. Default BR-AE-10 reason (VATEX-EU-AE / "Reverse charge") stays
    // appended in the "|"-joined format.
    assertThat(taxItem.getComment())
        .startsWith("Übergang der Steuerschuld")
        .contains("AE")
        .contains("VATEX-EU-AE")
        .contains("Reverse charge");
  }

  @Test
  void echoesExemptReasonCodeAndTextIntoTaxItemComment() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.exemptInvoice());

    Ebi61TaxItemType taxItem = ebi.getTax().getTaxItemAtIndex(0);
    assertThat(taxItem.getTaxPercent().getTaxCategoryCode()).isEqualTo("E");
    // Category E is a genuine Steuerbefreiung and keeps that lead-in.
    assertThat(taxItem.getComment())
        .startsWith("Steuerbefreiung: ")
        .contains("E")
        .contains("VATEX-EU-G")
        .contains("Innergemeinschaftliche Lieferung");
  }

  // --- Totals --------------------------------------------------------------------------------

  @Test
  void copiesGrossAndPayableTotalsWithoutRecomputing() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    assertThat(ebi.getTotalGrossAmount()).isEqualByComparingTo("405.00");
    assertThat(ebi.getPayableAmount()).isEqualByComparingTo("405.00");
  }

  // --- Payment -------------------------------------------------------------------------------

  @Test
  void mapsPaymentMeansToUniversalBankTransaction() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    var ubt = ebi.getPaymentMethod().getUniversalBankTransaction();
    assertThat(ubt.getBeneficiaryAccountCount()).isEqualTo(1);
    Ebi61AccountType account = ubt.getBeneficiaryAccountAtIndex(0);
    assertThat(account.getIBAN()).isEqualTo("AT611904300234573201");
    assertThat(account.getBIC()).isEqualTo("BKAUATWW");
  }

  @Test
  void omitsPaymentMethodWhenNoPaymentMeans() {
    // A commercial invoice without payment means keeps the whole PaymentMethod omitted (the XSD
    // makes it optional) — A10 only changes the credit-note branch below.
    Ebi61InvoiceType ebi = mapper.map(Fixtures.minimalB2bInvoice());

    assertThat(ebi.getPaymentMethod()).isNull();
  }

  // --- NoPayment for credit notes (A10) ------------------------------------------------------

  @Test
  void creditNoteWithoutPaymentMeansEmitsNoPayment() {
    // e-rechnung.gv.at: an effektive Gutschrift (a credit note that moves no money) should carry
    // PaymentMethod/NoPayment, not an omitted payment block and not a bank transaction.
    Ebi61InvoiceType ebi = mapper.map(Fixtures.reverseChargeCreditNote());

    assertThat(ebi.getPaymentMethod()).isNotNull();
    assertThat(ebi.getPaymentMethod().getNoPayment()).isNotNull();
    assertThat(ebi.getPaymentMethod().getUniversalBankTransaction()).isNull();
  }

  @Test
  void creditNoteWithoutPaymentMeansReReadsWithoutSchemaErrors() {
    // NoPaymentType is an empty element in the 6.1 XSD; re-read with the schema on to prove the
    // emitted PaymentMethod/NoPayment is schema-clean.
    String xml = STRATEGY.write(mapper.map(Fixtures.reverseChargeCreditNote()));

    ErrorList errors = new ErrorList();
    new EbInterface61Marshaller()
        .setUseSchema(true)
        .setCollectErrors(errors)
        .read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", errors, xml)
        .isFalse();
  }

  @Test
  void creditNoteWithPaymentMeansKeepsBankTransaction() {
    // A credit note that DOES carry payment means (a refund account) keeps its bank transaction —
    // NoPayment is only for the no-money case.
    Ebi61InvoiceType ebi = mapper.map(Fixtures.creditNoteWithRefundAccount());

    assertThat(ebi.getPaymentMethod()).isNotNull();
    assertThat(ebi.getPaymentMethod().getNoPayment()).isNull();
    var ubt = ebi.getPaymentMethod().getUniversalBankTransaction();
    assertThat(ubt).isNotNull();
    assertThat(ubt.getBeneficiaryAccountAtIndex(0).getIBAN()).isEqualTo("AT611904300234573201");
  }

  @Test
  void mapsDueDateAndPaymentTermsIntoPaymentConditions() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.sampleB2gInvoice());

    var conditions = ebi.getPaymentConditions();
    assertThat(conditions.getDueDateLocal()).isEqualTo("2026-07-31");
    // paymentTerms -> PaymentConditions/Comment (the XSD slot for free-text terms).
    assertThat(conditions.getComment())
        .isEqualTo("Zahlbar binnen 30 Tagen netto. 2 % Skonto bei Zahlung binnen 10 Tagen.");
  }

  @Test
  void omitsPaymentConditionsWhenNoDueDateOrTerms() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.minimalB2bInvoice());

    assertThat(ebi.getPaymentConditions()).isNull();
  }

  // --- Delivery (BT-72 / BG-14) -------------------------------------------------------------

  @Test
  void mapsDeliveryDateToDeliveryDateElement() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.invoiceWithDeliveryDate());

    assertThat(ebi.getDelivery()).isNotNull();
    assertThat(ebi.getDelivery().getDateLocal()).isEqualTo(LocalDate.of(2026, 7, 20));
    assertThat(ebi.getDelivery().getPeriod()).isNull();
  }

  @Test
  void mapsServicePeriodToDeliveryPeriodElement() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.invoiceWithServicePeriod());

    assertThat(ebi.getDelivery()).isNotNull();
    assertThat(ebi.getDelivery().getDate()).isNull();
    var period = ebi.getDelivery().getPeriod();
    assertThat(period).isNotNull();
    assertThat(period.getFromDateLocal()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(period.getToDateLocal()).isEqualTo(LocalDate.of(2026, 7, 31));
  }

  @Test
  void omitsDeliveryWhenNeitherDeliveryDateNorServicePeriodPresent() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.minimalB2bInvoice());

    assertThat(ebi.getDelivery()).isNull();
  }

  @Test
  void deliveryDateInvoiceReReadsWithoutSchemaErrors() {
    String xml = STRATEGY.write(mapper.map(Fixtures.invoiceWithDeliveryDate()));

    ErrorList errors = new ErrorList();
    new EbInterface61Marshaller()
        .setUseSchema(true)
        .setCollectErrors(errors)
        .read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", errors, xml)
        .isFalse();
  }

  @Test
  void servicePeriodInvoiceReReadsWithoutSchemaErrors() {
    String xml = STRATEGY.write(mapper.map(Fixtures.invoiceWithServicePeriod()));

    ErrorList errors = new ErrorList();
    new EbInterface61Marshaller()
        .setUseSchema(true)
        .setCollectErrors(errors)
        .read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", errors, xml)
        .isFalse();
  }

  // --- Address/Email (party contact) --------------------------------------------------------

  @Test
  void mapsPartyEmailsToAddressEmail() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.invoiceWithPartyEmails());

    assertThat(ebi.getBiller().getAddress().getEmail())
        .containsExactly("rechnung@kontakt-software.at");
    assertThat(ebi.getInvoiceRecipient().getAddress().getEmail())
        .containsExactly("einkauf@amt-vergabe.gv.at");
  }

  @Test
  void omitsAddressEmailWhenAbsent() {
    Ebi61InvoiceType ebi = mapper.map(Fixtures.minimalB2bInvoice());

    assertThat(ebi.getBiller().getAddress().hasNoEmailEntries()).isTrue();
    assertThat(ebi.getInvoiceRecipient().getAddress().hasNoEmailEntries()).isTrue();
  }

  @Test
  void partyEmailInvoiceReReadsWithoutSchemaErrors() {
    String xml = STRATEGY.write(mapper.map(Fixtures.invoiceWithPartyEmails()));

    ErrorList errors = new ErrorList();
    new EbInterface61Marshaller()
        .setUseSchema(true)
        .setCollectErrors(errors)
        .read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", errors, xml)
        .isFalse();
  }

  @Test
  void preservesSuppliedUnitCode() {
    // core's InvoiceLine forbids a blank/null unit code (EN 16931 BT-130 is mandatory), so the
    // mapper copies the supplied code verbatim — there is no fallback to default.
    Ebi61InvoiceType ebi = mapper.map(Fixtures.minimalB2bInvoice());

    var item = ebi.getDetails().getItemListAtIndex(0).getListLineItem().get(0);
    assertThat(item.getQuantity().getUnit()).isEqualTo("KGM");
    assertThat(item.getQuantity().getValue()).isEqualByComparingTo("10");
    assertThat(item.getUnitPrice().getValue()).isEqualByComparingTo("12.50");
  }

  @Test
  void doesNotRecomputeAmounts_creditNoteKeepsPositivePayable() {
    Invoice invoice = Fixtures.reverseChargeCreditNote();
    Ebi61InvoiceType ebi = mapper.map(invoice);

    // Reverse charge: taxable 5000, tax 0, gross == payable == 5000, copied verbatim.
    assertThat(ebi.getTotalGrossAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(ebi.getPayableAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(ebi.getTax().getTaxItemAtIndex(0).getTaxAmount()).isEqualByComparingTo("0.00");
  }
}
