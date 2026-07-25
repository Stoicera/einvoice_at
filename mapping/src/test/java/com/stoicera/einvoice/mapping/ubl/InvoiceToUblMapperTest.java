package com.stoicera.einvoice.mapping.ubl;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.ElectronicAddress;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import com.stoicera.einvoice.mapping.Fixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxCategoryType;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;
import org.junit.jupiter.api.Test;

/**
 * Example-based tests for {@link InvoiceToUblMapper} — the mapping decisions the property suite
 * cannot express, each pinned against the concrete UBL element it lands in.
 */
class InvoiceToUblMapperTest {

  private static final InvoiceToUblMapper MAPPER = new InvoiceToUblMapper();

  private static InvoiceType mapInvoice(Invoice invoice) {
    return ((UblDocument.CommercialInvoice) MAPPER.map(invoice)).document();
  }

  private static CreditNoteType mapCreditNote(Invoice invoice) {
    return ((UblDocument.CreditNote) MAPPER.map(invoice)).document();
  }

  @Test
  void stampsThePeppolBisBillingCustomizationAndProfile() {
    InvoiceType ubl = mapInvoice(Fixtures.sampleB2gInvoice());

    assertThat(ubl.getCustomizationIDValue())
        .isEqualTo("urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0");
    assertThat(ubl.getProfileIDValue()).isEqualTo("urn:fdc:peppol.eu:2017:poacc:billing:01:1.0");
  }

