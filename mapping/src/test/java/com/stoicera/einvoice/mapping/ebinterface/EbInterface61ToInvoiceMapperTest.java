package com.stoicera.einvoice.mapping.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helger.ebinterface.v61.Ebi61CountryType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61UnitPriceType;
import com.helger.ebinterface.v61.Ebi61UnitType;
import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.mapping.Fixtures;
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import com.stoicera.einvoice.mapping.conversion.ConversionNotes;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Reading <em>foreign</em> ebInterface documents — the case the round-trip property suite cannot
 * reach, because it only ever feeds this mapper documents our own writer produced.
 *
 * <p>That distinction is the point of this class. A round trip proves the pair of mappers agree
 * with each other; it proves nothing about a document written by somebody else's system, which is
 * exactly what a converter spends its life reading. The cases below are all shapes a real foreign
 * document takes and our own writer never produces.
 */
class EbInterface61ToInvoiceMapperTest {

  private static final InvoiceToEbInterface61Mapper FORWARD = new InvoiceToEbInterface61Mapper();
  private final EbInterface61ToInvoiceMapper mapper = new EbInterface61ToInvoiceMapper();

  /** The headline behaviour: a foreign document's arithmetic is checked, not adopted. */
  @Test
  void reportsAStatedDocumentTotalThatDisagreesWithTheDerivedOne() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setPayableAmount(new BigDecimal("999.99")); // a foreign system's arithmetic, not ours

