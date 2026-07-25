package com.stoicera.einvoice.mapping.ubl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.mapping.Fixtures;
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import com.stoicera.einvoice.mapping.conversion.ConversionNotes;
import java.math.BigDecimal;
import java.time.LocalDate;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyIdentificationType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PaymentMeansType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PeriodType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxTotalType;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;
import org.junit.jupiter.api.Test;

/**
 * Reading <em>foreign</em> UBL documents — the shapes our own writer never produces, which is
 * precisely what a converter reads all day. See {@code EbInterface61ToInvoiceMapperTest} for why
 * this is a separate concern from the round-trip properties.
 */
class UblToInvoiceMapperTest {

  private static final InvoiceToUblMapper FORWARD = new InvoiceToUblMapper();
  private final UblToInvoiceMapper mapper = new UblToInvoiceMapper();

  private static InvoiceType ublInvoice(Invoice invoice) {
    return ((UblDocument.CommercialInvoice) FORWARD.map(invoice)).document();
  }

  private static CreditNoteType ublCreditNote(Invoice invoice) {
    return ((UblDocument.CreditNote) FORWARD.map(invoice)).document();
  }

  @Test
  void reportsAStatedPayableAmountThatDisagreesWithTheDerivedOne() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getLegalMonetaryTotal().setPayableAmount(new BigDecimal("999.99"));

