package com.stoicera.einvoice.core.invoice;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatRate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical invoice per EN 16931 core (subset for Austrian B2G/B2B).
 *
 * <p>The VAT breakdown and totals are <em>derived</em> from the lines: {@link Builder#build()}
 * computes them, and the canonical constructor re-verifies them, so an arithmetically inconsistent
 * invoice cannot be constructed — not even by calling the constructor directly.
 *
 * <p>{@code orderReference} (Auftragsreferenz) and {@code supplierNumber} (Lieferantennummer) are
 * optional here; the Austrian federal B2G profile requires them via the validation module.
 */
public record Invoice(
    String invoiceNumber,
    LocalDate issueDate,
    LocalDate dueDate,
    Currency currency,
    String orderReference,
    String supplierNumber,
    Party seller,
    Party buyer,
    List<InvoiceLine> lines,
    PaymentMeans paymentMeans,
    String paymentTerms,
    List<VatBreakdownEntry> vatBreakdown,
    Totals totals) {

  public Invoice {
    if (invoiceNumber == null || invoiceNumber.isBlank()) {
      throw new InvariantViolationException("Invoice number must not be blank");
    }
    if (issueDate == null) {
      throw new InvariantViolationException("Invoice issue date must not be null");
    }
    if (currency == null) {
      throw new InvariantViolationException("Invoice currency must not be null");
    }
    if (seller == null) {
      throw new InvariantViolationException("Invoice seller must not be null");
    }
    if (buyer == null) {
      throw new InvariantViolationException("Invoice buyer must not be null");
    }
    if (lines == null || lines.isEmpty()) {
      throw new InvariantViolationException("Invoice must have at least one line");
    }
    lines = List.copyOf(lines);
    if (dueDate != null && dueDate.isBefore(issueDate)) {
      throw new InvariantViolationException(
          "Invoice due date %s must not be before issue date %s".formatted(dueDate, issueDate));
    }
    Set<String> ids = new HashSet<>();
    for (InvoiceLine line : lines) {
      if (!ids.add(line.id())) {
        throw new InvariantViolationException(
            "Line ids must be unique; duplicate id '%s'".formatted(line.id()));
      }
    }
    List<VatBreakdownEntry> expectedBreakdown = computeVatBreakdown(lines, currency);
    if (!expectedBreakdown.equals(vatBreakdown)) {
      throw new InvariantViolationException(
          "VAT breakdown %s does not match the breakdown derived from the lines %s"
              .formatted(vatBreakdown, expectedBreakdown));
    }
    vatBreakdown = List.copyOf(vatBreakdown);
    Totals expectedTotals = computeTotals(lines, currency);
    if (!expectedTotals.equals(totals)) {
      throw new InvariantViolationException(
          "Invoice totals %s do not match the totals derived from the lines %s"
              .formatted(totals, expectedTotals));
    }
  }

  /** Groups line nets by VAT rate and taxes each category sum (EN 16931 BR-CO-17). */
  public static List<VatBreakdownEntry> computeVatBreakdown(
      List<InvoiceLine> lines, Currency currency) {
    TreeMap<VatRate, Money> taxableByRate = new TreeMap<>();
    for (InvoiceLine line : lines) {
      taxableByRate.merge(line.vatRate(), line.netAmount(currency), Money::plus);
    }
    List<VatBreakdownEntry> entries = new ArrayList<>(taxableByRate.size());
    taxableByRate.forEach(
        (rate, taxable) -> entries.add(new VatBreakdownEntry(rate, taxable, rate.taxOn(taxable))));
    return List.copyOf(entries);
  }

  /** Sums rounded line nets (BR-CO-10) and category taxes into document totals (BG-22). */
  public static Totals computeTotals(List<InvoiceLine> lines, Currency currency) {
    Money net = Money.zero(currency);
    for (InvoiceLine line : lines) {
      net = net.plus(line.netAmount(currency));
    }
    Money tax = Money.zero(currency);
    for (VatBreakdownEntry entry : computeVatBreakdown(lines, currency)) {
      tax = tax.plus(entry.taxAmount());
    }
    Money gross = net.plus(tax);
    return new Totals(net, tax, gross, gross);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder; derives the VAT breakdown and totals — callers never supply them. */
  public static final class Builder {

    private String invoiceNumber;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private Currency currency = Money.EUR;
    private String orderReference;
    private String supplierNumber;
    private Party seller;
    private Party buyer;
    private final List<InvoiceLine> lines = new ArrayList<>();
    private PaymentMeans paymentMeans;
    private String paymentTerms;

    private Builder() {}

    public Builder invoiceNumber(String invoiceNumber) {
      this.invoiceNumber = invoiceNumber;
      return this;
    }

    public Builder issueDate(LocalDate issueDate) {
      this.issueDate = issueDate;
      return this;
    }

    public Builder dueDate(LocalDate dueDate) {
      this.dueDate = dueDate;
      return this;
    }

    public Builder currency(Currency currency) {
      this.currency = currency;
      return this;
    }

    public Builder orderReference(String orderReference) {
      this.orderReference = orderReference;
      return this;
    }

    public Builder supplierNumber(String supplierNumber) {
      this.supplierNumber = supplierNumber;
      return this;
    }

    public Builder seller(Party seller) {
      this.seller = seller;
      return this;
    }

    public Builder buyer(Party buyer) {
      this.buyer = buyer;
      return this;
    }

    public Builder addLine(InvoiceLine line) {
      if (line == null) {
        throw new InvariantViolationException("Invoice line must not be null");
      }
      this.lines.add(line);
      return this;
    }

    public Builder paymentMeans(PaymentMeans paymentMeans) {
      this.paymentMeans = paymentMeans;
      return this;
    }

    public Builder paymentTerms(String paymentTerms) {
      this.paymentTerms = paymentTerms;
      return this;
    }

    public Invoice build() {
      if (lines.isEmpty()) {
        throw new InvariantViolationException("Invoice must have at least one line");
      }
      if (currency == null) {
        throw new InvariantViolationException("Invoice currency must not be null");
      }
      return new Invoice(
          invoiceNumber,
          issueDate,
          dueDate,
          currency,
          orderReference,
          supplierNumber,
          seller,
          buyer,
          List.copyOf(lines),
          paymentMeans,
          paymentTerms,
          computeVatBreakdown(lines, currency),
          computeTotals(lines, currency));
    }
  }
}
