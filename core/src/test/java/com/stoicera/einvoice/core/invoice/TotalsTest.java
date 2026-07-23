package com.stoicera.einvoice.core.invoice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import org.junit.jupiter.api.Test;

class TotalsTest {

  private static final Money NET = Money.of("100.00", Money.EUR);
  private static final Money TAX = Money.of("20.00", Money.EUR);
  private static final Money GROSS = Money.of("120.00", Money.EUR);

  @Test
  void rejectsNullComponents() {
    assertThatThrownBy(() -> new Totals(null, TAX, GROSS, GROSS))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("null");
    assertThatThrownBy(() -> new Totals(NET, null, GROSS, GROSS))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("null");
    assertThatThrownBy(() -> new Totals(NET, TAX, null, GROSS))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("null");
    assertThatThrownBy(() -> new Totals(NET, TAX, GROSS, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("null");
  }

  @Test
  void rejectsGrossNotEqualToNetPlusTax() {
    assertThatThrownBy(
            () ->
                new Totals(NET, TAX, Money.of("121.00", Money.EUR), Money.of("121.00", Money.EUR)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Gross total");
  }

  @Test
  void rejectsPayableNotEqualToGross() {
    assertThatThrownBy(() -> new Totals(NET, TAX, GROSS, Money.of("119.00", Money.EUR)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Payable amount");
  }
}