  @Test
  void mapsHeaderFieldsOfACommercialInvoice() {
    InvoiceType ubl = mapInvoice(Fixtures.sampleB2gInvoice());

    assertThat(ubl.getIDValue()).isEqualTo("2026-000123");
    assertThat(ubl.getIssueDateValueLocal()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(ubl.getDueDateValueLocal()).isEqualTo(LocalDate.of(2026, 7, 31));
    assertThat(ubl.getInvoiceTypeCodeValue()).isEqualTo("380");
    assertThat(ubl.getDocumentCurrencyCodeValue()).isEqualTo("EUR");
    assertThat(ubl.getOrderReference().getIDValue()).isEqualTo("BBG-2026-4711");
  }

  /** The Lieferantennummer becomes BT-29, the seller identifier — see the mapper's Javadoc. */
  @Test
  void mapsTheSupplierNumberToASellerPartyIdentification() {
    InvoiceType ubl = mapInvoice(Fixtures.sampleB2gInvoice());

    assertThat(
            ubl.getAccountingSupplierParty()
                .getParty()
                .getPartyIdentification()
                .getFirst()
                .getIDValue())
        .isEqualTo("LF-4711");
  }

  @Test
  void mapsPartyNameAddressAndVatIdentification() {
    PartyType seller =
        mapInvoice(Fixtures.sampleB2gInvoice()).getAccountingSupplierParty().getParty();

    assertThat(seller.getPartyLegalEntity().getFirst().getRegistrationNameValue())
        .isEqualTo("Ökostrom & Wärme GmbH");
    assertThat(seller.getPostalAddress().getStreetNameValue()).isEqualTo("Grünmarktgasse 5");
    assertThat(seller.getPostalAddress().getCityNameValue()).isEqualTo("Wien");
    assertThat(seller.getPostalAddress().getPostalZoneValue()).isEqualTo("1010");
    assertThat(seller.getPostalAddress().getCountry().getIdentificationCodeValue()).isEqualTo("AT");
    assertThat(seller.getPartyTaxScheme().getFirst().getCompanyIDValue()).isEqualTo("ATU12345678");
    assertThat(seller.getPartyTaxScheme().getFirst().getTaxScheme().getIDValue()).isEqualTo("VAT");
  }

  /**
   * UBL, unlike ebInterface, lets the VAT identification be absent — so a Kleinunternehmer maps to
   * no {@code cac:PartyTaxScheme} at all, not to the {@code ATU00000000} placeholder the
   * ebInterface XSD forces.
   */
  @Test
  void omitsPartyTaxSchemeEntirelyForAPartyWithoutAVatId() {
    Invoice invoice = invoiceWithSeller(new Party("Kleinunternehmer OG", linz(), null));

    PartyType seller = mapInvoice(invoice).getAccountingSupplierParty().getParty();

    assertThat(seller.getPartyTaxScheme()).isEmpty();
    assertThat(seller.getPartyLegalEntity().getFirst().getRegistrationNameValue())
        .isEqualTo("Kleinunternehmer OG");
  }

  @Test
  void mapsTheElectronicAddressWithItsSchemeIdentifier() {
    Invoice invoice =
        invoiceWithSeller(
            new Party(
                "Stoicera Software Group",
                linz(),
                "ATU12345678",
                Optional.empty(),
                Optional.of(new ElectronicAddress("9915", "AT:VAT:ATU12345678"))));

    PartyType seller = mapInvoice(invoice).getAccountingSupplierParty().getParty();

    assertThat(seller.getEndpointIDValue()).isEqualTo("AT:VAT:ATU12345678");
    assertThat(seller.getEndpointID().getSchemeID()).isEqualTo("9915");
  }

  /** Never synthesised from the VAT id — absence stays absence. */
  @Test
  void omitsTheEndpointIdWhenThePartyHasNone() {
    PartyType seller =
        mapInvoice(Fixtures.sampleB2gInvoice()).getAccountingSupplierParty().getParty();

    assertThat(seller.getEndpointID()).isNull();
  }

  @Test
  void mapsThePartyEmailToAContact() {
    Invoice invoice =
        invoiceWithSeller(
            new Party(
                "Stoicera Software Group",
                linz(),
                "ATU12345678",
                Optional.of("rechnung@example.at")));

    PartyType seller = mapInvoice(invoice).getAccountingSupplierParty().getParty();

    assertThat(seller.getContact().getElectronicMailValue()).isEqualTo("rechnung@example.at");
  }

  @Test
  void omitsTheContactWhenThePartyHasNoEmail() {
    PartyType seller =
        mapInvoice(Fixtures.sampleB2gInvoice()).getAccountingSupplierParty().getParty();

    assertThat(seller.getContact()).isNull();
  }

  @Test
  void copiesTotalsWithoutRecomputingThem() {
    Invoice invoice = Fixtures.sampleB2gInvoice();
    InvoiceType ubl = mapInvoice(invoice);

    assertThat(ubl.getLegalMonetaryTotal().getLineExtensionAmountValue())
        .isEqualByComparingTo(invoice.totals().netTotal().amount());
    assertThat(ubl.getLegalMonetaryTotal().getTaxExclusiveAmountValue())
        .isEqualByComparingTo(invoice.totals().netTotal().amount());
    assertThat(ubl.getLegalMonetaryTotal().getTaxInclusiveAmountValue())
        .isEqualByComparingTo(invoice.totals().grossTotal().amount());
    assertThat(ubl.getLegalMonetaryTotal().getPayableAmountValue())
        .isEqualByComparingTo(invoice.totals().payableAmount().amount());
    assertThat(ubl.getTaxTotal().getFirst().getTaxAmountValue())
        .isEqualByComparingTo(invoice.totals().taxTotal().amount());
  }

  @Test
  void stampsTheDocumentCurrencyOnEveryAmount() {
    InvoiceType ubl = mapInvoice(Fixtures.sampleB2gInvoice());

    assertThat(ubl.getLegalMonetaryTotal().getPayableAmount().getCurrencyID()).isEqualTo("EUR");
    assertThat(ubl.getTaxTotal().getFirst().getTaxAmount().getCurrencyID()).isEqualTo("EUR");
    assertThat(
            ubl.getTaxTotal()
                .getFirst()
                .getTaxSubtotal()
                .getFirst()
                .getTaxableAmount()
                .getCurrencyID())
        .isEqualTo("EUR");
    assertThat(ubl.getInvoiceLine().getFirst().getLineExtensionAmount().getCurrencyID())
        .isEqualTo("EUR");
    assertThat(ubl.getInvoiceLine().getFirst().getPrice().getPriceAmount().getCurrencyID())
        .isEqualTo("EUR");
  }

  @Test
  void mapsLinesWithQuantityUnitCodeItemNameAndTaxCategory() {
    InvoiceType ubl = mapInvoice(Fixtures.sampleB2gInvoice());
    var line = ubl.getInvoiceLine().getFirst();

    assertThat(line.getIDValue()).isEqualTo("1");
    assertThat(line.getInvoicedQuantityValue()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(line.getInvoicedQuantity().getUnitCode()).isEqualTo("HUR");
    assertThat(line.getLineExtensionAmountValue()).isEqualByComparingTo(new BigDecimal("200.00"));
    assertThat(line.getItem().getNameValue()).isEqualTo("Beratungsleistung März");
    assertThat(line.getPrice().getPriceAmountValue())
        .isEqualByComparingTo(new BigDecimal("100.00"));

    TaxCategoryType category = line.getItem().getClassifiedTaxCategory().getFirst();
    assertThat(category.getIDValue()).isEqualTo("S");
    assertThat(category.getPercentValue()).isEqualByComparingTo(new BigDecimal("20"));
    assertThat(category.getTaxScheme().getIDValue()).isEqualTo("VAT");
  }

  @Test
  void mapsPaymentMeansAsCreditTransferWithIbanAndBic() {
    InvoiceType ubl = mapInvoice(Fixtures.sampleB2gInvoice());
    var means = ubl.getPaymentMeans().getFirst();

    assertThat(means.getPaymentMeansCodeValue()).isEqualTo("30");
    assertThat(means.getPayeeFinancialAccount().getIDValue()).isEqualTo("AT611904300234573201");
    assertThat(means.getPayeeFinancialAccount().getFinancialInstitutionBranch().getIDValue())
        .isEqualTo("BKAUATWW");
    // UBL-CR-412: an invoice must not repeat BT-9 here; it already has its own cbc:DueDate.
    assertThat(means.getPaymentDueDate()).isNull();
  }

  @Test
  void mapsPaymentTermsToANote() {
    InvoiceType ubl = mapInvoice(Fixtures.sampleB2gInvoice());

    assertThat(ubl.getPaymentTerms().getFirst().getNote().getFirst().getValue())
        .startsWith("Zahlbar binnen 30 Tagen");
  }

  /**
   * UBL splits what ebInterface kept together: a delivery date is {@code cac:Delivery}, a service
   * period is {@code cac:InvoicePeriod}. Core's mutual exclusion means at most one appears.
   */
  @Test
  void mapsADeliveryDateToDeliveryAndAServicePeriodToInvoicePeriod() {
    InvoiceType withDate =
        mapInvoice(sampleWith(builder -> builder.deliveryDate(LocalDate.of(2026, 6, 30))));

    assertThat(withDate.getDelivery().getFirst().getActualDeliveryDateValueLocal())
        .isEqualTo(LocalDate.of(2026, 6, 30));
    assertThat(withDate.getInvoicePeriod()).isEmpty();

    InvoiceType withPeriod =
        mapInvoice(
            sampleWith(
                builder ->
                    builder.servicePeriod(
                        new ServicePeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))));

    assertThat(withPeriod.getInvoicePeriod().getFirst().getStartDateValueLocal())
        .isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(withPeriod.getInvoicePeriod().getFirst().getEndDateValueLocal())
        .isEqualTo(LocalDate.of(2026, 6, 30));
    assertThat(withPeriod.getDelivery()).isEmpty();
  }

  /**
   * UBL keeps the exemption code and text in two dedicated elements, so — unlike the ebInterface
   * mapping, which has to fold both into a free-text Comment — they stay machine-readable.
   */
  @Test
  void splitsTheExemptionReasonIntoCodeAndTextElements() {
    Invoice reverseCharge =
        Invoice.builder()
            .invoiceNumber("2026-000900")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(new Party("Ausführer GmbH", linz(), "ATU12345678"))
            .buyer(new Party("Empfänger BV", linz(), "NL123456789B01"))
            .addLine(
                new InvoiceLine(
                    "1",
                    "Bauleistung",
                    new BigDecimal("1"),
                    "C62",
                    new BigDecimal("1000.00"),
                    VatRate.REVERSE_CHARGE))
            .exemptionReason(
                VatCategory.REVERSE_CHARGE,
                new VatExemptionReason("VATEX-EU-AE", "Übergang der Steuerschuld"))
            .build();

    TaxCategoryType category =
        mapInvoice(reverseCharge)
            .getTaxTotal()
            .getFirst()
            .getTaxSubtotal()
            .getFirst()
            .getTaxCategory();

    assertThat(category.getIDValue()).isEqualTo("AE");
    assertThat(category.getPercentValue()).isEqualByComparingTo(new BigDecimal("0"));
    assertThat(category.getTaxExemptionReasonCodeValue()).isEqualTo("VATEX-EU-AE");
    assertThat(category.getTaxExemptionReason().getFirst().getValue())
        .isEqualTo("Übergang der Steuerschuld");
  }

  @Test
  void mapsACreditNoteToTheCreditNoteRootWithCreditedQuantity() {
    Invoice creditNote = sampleWith(builder -> builder.type(InvoiceTypeCode.CREDIT_NOTE));

    CreditNoteType ubl = mapCreditNote(creditNote);

    assertThat(ubl.getCreditNoteTypeCodeValue()).isEqualTo("381");
    assertThat(ubl.getCreditNoteLine().getFirst().getCreditedQuantityValue())
        .isEqualByComparingTo(new BigDecimal("2"));
    assertThat(ubl.getCreditNoteLine().getFirst().getCreditedQuantity().getUnitCode())
        .isEqualTo("HUR");
  }

  /**
   * A UBL CreditNote has no {@code cbc:DueDate} element at all, so BT-9 moves onto the payment
   * means — the placement rule UBL-CR-412 exempts credit notes from.
   */
  @Test
  void putsACreditNoteDueDateOnThePaymentMeans() {
    Invoice creditNote = sampleWith(builder -> builder.type(InvoiceTypeCode.CREDIT_NOTE));

    CreditNoteType ubl = mapCreditNote(creditNote);

    assertThat(ubl.getPaymentMeans().getFirst().getPaymentDueDateValueLocal())
        .isEqualTo(LocalDate.of(2026, 7, 31));
  }

  private static Address linz() {
    return new Address("Hauptplatz 1", "Linz", "4020", "AT");
  }

  private static Invoice invoiceWithSeller(Party seller) {
    return Invoice.builder()
        .invoiceNumber("2026-000500")
        .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
        .issueDate(LocalDate.of(2026, 7, 1))
        .currency(Money.EUR)
        .seller(seller)
        .buyer(new Party("Käufer GmbH", linz(), "ATU87654321"))
        .addLine(
            new InvoiceLine(
                "1",
                "Leistung",
                new BigDecimal("1"),
                "C62",
                new BigDecimal("100.00"),
                VatRate.STANDARD_20))
        .build();
  }

  /**
   * The fixture invoice, rebuilt with one extra builder call. {@link Invoice} is immutable and its
   * builder is not re-openable, so the fixture is rebuilt rather than copied — which also keeps
   * each test's input visible in one place.
   */
  private static Invoice sampleWith(java.util.function.Consumer<Invoice.Builder> customise) {
    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber("2026-000123")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .dueDate(LocalDate.of(2026, 7, 31))
            .currency(Money.EUR)
            .orderReference("BBG-2026-4711")
            .supplierNumber("LF-4711")
            .seller(new Party("Ökostrom & Wärme GmbH", linz(), "ATU12345678"))
            .buyer(new Party("Bundesministerium für Öffentliches", linz(), "ATU87654321"))
            .addLine(
                new InvoiceLine(
                    "1",
                    "Beratungsleistung März",
                    new BigDecimal("2"),
                    "HUR",
                    new BigDecimal("100.00"),
                    VatRate.STANDARD_20))
            .paymentMeans(new PaymentMeans(new Iban("AT611904300234573201"), "BKAUATWW"));
    customise.accept(builder);
    return builder.build();
  }
}
