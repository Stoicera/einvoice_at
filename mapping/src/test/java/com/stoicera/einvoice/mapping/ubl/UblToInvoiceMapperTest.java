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