    CanonicalResult result = mapper.map(ubl);

    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_04))
        .anySatisfy(
            note -> {
              assertThat(note.severity()).isEqualTo(Severity.ERROR);
              assertThat(note.location()).contains("PayableAmount");
              assertThat(note.messageDe()).contains("999.99").doesNotContain("%s");
            });
    assertThat(result.invoice().totals().payableAmount().amount())
        .isEqualByComparingTo(new BigDecimal("405.00"));
  }

  @Test
  void reportsAStatedTaxAmountThatDisagrees() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getTaxTotal().getFirst().setTaxAmount(new BigDecimal("0.01"));

    assertThat(mapper.map(ubl).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_04))
        .anySatisfy(note -> assertThat(note.location()).contains("TaxTotal/cbc:TaxAmount"));
  }

  @Test
  void reportsAStatedLineAmountThatDisagrees() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getInvoiceLine().getFirst().setLineExtensionAmount(new BigDecimal("1.23"));

    assertThat(mapper.map(ubl).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_04))
        .anySatisfy(note -> assertThat(note.messageDe()).contains("1.23"));
  }

  @Test
  void staysSilentWhenTheDocumentStatesNoTotals() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.setLegalMonetaryTotal(null);
    ubl.getTaxTotal().clear();

    // The tax total is gone, so the exemption/breakdown read has nothing to work from either;
    // what matters is that no deviation is invented out of absent data.
    assertThat(mapper.map(ubl).notes())
        .extracting(Finding::ruleId)
        .doesNotContain(ConversionNotes.CONV_04);
  }

  @Test
  void readsADocumentWithoutOptionalBlocks() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getPaymentMeans().clear();
    ubl.getPaymentTerms().clear();
    ubl.getDelivery().clear();
    ubl.getInvoicePeriod().clear();
    ubl.setOrderReference(null);
    ubl.setDueDate((LocalDate) null);
    ubl.getAccountingSupplierParty().getParty().getPartyIdentification().clear();

    Invoice invoice = mapper.map(ubl).invoice();

    assertThat(invoice.paymentMeans()).isNull();
    assertThat(invoice.paymentTerms()).isNull();
    assertThat(invoice.dueDate()).isNull();
    assertThat(invoice.orderReference()).isNull();
    assertThat(invoice.supplierNumber()).isNull();
    assertThat(invoice.deliveryDate()).isEmpty();
    assertThat(invoice.servicePeriod()).isEmpty();
  }

  @Test
  void readsAnInvoicePeriodBackAsAServicePeriod() {
    InvoiceType ubl = ublInvoice(Fixtures.invoiceWithServicePeriod());

    assertThat(mapper.map(ubl).invoice().servicePeriod()).isPresent();
    assertThat(mapper.map(ubl).invoice().deliveryDate()).isEmpty();
  }

  /**
   * A foreign document may carry <em>both</em> a delivery date and an invoice period; the canonical
   * model holds only one, and the one that is dropped must be reported.
   *
   * <p>M4 hostile review, finding F6. § 11 Abs 1 Z 4 UStG makes the two mutually exclusive and
   * {@code core} enforces that, so our own writer never emits both and no round-trip property could
   * ever reach this shape. UBL, however, permits both, and a converter's entire job is documents it
   * did not write. The period was previously discarded in silence — in the one feature whose stated
   * premise is that nothing disappears without being named.
   */
  @Test
  void reportsTheInvoicePeriodItDropsWhenADeliveryDateIsAlsoPresent() {
    InvoiceType ubl = ublInvoice(Fixtures.invoiceWithDeliveryDate());
    PeriodType period = new PeriodType();
    period.setStartDate(LocalDate.of(2026, 7, 1));
    period.setEndDate(LocalDate.of(2026, 7, 31));
    ubl.addInvoicePeriod(period);

    CanonicalResult result = mapper.map(ubl);

    assertThat(result.invoice().deliveryDate()).contains(LocalDate.of(2026, 7, 20));
    assertThat(result.invoice().servicePeriod()).isEmpty();
    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(
            note -> {
              assertThat(note.severity()).isEqualTo(Severity.WARN);
              assertThat(note.location()).contains("cac:InvoicePeriod");
              assertThat(note.messageDe()).contains("Leistungszeitraum").doesNotContain("%s");
              assertThat(note.messageEn()).doesNotContain("%s");
            });
  }

  /**
   * Peppol allows a second {@code cac:TaxTotal} carrying the total in the tax accounting currency.
   * Only the first is read; that the second exists at all must not vanish (finding F6).
   */
  @Test
  void reportsASecondTaxTotalItDoesNotRead() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    TaxTotalType second = new TaxTotalType();
    second.setTaxAmount(new BigDecimal("42.00")).setCurrencyID("CHF");
    ubl.addTaxTotal(second);

    assertThat(mapper.map(ubl).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).contains("cac:TaxTotal"));
  }

  /** A document offering two ways to pay keeps one; the other is a reportable loss (finding F6). */
  @Test
  void reportsASecondPaymentMeansItDoesNotRead() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.addPaymentMeans(new PaymentMeansType());

    assertThat(mapper.map(ubl).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).contains("cac:PaymentMeans"));
  }

  /** Payment terms spread over several notes: only the first survives, and that is said so. */
  @Test
  void reportsFurtherPaymentTermsNotesItDoesNotRead() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getPaymentTerms()
        .getFirst()
        .addNote(
            new oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.NoteType(
                "Skonto 2 % bei Zahlung binnen 10 Tagen"));

    assertThat(mapper.map(ubl).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).contains("cac:PaymentTerms"));
  }

  /**
   * The canonical model holds one seller identifier (BT-29); a party carrying several loses the
   * rest, which is exactly the kind of thing a caller needs told (finding F6).
   */
  @Test
  void reportsFurtherSellerIdentifiersItDoesNotRead() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    PartyIdentificationType second = new PartyIdentificationType();
    second.setID("GLN-9876543210");
    ubl.getAccountingSupplierParty().getParty().addPartyIdentification(second);

    assertThat(mapper.map(ubl).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).contains("cac:PartyIdentification"));
  }

  /** The happy path stays quiet: our own output must produce none of the notes above. */
  @Test
  void reportsNoStructuralLossForADocumentThisPlatformWrote() {
    assertThat(mapper.map(ublInvoice(Fixtures.sampleB2gInvoice())).notes()).isEmpty();
  }

  @Test
  void readsAPartyWithoutAVatIdOrContact() {
    InvoiceType ubl = ublInvoice(Fixtures.invoiceWithoutVatIds());

    Invoice invoice = mapper.map(ubl).invoice();

    assertThat(invoice.seller().vatId()).isNull();
    assertThat(invoice.seller().email()).isEmpty();
    assertThat(invoice.seller().electronicAddress()).isEmpty();
  }

  @Test
  void readsAPaymentMeansWithoutABic() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getPaymentMeans().getFirst().getPayeeFinancialAccount().setFinancialInstitutionBranch(null);

    assertThat(mapper.map(ubl).invoice().paymentMeans().bic()).isNull();
  }

  /** A payment means with no account at all carries no IBAN, so there is nothing to read. */
  @Test
  void ignoresAPaymentMeansWithoutAnAccount() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getPaymentMeans().getFirst().setPayeeFinancialAccount(null);

    assertThat(mapper.map(ubl).invoice().paymentMeans()).isNull();
  }

  @Test
  void letsCoreRejectADocumentMissingItsSupplierParty() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.setAccountingSupplierParty(null);

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("seller");
  }

  @Test
  void letsCoreRejectADocumentWithNoLines() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getInvoiceLine().clear();

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("at least one line");
  }

  @Test
  void defaultsAnAbsentCurrencyToEuro() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.setDocumentCurrencyCode((String) null);

    assertThat(mapper.map(ubl).invoice().currency().getCurrencyCode()).isEqualTo("EUR");
  }

  /**
   * A currency code that is not ISO 4217 is a domain rejection, not a crash.
   *
   * <p>M4 hostile review, finding F2. {@code cbc:DocumentCurrencyCode} is an unconstrained string
   * in UBL 2.1 — the code list is enforced by Schematron, and this adapter deliberately reads with
   * schema validation off — so the value arrives here exactly as the uploader wrote it. Handing it
   * straight to {@link java.util.Currency#getInstance(String)} made the JDK throw {@code
   * IllegalArgumentException}, which no handler maps, so {@code POST /convert} answered <b>500</b>
   * and logged a stack trace for a request that was simply invalid. Every other bad value on this
   * path ({@code Iban}, {@code VatRate}, a missing party) raises {@link
   * InvariantViolationException} and becomes a 422; the currency was the one place a JDK factory
   * was trusted to behave like a {@code core} invariant.
   */
  @Test
  void rejectsACurrencyCodeThatIsNotIso4217() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.setDocumentCurrencyCode("BOGUS");

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BOGUS")
        .hasMessageContaining("ISO 4217");
  }

  /** The same guard on the credit-note overload, which reads the code through its own path. */
  @Test
  void rejectsACurrencyCodeThatIsNotIso4217OnACreditNote() {
    CreditNoteType ubl = ublCreditNote(Fixtures.reverseChargeCreditNote());
    ubl.setDocumentCurrencyCode("nonsense");

    assertThatThrownBy(() -> mapper.map(ubl)).isInstanceOf(InvariantViolationException.class);
  }

  /**
   * The echo is bounded. A hostile document can carry a megabyte-long "currency code"; the message
   * a caller gets back must not be a megabyte long, and must not carry control characters into a
   * log line. {@code Texts.safeEcho} caps at 64 characters plus an ellipsis.
   */
  @Test
  void boundsTheEchoOfAnAbsurdCurrencyCode() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.setDocumentCurrencyCode("X".repeat(5000) + "\n injected log line");

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .satisfies(
            thrown -> {
              assertThat(thrown.getMessage()).hasSizeLessThan(200).doesNotContain("\n");
            });
  }

  @Test
  void readsACreditNoteBackWithItsDueDateFromThePaymentMeans() {
    CreditNoteType ubl = ublCreditNote(Fixtures.creditNoteWithRefundAccount());

    Invoice invoice = mapper.map(ubl).invoice();

    assertThat(invoice.type()).isEqualTo(InvoiceTypeCode.CREDIT_NOTE);
    assertThat(invoice.dueDate())
        .isEqualTo(
            ublCreditNote(Fixtures.creditNoteWithRefundAccount())
                .getPaymentMeans()
                .getFirst()
                .getPaymentDueDateValueLocal());
  }

  @Test
  void readsACreditNoteWithoutPaymentMeansAndThereforeWithoutADueDate() {
    CreditNoteType ubl = ublCreditNote(Fixtures.reverseChargeCreditNote());
    ubl.getPaymentMeans().clear();

    assertThat(mapper.map(ubl).invoice().dueDate()).isNull();
  }

  // --- Degenerate documents ---------------------------------------------------------------------
  // A foreign system can omit anything the UBL schema permits it to omit. Each case below strips
  // one
  // more structure than a well-formed document has, and pins that the mapper either reads what is
  // left or lets core reject it — never a NullPointerException from inside the read.

  @Test
  void letsCoreRejectALineWithNoItem() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getInvoiceLine().getFirst().setItem(null);

    assertThatThrownBy(() -> mapper.map(ubl)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectALineWithNoPrice() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getInvoiceLine().getFirst().setPrice(null);

    assertThatThrownBy(() -> mapper.map(ubl)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectALineWithNoQuantity() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getInvoiceLine().getFirst().setInvoicedQuantity((BigDecimal) null);

    assertThatThrownBy(() -> mapper.map(ubl)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectALineWithNoTaxCategory() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getInvoiceLine().getFirst().getItem().getClassifiedTaxCategory().clear();

    assertThatThrownBy(() -> mapper.map(ubl)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectATaxCategoryWithNoPercent() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getInvoiceLine()
        .getFirst()
        .getItem()
        .getClassifiedTaxCategory()
        .getFirst()
        .setPercent((BigDecimal) null);

    assertThatThrownBy(() -> mapper.map(ubl)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectAPartyWithNoPostalAddress() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getAccountingSupplierParty().getParty().setPostalAddress(null);

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("address");
  }

  @Test
  void letsCoreRejectAPartyWithNoLegalEntityName() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getAccountingSupplierParty().getParty().getPartyLegalEntity().clear();

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("name");
  }

  @Test
  void letsCoreRejectAnAddressWithNoCountry() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getAccountingSupplierParty().getParty().getPostalAddress().setCountry(null);

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("country");
  }

  @Test
  void letsCoreRejectADocumentMissingItsCustomerParty() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.setAccountingCustomerParty(null);

    assertThatThrownBy(() -> mapper.map(ubl))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("buyer");
  }

  /** An empty PaymentTerms note list is legal UBL and simply means no terms. */
  @Test
  void readsAPaymentTermsWithoutANote() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getPaymentTerms().getFirst().getNote().clear();

    assertThat(mapper.map(ubl).invoice().paymentTerms()).isNull();
  }

  /** A tax subtotal without a category, and one whose category needs no exemption reason. */
  @Test
  void ignoresTaxSubtotalsThatCarryNoExemptionReason() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    ubl.getTaxTotal().getFirst().getTaxSubtotal().getFirst().setTaxCategory(null);

    assertThat(mapper.map(ubl).invoice().vatBreakdown())
        .allSatisfy(
            entry -> assertThat(entry.rate().category().requiresExemptionReason()).isFalse());
  }

  @Test
  void ignoresAnExemptionCategoryThatCarriesNeitherCodeNorText() {
    InvoiceType ubl = ublInvoice(Fixtures.sampleB2gInvoice());
    var category = ubl.getTaxTotal().getFirst().getTaxSubtotal().getFirst().getTaxCategory();
    category.setID("AE");
    category.setTaxExemptionReasonCode((String) null);
    category.getTaxExemptionReason().clear();

    // No reason to record, and the lines still carry category S, so core derives an S breakdown.
    assertThat(mapper.map(ubl).invoice().vatBreakdown()).isNotEmpty();
  }
}
