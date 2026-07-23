package com.stoicera.einvoice.core.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Falsifiable arithmetic properties: every derived amount is recomputed by an independent,
 * test-local plain-{@link BigDecimal} oracle and compared to the production value. Earlier
 * revisions asserted production methods against themselves (e.g. {@code entry.taxAmount() ==
 * entry.rate().taxOn(...)}, an invariant the constructor already enforces on the same code path)
 * and could therefore never fail — see finding P1.2. The oracle here never calls {@code Money},
 * {@code VatRate.taxOn} or any other production arithmetic, so a defect in the model's rounding or
 * grouping surfaces as a property failure. Falsifiability is pinned deterministically by {@code
 * VatArithmeticExampleTest}.
 */
class InvoiceArithmeticPropertyTest {

  @Provide
  Arbitrary<Invoice> invoices() {
    return Generators.invoices();
  }

  // ---- independent oracle: plain BigDecimal, no production arithmetic ----

  private static BigDecimal round2(BigDecimal raw) {
    return raw.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal oracleLineNet(InvoiceLine line) {
    return round2(line.quantity().multiply(line.unitPrice()));
  }

  private static Map<VatRate, BigDecimal> oracleTaxableByRate(Invoice invoice) {
    Map<VatRate, BigDecimal> taxable = new TreeMap<>();
    for (InvoiceLine line : invoice.lines()) {
      taxable.merge(line.vatRate(), oracleLineNet(line), BigDecimal::add);
    }
    return taxable;
  }

  private static BigDecimal oracleTaxOn(BigDecimal taxable, VatRate rate) {
    return round2(taxable.multiply(rate.percentage()).movePointLeft(2));
  }

  // ---- arithmetic properties, each compared against the oracle ----

  @Property
  void netTotalMatchesTheOracle(@ForAll("invoices") Invoice invoice) {
    BigDecimal expected =
        invoice.lines().stream()
            .map(InvoiceArithmeticPropertyTest::oracleLineNet)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(invoice.totals().netTotal().amount()).isEqualByComparingTo(expected);
  }

  @Property
  void breakdownMatchesTheOraclePerRate(@ForAll("invoices") Invoice invoice) {
    Map<VatRate, BigDecimal> expected = oracleTaxableByRate(invoice);
    assertThat(invoice.vatBreakdown()).hasSameSizeAs(expected.entrySet());
    for (VatBreakdownEntry entry : invoice.vatBreakdown()) {
      BigDecimal taxable = expected.get(entry.rate());
      assertThat(taxable).isNotNull();
      assertThat(entry.taxableAmount().amount()).isEqualByComparingTo(taxable);
      assertThat(entry.taxAmount().amount())
          .isEqualByComparingTo(oracleTaxOn(taxable, entry.rate()));
    }
  }

  @Property
  void totalsMatchTheOracle(@ForAll("invoices") Invoice invoice) {
    Map<VatRate, BigDecimal> taxable = oracleTaxableByRate(invoice);
    BigDecimal expectedNet = taxable.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal expectedTax =
        taxable.entrySet().stream()
            .map(e -> oracleTaxOn(e.getValue(), e.getKey()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(invoice.totals().netTotal().amount()).isEqualByComparingTo(expectedNet);
    assertThat(invoice.totals().taxTotal().amount()).isEqualByComparingTo(expectedTax);
    assertThat(invoice.totals().grossTotal().amount())
        .isEqualByComparingTo(expectedNet.add(expectedTax));
    assertThat(invoice.totals().payableAmount().amount())
        .isEqualByComparingTo(expectedNet.add(expectedTax));
    assertThat(invoice.totals().payableAmount().amount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
  }

  // ---- structural properties (kept: they check ordering/exemption shape, not arithmetic) ----

  @Property
  void nonPositiveRateCategoriesNeverCarryTax(@ForAll("invoices") Invoice invoice) {
    Stream.of(VatCategory.ZERO_RATED, VatCategory.REVERSE_CHARGE, VatCategory.EXEMPT)
        .flatMap(
            category ->
                invoice.vatBreakdown().stream().filter(e -> e.rate().category() == category))
        .forEach(entry -> assertThat(entry.taxAmount().isZero()).isTrue());
  }

  @Property
  void breakdownHasNoDuplicateRatesAndIsSorted(@ForAll("invoices") Invoice invoice) {
    List<VatBreakdownEntry> breakdown = invoice.vatBreakdown();
    assertThat(breakdown.stream().map(VatBreakdownEntry::rate).distinct().count())
        .isEqualTo(breakdown.size());
    assertThat(breakdown.stream().map(VatBreakdownEntry::rate).toList()).isSorted();
  }
}
