package com.stoicera.einvoice.mapping.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helger.ebinterface.v61.Ebi61CountryType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61UnitPriceType;
import com.helger.ebinterface.v61.Ebi61UnitType;
import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
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

  /**
   * An exemption comment this project wrote is read back into its parts — code and text — and no
   * loss is reported, because nothing was lost.
   *
   * <p>M4 hostile review, finding F3a. The reverse mapper used to take the whole comment as free
   * text and note the {@code VATEX} code as unrecoverable, on the stated grounds that "parsing it
   * back out of prose would be guesswork". It is not prose: the forward mapper composes {@code
   * lead-in + category + " | " + code + " | " + text}, a delimited field list of this project's own
   * design, and reading back what we ourselves wrote is not guesswork.
   */
  @Test
  void recoversBothCodeAndTextFromAnExemptionCommentThisProjectWrote() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.reverseChargeCreditNote());

    CanonicalResult result = mapper.map(ebi);

    VatExemptionReason reason = result.invoice().vatBreakdown().getFirst().exemptionReason();
    assertThat(reason.code()).isEqualTo("VATEX-EU-AE");
    assertThat(reason.text()).isEqualTo("Reverse charge");
    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .noneSatisfy(note -> assertThat(note.location()).isEqualTo("Tax/TaxItem/Comment"));
  }

  /**
   * A <em>foreign</em> comment is genuine prose, and stays text — with the loss reported, exactly
   * as before. The structured read above must not turn every free-text remark into a fake code.
   */
  @Test
  void keepsAForeignExemptionCommentAsTextAndReportsTheLostCode() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.reverseChargeCreditNote());
    ebi.getTax()
        .getTaxItem()
        .getFirst()
        .setComment("Steuerschuld geht auf den Leistungsempfänger über, siehe § 19 UStG");

    CanonicalResult result = mapper.map(ebi);

    VatExemptionReason reason = result.invoice().vatBreakdown().getFirst().exemptionReason();
    assertThat(reason.code()).isNull();
    assertThat(reason.text())
        .isEqualTo("Steuerschuld geht auf den Leistungsempfänger über, siehe § 19 UStG");
    assertThat(result.notes())
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).isEqualTo("Tax/TaxItem/Comment"));
  }

  /**
   * The comment must not grow when a document goes out and comes back — M4 hostile review, finding
   * F3a, the defect the missing cross-format round-trip test (F3) would have caught.
   *
   * <p>Because the reverse mapper kept the whole comment as text, and the forward mapper then
   * prefixed the category code again, each ebInterface → canonical → ebInterface trip produced
   * {@code "Steuerbefreiung: E | E | VATEX-EU-G | …"}, then {@code "E | E | E | …"}. Unbounded
   * growth of a persisted field across repeated conversions — silent, and invisible to any
   * same-format property test, since those compare canonical models rather than emitted documents.
   */
  @Test
  void anExemptionCommentIsStableAcrossRepeatedRoundTrips() {
    String first =
        FORWARD.map(Fixtures.exemptInvoice()).getTax().getTaxItem().getFirst().getComment();

    String second =
        FORWARD
            .map(mapper.map(FORWARD.map(Fixtures.exemptInvoice())).invoice())
            .getTax()
            .getTaxItem()
            .getFirst()
            .getComment();
    String third =
        FORWARD
            .map(
                mapper
                    .map(FORWARD.map(mapper.map(FORWARD.map(Fixtures.exemptInvoice())).invoice()))
                    .invoice())
            .getTax()
            .getTaxItem()
            .getFirst()
            .getComment();

    assertThat(second).isEqualTo(first);
    assertThat(third).isEqualTo(first);
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

  /**
   * A currency code that is not ISO 4217 is a domain rejection, not a crash — M4 hostile review,
   * finding F2, the ebInterface half.
   *
   * <p>The ebInterface XSD does restrict {@code InvoiceCurrency} to a code list, but this adapter
   * reads with schema validation off (validation is the validation module's job), so a foreign
   * document's value reaches the mapper unchecked exactly as UBL's does. Both reverse mappers had
   * the same hole and both are closed the same way; see the UBL test's Javadoc for the full
   * rationale.
   */
  @Test
  void rejectsACurrencyCodeThatIsNotIso4217() {
    Ebi61InvoiceType ebi = FORWARD.map(Fixtures.sampleB2gInvoice());
    ebi.setInvoiceCurrency("BOGUS");

    assertThatThrownBy(() -> mapper.map(ebi))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BOGUS")
        .hasMessageContaining("ISO 4217");
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
