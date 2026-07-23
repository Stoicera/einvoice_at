package com.stoicera.einvoice.core.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class VatRateTest {

  @Test
  void austrianRatesAreComplete() {
    assertThat(VatRate.austrianRates())
        .containsExactly(
            VatRate.STANDARD_20,
            VatRate.REDUCED_13,
            VatRate.REDUCED_10,
            VatRate.ZERO,
            VatRate.REVERSE_CHARGE,
            VatRate.EXEMPT);
    assertThat(VatRate.STANDARD_20.category().code()).isEqualTo("S");
    assertThat(VatRate.REDUCED_13.category().code()).isEqualTo("S");
    assertThat(VatRate.ZERO.category().code()).isEqualTo("Z");
    assertThat(VatRate.REVERSE_CHARGE.category().code()).isEqualTo("AE");
    assertThat(VatRate.EXEMPT.category().code()).isEqualTo("E");
  }

  @Test
  void taxOnRoundsCommercially() {
    assertThat(VatRate.STANDARD_20.taxOn(Money.of("99.99", Money.EUR)))
        .isEqualTo(Money.of("20.00", Money.EUR)); // 19.998 -> 20.00
    assertThat(VatRate.REDUCED_13.taxOn(Money.of("0.10", Money.EUR)))
        .isEqualTo(Money.of("0.01", Money.EUR)); // 0.013 -> 0.01
    assertThat(VatRate.REDUCED_10.taxOn(Money.of("0.05", Money.EUR)))
        .isEqualTo(Money.of("0.01", Money.EUR)); // 0.005 -> 0.01 (HALF_UP)
  }

  @Test
  void nonPositiveRateCategoriesYieldZeroTax() {
    Money base = Money.of("1234.56", Money.EUR);
    assertThat(VatRate.ZERO.taxOn(base)).isEqualTo(Money.zero(Money.EUR));
    assertThat(VatRate.REVERSE_CHARGE.taxOn(base)).isEqualTo(Money.zero(Money.EUR));
    assertThat(VatRate.EXEMPT.taxOn(base)).isEqualTo(Money.zero(Money.EUR));
  }

  @Test
  void percentageIsNormalizedToScaleTwo() {
    assertThat(new VatRate(VatCategory.STANDARD, new BigDecimal("20")))
        .isEqualTo(VatRate.STANDARD_20);
  }

  @Test
  void fractionalAndBoundaryPercentagesAreAccepted() {
    assertThat(new VatRate(VatCategory.STANDARD, new BigDecimal("2.5")).percentage())
        .isEqualByComparingTo("2.5");
    assertThat(new VatRate(VatCategory.STANDARD, new BigDecimal("100")).percentage())
        .isEqualByComparingTo("100");
  }

  @Test
  void percentageAboveOneHundredIsRejected() {
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, new BigDecimal("100.01")))
        .isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void rejectsInvalidRates() {
    assertThatThrownBy(() -> new VatRate(null, BigDecimal.TEN))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, null))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, new BigDecimal("-1")))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, new BigDecimal("101")))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, new BigDecimal("20.005")))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, BigDecimal.ZERO))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> new VatRate(VatCategory.REVERSE_CHARGE, BigDecimal.TEN))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("0");
  }

  @Test
  void scaleViolationMessageStatesTheScaleNumberNotTheRawValue() {
    // Never toPlainString() an unvalidated value: state the scale number instead (see Money,
    // InvoiceLine).
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, new BigDecimal("20.00005")))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("VAT percentage scale 5 exceeds scale 2");
  }

  @Test
  void outOfRangeMessageDoesNotEchoTheValue() {
    assertThatThrownBy(() -> new VatRate(VatCategory.STANDARD, new BigDecimal("100.01")))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("VAT percentage out of range [0, 100]");
  }

  @Test
  void sortsByCategoryThenPercentageDescending() {
    assertThat(
            java.util.stream.Stream.of(
                    VatRate.ZERO, VatRate.REDUCED_10, VatRate.STANDARD_20, VatRate.REDUCED_13)
                .sorted()
                .toList())
        .containsExactly(VatRate.STANDARD_20, VatRate.REDUCED_13, VatRate.REDUCED_10, VatRate.ZERO);
  }
}
