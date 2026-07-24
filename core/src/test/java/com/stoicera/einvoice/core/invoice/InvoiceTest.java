package com.stoicera.einvoice.core.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
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

  /** Builder with the common header fields set but no lines — for tests that supply their own. */
  private static Invoice.Builder base() {
    return Invoice.builder()
        .invoiceNumber("RE-2026-001")
        .issueDate(LocalDate.of(2026, 7, 23))
        .seller(SELLER)
        .buyer(BUYER);
  }

  private static Invoice.Builder minimal() {
    return base().addLine(line("1", "2", "100.00", VatRate.STANDARD_20));
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
  void reverseChargeInvoiceDefaultsTheStandardExemptionReason() {
    Invoice invoice =
        Invoice.builder()
            .invoiceNumber("RE-2026-003")
            .issueDate(LocalDate.of(2026, 7, 23))
            .seller(SELLER)
            .buyer(BUYER)
            .addLine(line("1", "1", "100.00", VatRate.REVERSE_CHARGE))
            .build();
    assertThat(invoice.vatBreakdown()).hasSize(1);
    assertThat(invoice.vatBreakdown().getFirst().exemptionReason())
        .isEqualTo(VatExemptionReason.REVERSE_CHARGE);
  }

  @Test
  void exemptInvoiceWithoutReasonIsRejected() {
    assertThatThrownBy(
            () ->
                Invoice.builder()
                    .invoiceNumber("RE-2026-004")
                    .issueDate(LocalDate.of(2026, 7, 23))
                    .seller(SELLER)
                    .buyer(BUYER)
                    .addLine(line("1", "1", "100.00", VatRate.EXEMPT))
                    .build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BR-E-10");
  }

  @Test
  void exemptInvoiceCarriesTheSuppliedReason() {
    VatExemptionReason reason =
        new VatExemptionReason(null, "Kleinunternehmer § 6 Abs 1 Z 27 UStG");
    Invoice invoice =
        Invoice.builder()
            .invoiceNumber("RE-2026-005")
            .issueDate(LocalDate.of(2026, 7, 23))
            .seller(SELLER)
            .buyer(BUYER)
            .exemptionReason(VatCategory.EXEMPT, reason)
            .addLine(line("1", "1", "100.00", VatRate.EXEMPT))
            .build();
    assertThat(invoice.vatBreakdown().getFirst().exemptionReason()).isEqualTo(reason);
  }

  @Test
  void computeTotalsHandlesReverseChargeLinesWithoutReasons() {
    List<InvoiceLine> lines = List.of(line("1", "1", "100.00", VatRate.REVERSE_CHARGE));
    Totals totals = Invoice.computeTotals(lines, Money.EUR);
    assertThat(totals.taxTotal()).isEqualTo(Money.zero(Money.EUR));
    assertThat(totals.netTotal()).isEqualTo(Money.of("100.00", Money.EUR));
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
                    valid.type(),
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
                    valid.type(),
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
  void canonicalConstructorEnforcesInvariantsEvenWhenBuilderIsBypassed() {
    Invoice valid = minimal().build();
    assertThatThrownBy(
            () ->
                new Invoice(
                    null,
                    valid.type(),
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
                    valid.totals()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("number");
    assertThatThrownBy(
            () ->
                new Invoice(
                    valid.invoiceNumber(),
                    valid.type(),
                    valid.issueDate(),
                    valid.dueDate(),
                    null,
                    valid.orderReference(),
                    valid.supplierNumber(),
                    valid.seller(),
                    valid.buyer(),
                    valid.lines(),
                    valid.paymentMeans(),
                    valid.paymentTerms(),
                    valid.vatBreakdown(),
                    valid.totals()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("currency");
    assertThatThrownBy(
            () ->
                new Invoice(
                    valid.invoiceNumber(),
                    valid.type(),
                    valid.issueDate(),
                    valid.dueDate(),
                    valid.currency(),
                    valid.orderReference(),
                    valid.supplierNumber(),
                    valid.seller(),
                    valid.buyer(),
                    null,
                    valid.paymentMeans(),
                    valid.paymentTerms(),
                    valid.vatBreakdown(),
                    valid.totals()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("line");
    assertThatThrownBy(
            () ->
                new Invoice(
                    valid.invoiceNumber(),
                    valid.type(),
                    valid.issueDate(),
                    valid.dueDate(),
                    valid.currency(),
                    valid.orderReference(),
                    valid.supplierNumber(),
                    valid.seller(),
                    valid.buyer(),
                    List.of(),
                    valid.paymentMeans(),
                    valid.paymentTerms(),
                    valid.vatBreakdown(),
                    valid.totals()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("line");
  }

  @Test
  void duplicateLineIdMessageSanitizesControlCharacters() {
    String idWithNewline = "1\n2";
    assertThatThrownBy(
            () ->
                base()
                    .addLine(line(idWithNewline, "1", "1.00", VatRate.STANDARD_20))
                    .addLine(line(idWithNewline, "1", "1.00", VatRate.STANDARD_20))
                    .build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("?")
        .hasMessageNotContaining("\n");
  }

  @Test
  void builderRejectsNullLine() {
    assertThatThrownBy(() -> minimal().addLine(null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("line");
  }

  @Test
  void builderRejectsNullCurrency() {
    assertThatThrownBy(() -> minimal().currency(null).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void buildsOptionalFieldsViaBuilder() {
    PaymentMeans paymentMeans = new PaymentMeans(new Iban("AT611904300234573201"), "GIBAATWWXXX");
    Invoice invoice =
        minimal()
            .orderReference("AUFTRAG-42")
            .supplierNumber("LIEF-7")
            .paymentMeans(paymentMeans)
            .paymentTerms("30 Tage netto")
            .build();
    assertThat(invoice.orderReference()).isEqualTo("AUFTRAG-42");
    assertThat(invoice.supplierNumber()).isEqualTo("LIEF-7");
    assertThat(invoice.paymentMeans()).isEqualTo(paymentMeans);
    assertThat(invoice.paymentTerms()).isEqualTo("30 Tage netto");
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

  @Test
  void totalsAndBreakdownCarryTheInvoiceCurrency() {
    Currency chf = Currency.getInstance("CHF");
    Invoice invoice =
        base()
            .currency(chf)
            .addLine(
                new InvoiceLine(
                    "1",
                    "Beratung",
                    new BigDecimal("2"),
                    "HUR",
                    new BigDecimal("150.00"),
                    VatRate.STANDARD_20))
            .build();
    assertThat(invoice.totals().netTotal().currency()).isEqualTo(chf);
    assertThat(invoice.totals().netTotal()).isEqualTo(Money.of("300.00", chf));
    assertThat(invoice.totals().taxTotal()).isEqualTo(Money.of("60.00", chf));
    assertThat(invoice.vatBreakdown().getFirst().taxableAmount().currency()).isEqualTo(chf);
  }

  @Test
  void typeDefaultsToCommercialInvoice() {
    assertThat(minimal().build().type()).isEqualTo(InvoiceTypeCode.COMMERCIAL_INVOICE);
  }

  @Test
  void creditNoteTypeIsCarried() {
    Invoice creditNote = minimal().type(InvoiceTypeCode.CREDIT_NOTE).build();
    assertThat(creditNote.type()).isEqualTo(InvoiceTypeCode.CREDIT_NOTE);
  }

  @Test
  void whollyNegativeInvoiceIsRejected() {
    assertThatThrownBy(
            () ->
                base()
                    .addLine(
                        new InvoiceLine(
                            "1",
                            "Storno",
                            new BigDecimal("-1"),
                            "C62",
                            new BigDecimal("100.00"),
                            VatRate.STANDARD_20))
                    .build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("must not be negative")
        .hasMessageContaining("381");
  }

  @Test
  void negativeLineWithinPositiveInvoiceIsAllowed() {
    Invoice invoice =
        base()
            .addLine(
                new InvoiceLine(
                    "1",
                    "Leistung",
                    new BigDecimal("1"),
                    "C62",
                    new BigDecimal("100.00"),
                    VatRate.STANDARD_20))
            .addLine(
                new InvoiceLine(
                    "2",
                    "Rabatt",
                    new BigDecimal("-1"),
                    "C62",
                    new BigDecimal("10.00"),
                    VatRate.STANDARD_20))
            .build();
    assertThat(invoice.totals().netTotal()).isEqualTo(Money.of("90.00", Money.EUR));
  }

  @Test
  void invoiceNumberLengthIsCappedAtOneTwentyEightCharacters() {
    String atLimit = "x".repeat(128);
    assertThat(minimal().invoiceNumber(atLimit).build().invoiceNumber()).hasSize(128);
    String overLimit = "x".repeat(129);
    assertThatThrownBy(() -> minimal().invoiceNumber(overLimit).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Invoice number");
  }

  @Test
  void orderReferenceLengthIsCappedAtOneTwentyEightCharactersWhenPresent() {
    String atLimit = "x".repeat(128);
    assertThat(minimal().orderReference(atLimit).build().orderReference()).hasSize(128);
    String overLimit = "x".repeat(129);
    assertThatThrownBy(() -> minimal().orderReference(overLimit).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Order reference");
  }

  @Test
  void supplierNumberLengthIsCappedAtOneTwentyEightCharactersWhenPresent() {
    String atLimit = "x".repeat(128);
    assertThat(minimal().supplierNumber(atLimit).build().supplierNumber()).hasSize(128);
    String overLimit = "x".repeat(129);
    assertThatThrownBy(() -> minimal().supplierNumber(overLimit).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Supplier number");
  }

  @Test
  void paymentTermsLengthIsCappedAtFourThousandNinetySixCharactersWhenPresent() {
    String atLimit = "x".repeat(4096);
    assertThat(minimal().paymentTerms(atLimit).build().paymentTerms()).hasSize(4096);
    String overLimit = "x".repeat(4097);
    assertThatThrownBy(() -> minimal().paymentTerms(overLimit).build())
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Payment terms");
  }

  @Test
  void computeVatBreakdownRejectsNullExemptionReasonsMap() {
    List<InvoiceLine> lines = List.of(line("1", "1", "100.00", VatRate.STANDARD_20));
    assertThatThrownBy(() -> Invoice.computeVatBreakdown(lines, Money.EUR, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Exemption reasons map must not be null");
  }

  @Test
  void constructorRejectsDuplicatedBreakdownEntryForAeCategory() {
    Invoice valid = base().addLine(line("1", "1", "100.00", VatRate.REVERSE_CHARGE)).build();
    VatBreakdownEntry aeEntry = valid.vatBreakdown().getFirst();
    List<VatBreakdownEntry> tampered = List.of(aeEntry, aeEntry);
    assertThatThrownBy(
            () ->
                new Invoice(
                    valid.invoiceNumber(),
                    valid.type(),
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
  void nullTypeIsRejected() {
    Invoice valid = minimal().build();
    assertThatThrownBy(
            () ->
                new Invoice(
                    valid.invoiceNumber(),
                    null,
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
                    valid.totals()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("type");
  }
}
