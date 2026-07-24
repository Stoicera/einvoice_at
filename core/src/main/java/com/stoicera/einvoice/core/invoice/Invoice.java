package com.stoicera.einvoice.core.invoice;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.internal.Texts;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical invoice per EN 16931 core (subset for Austrian B2G/B2B).
 *
 * <p>The VAT breakdown and totals are <em>derived</em> from the lines: {@link Builder#build()}
 * computes them, and the canonical constructor re-verifies them, so an arithmetically inconsistent
 * invoice cannot be constructed — not even by calling the constructor directly. Exemption reasons
 * (BT-120/BT-121) are the exception: they are caller-supplied data, not derivable from the lines,
 * so the constructor only structurally validates them (via {@link VatBreakdownEntry}) rather than
 * recomputing them.
 *
 * <p>{@code orderReference} (Auftragsreferenz) and {@code supplierNumber} (Lieferantennummer) are
 * optional here; the Austrian federal B2G profile requires them via the validation module.
 *
 * <p>{@link #type()} (BT-3) carries the EN 16931 document type code — 380 for a commercial invoice,
 * 381 for a credit note — and it, not the sign of the amounts, determines the direction of the
 * document; the canonical constructor additionally requires {@link Totals#payableAmount()} to be
 * non-negative, so a wholly negative document must be represented as a 381 credit note.
 */
public record Invoice(
    String invoiceNumber,
    InvoiceTypeCode type,
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

  /** Defensive DoS bound, not a business rule: free-text reference fields must stay bounded. */
  private static final int MAX_REFERENCE_LENGTH = 128;

  private static final int MAX_PAYMENT_TERMS_LENGTH = 4096;

  /**
   * Defensive DoS bound, not a business rule: a mismatch message must stay bounded even when a
   * caller supplies (or the derivation produces) an arbitrarily long breakdown list.
   */
  private static final int MAX_BREAKDOWN_ECHO_ENTRIES = 3;

  public Invoice {
    if (invoiceNumber == null || invoiceNumber.isBlank()) {
      throw new InvariantViolationException("Invoice number must not be blank");
    }
    requireMaxLength(invoiceNumber, MAX_REFERENCE_LENGTH, "Invoice number");
    if (type == null) {
      throw new InvariantViolationException("Invoice type code (BT-3) must not be null");
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
    requireMaxLength(orderReference, MAX_REFERENCE_LENGTH, "Order reference");
    requireMaxLength(supplierNumber, MAX_REFERENCE_LENGTH, "Supplier number");
    requireMaxLength(paymentTerms, MAX_PAYMENT_TERMS_LENGTH, "Payment terms");
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
            "Line ids must be unique; duplicate id '%s'".formatted(Texts.safeEcho(line.id())));
      }
    }
    Map<VatCategory, VatExemptionReason> suppliedReasons = new EnumMap<>(VatCategory.class);
    if (vatBreakdown != null) {
      for (VatBreakdownEntry entry : vatBreakdown) {
        if (entry.exemptionReason() != null) {
          suppliedReasons.putIfAbsent(entry.rate().category(), entry.exemptionReason());
        }
      }
    }
    List<VatBreakdownEntry> expectedBreakdown =
        computeVatBreakdown(lines, currency, suppliedReasons);
    if (!expectedBreakdown.equals(vatBreakdown)) {
      throw new InvariantViolationException(
          "VAT breakdown %s does not match the breakdown derived from the lines %s"
              .formatted(
                  describeBreakdownForMismatch(vatBreakdown),
                  describeBreakdownForMismatch(expectedBreakdown)));
    }
    vatBreakdown = List.copyOf(vatBreakdown);
    Totals expectedTotals = computeTotals(lines, currency);
    if (!expectedTotals.equals(totals)) {
      throw new InvariantViolationException(
          "Invoice totals %s do not match the totals derived from the lines %s"
              .formatted(totals, expectedTotals));
    }
    if (totals.payableAmount().isNegative()) {
      throw new InvariantViolationException(
          "Payable amount %s must not be negative; represent credits as a type 381 credit note"
              .formatted(totals.payableAmount()));
    }
  }

  /** Groups line nets by VAT rate and taxes each category sum (EN 16931 BR-CO-17). */
  public static List<VatBreakdownEntry> computeVatBreakdown(
      List<InvoiceLine> lines,
      Currency currency,
      Map<VatCategory, VatExemptionReason> exemptionReasons) {
    if (exemptionReasons == null) {
      throw new InvariantViolationException("Exemption reasons map must not be null");
    }
    TreeMap<VatRate, Money> taxableByRate = taxableByRate(lines, currency);
    List<VatBreakdownEntry> entries = new ArrayList<>(taxableByRate.size());
    taxableByRate.forEach(
        (rate, taxable) ->
            entries.add(
                new VatBreakdownEntry(
                    rate, taxable, rate.taxOn(taxable), exemptionReasons.get(rate.category()))));
    return List.copyOf(entries);
  }

  /** Sums rounded line nets (BR-CO-10) and category taxes into document totals (BG-22). */
  public static Totals computeTotals(List<InvoiceLine> lines, Currency currency) {
    Money net = Money.zero(currency);
    for (InvoiceLine line : lines) {
      net = net.plus(line.netAmount(currency));
    }
    Money tax = Money.zero(currency);
    for (Map.Entry<VatRate, Money> entry : taxableByRate(lines, currency).entrySet()) {
      tax = tax.plus(entry.getKey().taxOn(entry.getValue()));
    }
    Money gross = net.plus(tax);
    return new Totals(net, tax, gross, gross);
  }

  private static TreeMap<VatRate, Money> taxableByRate(List<InvoiceLine> lines, Currency currency) {
    TreeMap<VatRate, Money> taxableByRate = new TreeMap<>();
    for (InvoiceLine line : lines) {
      taxableByRate.merge(line.vatRate(), line.netAmount(currency), Money::plus);
    }
    return taxableByRate;
  }

  private static void requireMaxLength(String value, int max, String field) {
    if (value != null && value.length() > max) {
      throw new InvariantViolationException("%s exceeds %d characters".formatted(field, max));
    }
  }

  /**
   * Renders a breakdown list for a mismatch message, bounded to the first {@value
   * #MAX_BREAKDOWN_ECHO_ENTRIES} entries plus a count suffix — a caller-supplied (or derived)
   * breakdown can be arbitrarily long, and the exception message must stay bounded regardless.
   */
  private static String describeBreakdownForMismatch(List<VatBreakdownEntry> entries) {
    if (entries == null) {
      return "null";
    }
    if (entries.size() <= MAX_BREAKDOWN_ECHO_ENTRIES) {
      return entries.toString();
    }
    return "%s … (%d weitere)"
        .formatted(
            entries.subList(0, MAX_BREAKDOWN_ECHO_ENTRIES),
            entries.size() - MAX_BREAKDOWN_ECHO_ENTRIES);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder; derives the VAT breakdown and totals — callers never supply them. */
  public static final class Builder {

    private String invoiceNumber;
    private InvoiceTypeCode type = InvoiceTypeCode.COMMERCIAL_INVOICE;
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
    private final Map<VatCategory, VatExemptionReason> exemptionReasons =
        new EnumMap<>(VatCategory.class);

    private Builder() {}

    public Builder invoiceNumber(String invoiceNumber) {
      this.invoiceNumber = invoiceNumber;
      return this;
    }

    public Builder type(InvoiceTypeCode type) {
      this.type = type;
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

    /**
     * Exemption reason (BT-120/BT-121) for a VAT category present in the lines. Required for
     * category E; category AE defaults to {@link VatExemptionReason#REVERSE_CHARGE}.
     */
    public Builder exemptionReason(VatCategory category, VatExemptionReason reason) {
      if (category == null) {
        throw new InvariantViolationException("VAT category must not be null");
      }
      if (reason == null) {
        throw new InvariantViolationException("Exemption reason must not be null");
      }
      this.exemptionReasons.put(category, reason);
      return this;
    }

    public Invoice build() {
      if (lines.isEmpty()) {
        throw new InvariantViolationException("Invoice must have at least one line");
      }
      if (currency == null) {
        throw new InvariantViolationException("Invoice currency must not be null");
      }
      Map<VatCategory, VatExemptionReason> reasons = new EnumMap<>(exemptionReasons);
      boolean hasReverseCharge =
          lines.stream().anyMatch(l -> l.vatRate().category() == VatCategory.REVERSE_CHARGE);
      if (hasReverseCharge) {
        reasons.putIfAbsent(VatCategory.REVERSE_CHARGE, VatExemptionReason.REVERSE_CHARGE);
      }
      return new Invoice(
          invoiceNumber,
          type,
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
          computeVatBreakdown(lines, currency, reasons),
          computeTotals(lines, currency));
    }
  }
}
