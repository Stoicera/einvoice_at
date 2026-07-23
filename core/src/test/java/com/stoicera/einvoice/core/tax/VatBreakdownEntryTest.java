package com.stoicera.einvoice.core.tax;

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
}
