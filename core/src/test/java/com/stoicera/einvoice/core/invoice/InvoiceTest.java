package com.stoicera.einvoice.core.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceTest {

  private static final Party SELLER =
      new Party(
          "Stoicera Software Group",
          new Address("Hauptplatz 1", "Linz", "4020", "AT"),
          "ATU12345678");
  private static final Party BUYER =
      new Party("Bund", new Address("Ballhausplatz 2", "Wien", "1010", "AT"), "ATU99999999");

  private static InvoiceLine line(String id, String qty, String price, VatRate rate) {
    return new InvoiceLine(
        id, "Pos " + id, new BigDecimal(qty), "C62", new BigDecimal(price), rate);
  }

  private static Invoice.Builder minimal() {
    return Invoice.builder()
        .invoiceNumber("RE-2026-001")
        .issueDate(LocalDate.of(2026, 7, 23))
        .seller(SELLER)
        .buyer(BUYER)
        .addLine(line("1", "2", "100.00", VatRate.STANDARD_20));
  }

  @Test
  void derivesBreakdownAndTotalsGroupedByRate() {
    Invoice invoice =
        minimal()
            .addLine(line("2", "1", "50.00", VatRate.STANDARD_20))
            .addLine(line("3", "3", "10.00", VatRate.REDUCED_10))
            .build();

    assertThat(invoice.vatBreakdown())
        .containsExactly(
            new VatBreakdownEntry(
                VatRate.STANDARD_20, Money.of("250.00", Money.EUR), Money.of("50.00", Money.EUR)),
            new VatBreakdownEntry(
                VatRate.REDUCED_10, Money.of("30.00", Money.EUR), Money.of("3.00", Money.EUR)));
    assertThat(invoice.totals())
        .isEqualTo(
            new Totals(
                Money.of("280.00", Money.EUR),
                Money.of("53.00", Money.EUR),
                Money.of("333.00", Money.EUR),
                Money.of("333.00", Money.EUR)));
  }

  @Test
  void taxIsComputedOnTheCategorySum() {
    // Two lines of 0.10 at 13 %: per-line tax would be 0.01 + 0.01 = 0.02;
    // category-sum tax is round(0.20 * 0.13) = round(0.026) = 0.03.
    Invoice invoice =
        minimal()
            .addLine(line("2", "1", "0.10", VatRate.REDUCED_13))
            .addLine(line("3", "1", "0.10", VatRate.REDUCED_13))
            .build();
    assertThat(invoice.vatBreakdown())
        .contains(
            new VatBreakdownEntry(
                VatRate.REDUCED_13, Money.of("0.20", Money.EUR), Money.of("0.03", Money.EUR)));
  }

  @Test
  void reverseChargeYieldsZeroTax() {
    Invoice invoice =
        Invoice.builder()
            .invoiceNumber("RE-2026-002")
            .issueDate(LocalDate.of(2026, 7, 23))
            .seller(SELLER)
            .buyer(BUYER)
            .addLine(line("1", "1", "1000.00", VatRate.REVERSE_CHARGE))
            .build();
    assertThat(invoice.totals().taxTotal()).isEqualTo(Money.zero(Money.EUR));
    assertThat(invoice.totals().payableAmount()).isEqualTo(Money.of("1000.00", Money.EUR));
  }

  @Test
  void constructorRejectsTamperedTotals() {
    Invoice valid = minimal().build();
    Totals tampered =
        new Totals(
            valid.totals().netTotal(),
            valid.totals().taxTotal().plus(Money.of("0.01", Money.EUR)),
            valid.totals().grossTotal().plus(Money.of("0.01", Money.EUR)),
            valid.totals().payableAmount().plus(Money.of("0.01", Money.EUR)));
    assertThatThrownBy(
            () ->
                new Invoice(
                    valid.invoiceNumber(),
                    valid.issueDate(),
                    valid.dueDate(),
                    valid.currency(),
                    valid.orderReference(),
                    valid.supplierNumber(),
                    valid.seller(),
                    valid.buyer(),
                    valid.lines(),
                    valid.paymentMeans(),
                    valid.paymentTerms(),
                    valid.vatBreakdown(),
                    tampered))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("totals");
  }

  @Test
  void constructorRejectsTamperedBreakdown() {
    Invoice valid = minimal().build();
    List<VatBreakdownEntry> tampered =
        List.of(
            new VatBreakdownEntry(
                VatRate.REDUCED_10,
                valid.totals().netTotal(),
                VatRate.REDUCED_10.taxOn(valid.totals().netTotal())));
    assertThatThrownBy(
            () ->
                new Invoice(
                    valid.invoiceNumber(),
                    valid.issueDate(),
                    valid.dueDate(),
                    valid.currency(),
                    valid.orderReference(),
                    valid.supplierNumber(),
                    valid.seller(),
                    valid.buyer(),
                    valid.lines(),
                    valid.paymentMeans(),
                    valid.paymentTerms(),
                    tampered,
                    valid.totals()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("breakdown");
  }

  @Test
  void structuralInvariants() {
    assertThatThrownBy(() -> minimal().invoiceNumber(" ").build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("number");
    assertThatThrownBy(() -> minimal().issueDate(null).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("issue date");
    assertThatThrownBy(() -> minimal().seller(null).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("seller");
    assertThatThrownBy(() -> minimal().buyer(null).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("buyer");
    assertThatThrownBy(
            () ->
                Invoice.builder()
                    .invoiceNumber("RE-1")
                    .issueDate(LocalDate.of(2026, 7, 23))
                    .seller(SELLER)
                    .buyer(BUYER)
                    .build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("line");
    assertThatThrownBy(() -> minimal().dueDate(LocalDate.of(2026, 7, 22)).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("due date");
    assertThatThrownBy(() -> minimal().addLine(line("1", "1", "1.00", VatRate.STANDARD_20)).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("unique");
  }

  @Test
  void dueDateEqualToIssueDateIsAllowed() {
    Invoice invoice = minimal().dueDate(LocalDate.of(2026, 7, 23)).build();
    assertThat(invoice.dueDate()).isEqualTo(invoice.issueDate());
  }

  @Test
  void linesAreImmutable() {
    Invoice invoice = minimal().build();
    assertThatThrownBy(() -> invoice.lines().add(line("9", "1", "1.00", VatRate.STANDARD_20)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void currencyDefaultsToEur() {
    assertThat(minimal().build().currency()).isEqualTo(Money.EUR);
  }
}
