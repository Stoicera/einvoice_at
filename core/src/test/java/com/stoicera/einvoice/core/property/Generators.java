package com.stoicera.einvoice.core.property;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.time.LocalDate;
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

  private Generators() {}

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

  static Arbitrary<Invoice> invoices() {
    return lines()
        .list()
        .ofMinSize(1)
        .ofMaxSize(40)
        .map(
            lineList -> {
              Invoice.Builder builder =
                  Invoice.builder()
                      .invoiceNumber("RE-PROP-1")
                      .issueDate(LocalDate.of(2026, 7, 23))
                      .seller(SELLER)
                      .buyer(BUYER)
                      .exemptionReason(
                          VatCategory.EXEMPT,
                          new VatExemptionReason(null, "Kleinunternehmer § 6 Abs 1 Z 27 UStG"));
              for (int i = 0; i < lineList.size(); i++) {
                InvoiceLine l = lineList.get(i);
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

  static Money sumOfLineNets(Invoice invoice) {
    return invoice.lines().stream()
        .map(l -> l.netAmount(invoice.currency()))
        .reduce(Money.zero(invoice.currency()), Money::plus);
  }
}
