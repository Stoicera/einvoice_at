package com.stoicera.einvoice.core.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import org.junit.jupiter.api.Test;

class VatBreakdownEntryTest {

  private static final Money TAXABLE = Money.of("100.00", Money.EUR);
  private static final Money TAX = Money.of("20.00", Money.EUR);

  @Test
  void rejectsNullComponents() {
    assertThatThrownBy(() -> new VatBreakdownEntry(null, TAXABLE, TAX))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("null");
    assertThatThrownBy(() -> new VatBreakdownEntry(VatRate.STANDARD_20, null, TAX))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("null");
    assertThatThrownBy(() -> new VatBreakdownEntry(VatRate.STANDARD_20, TAXABLE, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("null");
  }

  @Test
  void rejectsTaxAmountNotMatchingRateOnTaxableAmount() {
    assertThatThrownBy(
            () -> new VatBreakdownEntry(VatRate.STANDARD_20, TAXABLE, Money.of("19.00", Money.EUR)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Tax amount");
  }

  @Test
  void reverseChargeEntryRequiresAnExemptionReason() {
    Money base = Money.of("100.00", Money.EUR);
    assertThatThrownBy(
            () -> new VatBreakdownEntry(VatRate.REVERSE_CHARGE, base, Money.zero(Money.EUR), null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BR-AE-10");
  }

  @Test
  void exemptEntryRequiresAnExemptionReason() {
    Money base = Money.of("100.00", Money.EUR);
    assertThatThrownBy(
            () -> new VatBreakdownEntry(VatRate.EXEMPT, base, Money.zero(Money.EUR), null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BR-E-10");
  }

  @Test
  void standardAndZeroRatedEntriesMustNotCarryAnExemptionReason() {
    Money base = Money.of("100.00", Money.EUR);
    assertThatThrownBy(
            () ->
                new VatBreakdownEntry(
                    VatRate.STANDARD_20,
                    base,
                    Money.of("20.00", Money.EUR),
                    VatExemptionReason.REVERSE_CHARGE))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BR-S-10");
    assertThatThrownBy(
            () ->
                new VatBreakdownEntry(
                    VatRate.ZERO, base, Money.zero(Money.EUR), VatExemptionReason.REVERSE_CHARGE))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BR-Z-10");
  }

  @Test
  void reverseChargeAndExemptEntriesWithReasonAreValid() {
    Money base = Money.of("100.00", Money.EUR);
    VatBreakdownEntry ae =
        new VatBreakdownEntry(
            VatRate.REVERSE_CHARGE, base, Money.zero(Money.EUR), VatExemptionReason.REVERSE_CHARGE);
    assertThat(ae.exemptionReason()).isEqualTo(VatExemptionReason.REVERSE_CHARGE);
    VatBreakdownEntry e =
        new VatBreakdownEntry(
            VatRate.EXEMPT,
            base,
            Money.zero(Money.EUR),
            new VatExemptionReason(null, "Kleinunternehmer § 6 Abs 1 Z 27 UStG"));
    assertThat(e.exemptionReason().text()).contains("Kleinunternehmer");
  }
}
