package com.stoicera.einvoice.core.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatCategory;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class InvoiceArithmeticProperties {

  @Provide
  Arbitrary<Invoice> invoices() {
    return Generators.invoices();
  }

  @Property
  void netTotalIsTheSumOfRoundedLineNets(@ForAll("invoices") Invoice invoice) {
    assertThat(invoice.totals().netTotal()).isEqualTo(Generators.sumOfLineNets(invoice));
  }

  @Property
  void breakdownTaxablesPartitionTheNetTotal(@ForAll("invoices") Invoice invoice) {
    Money taxableSum =
        invoice.vatBreakdown().stream()
            .map(VatBreakdownEntry::taxableAmount)
            .reduce(Money.zero(invoice.currency()), Money::plus);
    assertThat(taxableSum).isEqualTo(invoice.totals().netTotal());
  }

  @Property
  void everyBreakdownTaxIsTheRateAppliedToItsTaxable(@ForAll("invoices") Invoice invoice) {
    for (VatBreakdownEntry entry : invoice.vatBreakdown()) {
      assertThat(entry.taxAmount()).isEqualTo(entry.rate().taxOn(entry.taxableAmount()));
    }
  }

  @Property
  void grossEqualsNetPlusTaxAndPayableEqualsGross(@ForAll("invoices") Invoice invoice) {
    assertThat(invoice.totals().grossTotal())
        .isEqualTo(invoice.totals().netTotal().plus(invoice.totals().taxTotal()));
    assertThat(invoice.totals().payableAmount()).isEqualTo(invoice.totals().grossTotal());
  }

  @Property
  void taxTotalIsTheSumOfBreakdownTaxes(@ForAll("invoices") Invoice invoice) {
    Money taxSum =
        invoice.vatBreakdown().stream()
            .map(VatBreakdownEntry::taxAmount)
            .reduce(Money.zero(invoice.currency()), Money::plus);
    assertThat(invoice.totals().taxTotal()).isEqualTo(taxSum);
  }

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