    CanonicalResult result = mapper.map(ebi);

    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_04))
        .singleElement()
        .satisfies(
            note -> {
              assertThat(note.severity()).isEqualTo(Severity.ERROR);
              assertThat(note.location()).isEqualTo("PayableAmount");
              assertThat(note.messageDe()).contains("999.99").doesNotContain("%s");
              assertThat(note.messageEn()).contains("999.99");
            });
    // The derived value wins — that is the whole point of the canonical model.
    assertThat(result.invoice().totals().payableAmount().amount())
        .isEqualByComparingTo(new BigDecimal("405.00"));
  }

  @Test
  void reportsAStatedGrossTotalThatDisagrees() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setTotalGrossAmount(new BigDecimal("1.00"));

    assertThat(mapper.map(ebi).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_04))
        .anySatisfy(note -> assertThat(note.location()).isEqualTo("TotalGrossAmount"));
  }

  @Test
  void reportsAStatedLineAmountThatDisagreesWithQuantityTimesPrice() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getDetails()
        .getItemList()
        .getFirst()
        .getListLineItem()
        .getFirst()
        .setLineItemAmount(new BigDecimal("123.45"));

    assertThat(mapper.map(ebi).notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_04))
        .anySatisfy(
            note -> {
              assertThat(note.location()).contains("ListLineItem[1]/LineItemAmount");
              assertThat(note.messageDe()).contains("123.45");
            });
  }

  /**
   * A document with no stated totals at all is read without complaint; there is nothing to compare.
   */
  @Test
  void staysSilentWhenTheDocumentStatesNoTotals() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setPayableAmount((BigDecimal) null);
    ebi.setTotalGrossAmount((BigDecimal) null);

    assertThat(mapper.map(ebi).notes())
        .extracting(Finding::ruleId)
        .doesNotContain(ConversionNotes.CONV_04);
  }

  @Test
  void readsTheNoUidConventionBackAsAnAbsentVatIdAndSaysSo() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getBiller().setVATIdentificationNumber("ATU00000000");

    CanonicalResult result = mapper.map(ebi);

    assertThat(result.invoice().seller().vatId()).isNull();
    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_02))
        .anySatisfy(
            note -> {
              assertThat(note.severity()).isEqualTo(Severity.INFO);
              assertThat(note.location()).isEqualTo("Biller/VATIdentificationNumber");
              assertThat(note.messageDe()).contains("ATU00000000").doesNotContain("%s");
            });
  }

  @Test
  void reportsTheCountryDisplayNameBeingDropped() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());

    CanonicalResult result = mapper.map(ebi);

    assertThat(result.invoice().seller().address().countryCode()).isEqualTo("AT");
    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).isEqualTo("Biller/Address/Country"));
  }

  @Test
  void readsAnExemptionCommentBackAsTextAndReportsTheLostCode() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.reverseChargeCreditNote());

    CanonicalResult result = mapper.map(ebi);

    assertThat(result.invoice().vatBreakdown().getFirst().exemptionReason().text())
        .doesNotStartWith("Übergang der Steuerschuld: ");
    assertThat(result.invoice().vatBreakdown().getFirst().exemptionReason().code()).isNull();
    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).isEqualTo("Tax/TaxItem/Comment"));
  }

  /**
   * A document missing a structurally required part is {@code core}'s to reject, not this mapper's.
   * Validation is a separate stage that runs before conversion; duplicating its judgement here
   * would mean two places deciding what a valid invoice is.
   */
  @Test
  void letsCoreRejectADocumentMissingItsBiller() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setBiller(null);

    assertThatThrownBy(() -> mapper.map(ebi))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("seller");
  }

  @Test
  void letsCoreRejectADocumentWithNoLines() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setDetails(null);

    assertThatThrownBy(() -> mapper.map(ebi))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("at least one line");
  }

  @Test
  void defaultsAnAbsentCurrencyToEuro() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setInvoiceCurrency((String) null);

    assertThat(mapper.map(ebi).invoice().currency().getCurrencyCode()).isEqualTo("EUR");
  }

  @Test
  void readsADocumentWithoutOptionalBlocks() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setPaymentMethod(null);
    ebi.setPaymentConditions(null);
    ebi.setDelivery(null);
    ebi.getInvoiceRecipient().setOrderReference(null);
    ebi.getBiller().setInvoiceRecipientsBillerID(null);

    CanonicalResult result = mapper.map(ebi);

    assertThat(result.invoice().paymentMeans()).isNull();
    assertThat(result.invoice().dueDate()).isNull();
    assertThat(result.invoice().paymentTerms()).isNull();
    assertThat(result.invoice().orderReference()).isNull();
    assertThat(result.invoice().supplierNumber()).isNull();
    assertThat(result.invoice().deliveryDate()).isEmpty();
    assertThat(result.invoice().servicePeriod()).isEmpty();
  }

  @Test
  void readsAServicePeriodBack() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.invoiceWithServicePeriod());

    assertThat(mapper.map(ebi).invoice().servicePeriod()).isPresent();
    assertThat(mapper.map(ebi).invoice().deliveryDate()).isEmpty();
  }

  @Test
  void readsACreditMemoBackAsACreditNote() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.reverseChargeCreditNote());

    assertThat(mapper.map(ebi).invoice().type().code()).isEqualTo("381");
  }

  // --- Degenerate documents ---------------------------------------------------------------------
  // Shapes a foreign ebInterface writer can legitimately produce. Each pins that the mapper either
  // reads what is left or lets core reject it — never a NullPointerException from inside the read.

  @Test
  void letsCoreRejectALineWithNoQuantity() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getDetails()
        .getItemList()
        .getFirst()
        .getListLineItem()
        .getFirst()
        .setQuantity((Ebi61UnitType) null);

    assertThatThrownBy(() -> mapper.map(ebi)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectALineWithNoUnitPrice() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getDetails()
        .getItemList()
        .getFirst()
        .getListLineItem()
        .getFirst()
        .setUnitPrice((Ebi61UnitPriceType) null);

    assertThatThrownBy(() -> mapper.map(ebi)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectALineWithNoDescription() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getDetails().getItemList().getFirst().getListLineItem().getFirst().getDescription().clear();

    assertThatThrownBy(() -> mapper.map(ebi)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectALineWithNoTaxItem() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getDetails().getItemList().getFirst().getListLineItem().getFirst().setTaxItem(null);

    assertThatThrownBy(() -> mapper.map(ebi)).isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void letsCoreRejectABillerWithNoAddress() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getBiller().setAddress(null);

    assertThatThrownBy(() -> mapper.map(ebi))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("seller");
  }

  @Test
  void letsCoreRejectADocumentMissingItsRecipient() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setInvoiceRecipient(null);

    assertThatThrownBy(() -> mapper.map(ebi))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("buyer");
  }

  @Test
  void letsCoreRejectAnAddressWithNoCountry() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getBiller().getAddress().setCountry((Ebi61CountryType) null);

    assertThatThrownBy(() -> mapper.map(ebi))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("country");
  }

  /** A Country element with a code but no display text has nothing to lose, so nothing is noted. */
  @Test
  void staysSilentWhenTheCountryCarriesNoDisplayName() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getBiller().getAddress().getCountry().setValue(null);
    ebi.getInvoiceRecipient().getAddress().getCountry().setValue(null);

    assertThat(mapper.map(ebi).notes())
        .noneSatisfy(note -> assertThat(note.location()).contains("Address/Country"));
  }

  /** A payment method with no beneficiary account carries no IBAN, so there is nothing to read. */
  @Test
  void ignoresAPaymentMethodWithoutABeneficiaryAccount() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getPaymentMethod().getUniversalBankTransaction().getBeneficiaryAccount().clear();

    assertThat(mapper.map(ebi).invoice().paymentMeans()).isNull();
  }

  /** A NoPayment credit note has a payment method but no bank transfer at all. */
  @Test
  void ignoresANoPaymentMethod() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.reverseChargeCreditNote());

    assertThat(ebi.getPaymentMethod().getNoPayment()).isNotNull();
    assertThat(mapper.map(ebi).invoice().paymentMeans()).isNull();
  }

  @Test
  void ignoresATaxItemCommentOnACategoryThatNeedsNoExemptionReason() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.getTax().getTaxItem().getFirst().setComment("eine Bemerkung ohne Befreiungsgrund");

    assertThat(mapper.map(ebi).notes())
        .noneSatisfy(note -> assertThat(note.location()).isEqualTo("Tax/TaxItem/Comment"));
  }

  @Test
  void readsADocumentWithNoTaxBlockAtAll() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setTax(null);

    // The breakdown is derived from the lines, so removing the document's own Tax block changes
    // nothing about the resulting invoice — it only removes what there was to cross-check.
    assertThat(mapper.map(ebi).invoice().vatBreakdown()).hasSize(2);
  }
}
