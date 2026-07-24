package com.stoicera.einvoice.core.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Hand-computed Austrian VAT pins: net, rate -> expected tax (kaufmännisches Runden). */
class VatArithmeticExampleTest {

  static Stream<Arguments> examples() {
    return Stream.of(
        // net, rate, expected category tax — computed by hand
        Arguments.of("100.00", VatRate.STANDARD_20, "20.00"),
        Arguments.of("99.99", VatRate.STANDARD_20, "20.00"), // 19.998 -> 20.00
        Arguments.of("0.01", VatRate.STANDARD_20, "0.00"), // 0.002  -> 0.00
        Arguments.of("0.03", VatRate.STANDARD_20, "0.01"), // 0.006  -> 0.01
        Arguments.of("0.13", VatRate.REDUCED_13, "0.02"), // 0.0169 -> 0.02
        Arguments.of("0.50", VatRate.REDUCED_10, "0.05"), // 0.050  -> 0.05 exact
        Arguments.of("0.25", VatRate.REDUCED_10, "0.03"), // 0.025  -> 0.03 (HALF_UP)
        Arguments.of("0.35", VatRate.REDUCED_10, "0.04"), // 0.035  -> 0.04 (HALF_UP)
        Arguments.of("123.45", VatRate.REDUCED_13, "16.05"), // 16.0485 -> 16.05
        Arguments.of("7.77", VatRate.STANDARD_20, "1.55")); // 1.554  -> 1.55
  }

  @ParameterizedTest
  @MethodSource("examples")
  void categoryTaxMatchesTheHandComputedValue(String net, VatRate rate, String expectedTax) {
    assertThat(rate.taxOn(Money.of(net, Money.EUR))).isEqualTo(Money.of(expectedTax, Money.EUR));
  }

  @ParameterizedTest
  @MethodSource("examples")
  void breakdownCarriesTheHandComputedTax(String net, VatRate rate, String expectedTax) {
    List<InvoiceLine> lines =
        List.of(new InvoiceLine("1", "Pin", BigDecimal.ONE, "C62", new BigDecimal(net), rate));
    Totals totals = Invoice.computeTotals(lines, Money.EUR);
    assertThat(totals.taxTotal()).isEqualTo(Money.of(expectedTax, Money.EUR));
  }
}
