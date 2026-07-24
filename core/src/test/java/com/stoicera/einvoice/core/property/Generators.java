package com.stoicera.einvoice.core.property;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/** Shared arbitraries for the canonical-model property tests. */
final class Generators {

  private static final Party SELLER =
      new Party(
          "Stoicera Software Group",
          new Address("Hauptplatz 1", "Linz", "4020", "AT"),
          "ATU12345678");
  private static final Party BUYER =
      new Party("Bund", new Address("Ballhausplatz 2", "Wien", "1010", "AT"), "ATU99999999");

  // All five have 2 fraction digits — Money.SCALE is fixed at 2 by design; non-2-digit
  // currencies like JPY are out of scope until the model supports them.
  private static final List<Currency> CURRENCIES =
      List.of(
          Currency.getInstance("EUR"),
          Currency.getInstance("USD"),
          Currency.getInstance("CHF"),
          Currency.getInstance("GBP"),
          Currency.getInstance("SEK"));

  private Generators() {}

  static Arbitrary<Currency> currencies() {
    return Arbitraries.of(CURRENCIES);
  }

  static Arbitrary<BigDecimal> moneyAmounts() {
    return Arbitraries.bigDecimals()
        .between(new BigDecimal("-999999.99"), new BigDecimal("999999.99"))
        .ofScale(2);
  }

  static Arbitrary<BigDecimal> quantities() {
    return Arbitraries.bigDecimals()
        .between(new BigDecimal("-9999.9999"), new BigDecimal("9999.9999"))
        .ofScale(4)
        .filter(q -> q.signum() != 0);
  }

  static Arbitrary<BigDecimal> unitPrices() {
    return Arbitraries.bigDecimals()
        .between(BigDecimal.ZERO, new BigDecimal("99999.9999"))
        .ofScale(4);
  }

  static Arbitrary<VatRate> austrianVatRates() {
    return Arbitraries.of(VatRate.austrianRates());
  }

  static Arbitrary<InvoiceLine> lines() {
    return Combinators.combine(quantities(), unitPrices(), austrianVatRates())
        .as(
            (qty, price, rate) ->
                new InvoiceLine("PLACEHOLDER", "Property line", qty, "C62", price, rate));
  }

  static Arbitrary<LocalDate> deliveryDates() {
    return Arbitraries.integers().between(-365, 365).map(LocalDate.of(2026, 7, 23)::plusDays);
  }

  static Arbitrary<ServicePeriod> servicePeriods() {
    return Combinators.combine(deliveryDates(), Arbitraries.integers().between(0, 90))
        .as((from, spanDays) -> new ServicePeriod(from, from.plusDays(spanDays)));
  }

  /**
   * Delivery date and service period are mutually exclusive on {@link Invoice} (§ 11 Abs 1 Z 4
   * UStG); this arbitrary picks one of the three legal arms — neither, a delivery date, or a
   * service period — rather than generating the two independently, which could accidentally produce
   * the illegal "both present" combination.
   */
  static Arbitrary<DeliveryArm> deliveryArms() {
    Arbitrary<DeliveryArm> none =
        Arbitraries.just(new DeliveryArm(Optional.empty(), Optional.empty()));
    Arbitrary<DeliveryArm> dateOnly =
        deliveryDates().map(date -> new DeliveryArm(Optional.of(date), Optional.empty()));
    Arbitrary<DeliveryArm> periodOnly =
        servicePeriods().map(period -> new DeliveryArm(Optional.empty(), Optional.of(period)));
    return Arbitraries.oneOf(none, dateOnly, periodOnly);
  }

  static Arbitrary<String> emails() {
    Arbitrary<String> token =
        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    Arbitrary<String> tld = Arbitraries.of("at", "com", "org", "eu");
    return Combinators.combine(token, token, tld)
        .as((local, domain, t) -> local + "@" + domain + "." + t);
  }

  /** Absent or present arm for {@link Party#email()}, null-in-the-wire-sense included. */
  static Arbitrary<Optional<String>> optionalEmails() {
    Arbitrary<Optional<String>> absent = Arbitraries.just(Optional.empty());
    Arbitrary<Optional<String>> present = emails().map(Optional::of);
    return Arbitraries.oneOf(absent, present);
  }

  static Arbitrary<Invoice> invoices() {
    return Combinators.combine(
            lines().list().ofMinSize(1).ofMaxSize(40),
            Arbitraries.of(InvoiceTypeCode.values()),
            currencies(),
            deliveryArms(),
            optionalEmails(),
            optionalEmails())
        .as(
            (lineList, type, currency, delivery, sellerEmail, buyerEmail) -> {
              // Keep generated invoices payable-non-negative (BT-3 invariant): compute the
              // oracle payable (net + per-rate tax, plain BigDecimal) and flip every quantity
              // if it would be negative. Flipping on the net sign alone is NOT enough — an
              // invoice with net 0 but negative tax (e.g. +100 @ 0 % and -100 @ 20 %) has
              // negative payable. HALF_UP rounds half away from zero, so negating every
              // quantity negates each rounded line net — and therefore each category taxable,
              // each category tax, and the payable — exactly.
              Map<VatRate, BigDecimal> taxableByRate = new TreeMap<>();
              for (InvoiceLine l : lineList) {
                taxableByRate.merge(
                    l.vatRate(),
                    l.quantity().multiply(l.unitPrice()).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal::add);
              }
              BigDecimal payable =
                  taxableByRate.entrySet().stream()
                      .map(
                          e ->
                              e.getValue()
                                  .add(
                                      e.getValue()
                                          .multiply(e.getKey().percentage())
                                          .movePointLeft(2)
                                          .setScale(2, RoundingMode.HALF_UP)))
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
              List<InvoiceLine> effective =
                  payable.signum() < 0
                      ? lineList.stream()
                          .map(
                              l ->
                                  new InvoiceLine(
                                      l.id(),
                                      l.description(),
                                      l.quantity().negate(),
                                      l.unitCode(),
                                      l.unitPrice(),
                                      l.vatRate()))
                          .toList()
                      : lineList;

              Party seller =
                  new Party(SELLER.name(), SELLER.address(), SELLER.vatId(), sellerEmail);
              Party buyer = new Party(BUYER.name(), BUYER.address(), BUYER.vatId(), buyerEmail);
              Invoice.Builder builder =
                  Invoice.builder()
                      .invoiceNumber("RE-PROP-1")
                      .type(type)
                      .issueDate(LocalDate.of(2026, 7, 23))
                      .currency(currency)
                      .seller(seller)
                      .buyer(buyer)
                      .exemptionReason(
                          VatCategory.EXEMPT,
                          new VatExemptionReason(null, "Kleinunternehmer § 6 Abs 1 Z 27 UStG"));
              delivery.deliveryDate().ifPresent(builder::deliveryDate);
              delivery.servicePeriod().ifPresent(builder::servicePeriod);
              for (int i = 0; i < effective.size(); i++) {
                InvoiceLine l = effective.get(i);
                builder.addLine(
                    new InvoiceLine(
                        String.valueOf(i + 1),
                        l.description(),
                        l.quantity(),
                        l.unitCode(),
                        l.unitPrice(),
                        l.vatRate()));
              }
              return builder.build();
            });
  }

  /** One of the three legal delivery-info arms; see {@link #deliveryArms()}. */
  record DeliveryArm(Optional<LocalDate> deliveryDate, Optional<ServicePeriod> servicePeriod) {}
}
