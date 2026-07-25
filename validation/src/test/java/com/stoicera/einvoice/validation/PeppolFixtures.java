package com.stoicera.einvoice.validation;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.ElectronicAddress;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Canonical invoices built to be judged by the official OpenPeppol rule set.
 *
 * <p>They carry more than the ebInterface fixtures do, and every extra field is here because Peppol
 * BIS Billing 3.0 requires it: both parties' electronic addresses (BT-34/BT-49) and a buyer/order
 * reference. The IBAN and BIC are the same canonical ebInterface test values the rest of the repo
 * uses (see {@code samples/README.md} for their provenance) — checksum-valid, not a real account.
 */
final class PeppolFixtures {

  private PeppolFixtures() {}

  /** A fully populated invoice that satisfies the Peppol rule set. */
  static Invoice peppolReadyInvoice() {
    return build(builder -> {});
  }

  /** The same document as a credit note (BT-3 381), which Peppol judges by a separate rule set. */
  static Invoice peppolReadyCreditNote() {
    return build(builder -> builder.type(InvoiceTypeCode.CREDIT_NOTE));
  }

  /**
   * The same invoice with both electronic addresses removed — the one field the canonical model
   * treats as optional and Peppol treats as mandatory. Used to prove the rule set genuinely runs
   * and genuinely rejects.
   */
  static Invoice invoiceWithoutElectronicAddresses() {
    return build(
        builder ->
            builder
                .seller(new Party("Ökostrom & Wärme GmbH", sellerAddress(), "ATU12345678"))
                .buyer(new Party("Bundesamt für Beschaffung", buyerAddress(), "ATU87654321")));
  }

  private static Invoice build(Consumer<Invoice.Builder> customise) {
    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber("2026-000123")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .dueDate(LocalDate.of(2026, 7, 31))
            .currency(Money.EUR)
            .orderReference("BBG-2026-4711")
            .supplierNumber("LF-4711")
            .seller(
                new Party(
                    "Ökostrom & Wärme GmbH",
                    sellerAddress(),
                    "ATU12345678",
                    Optional.of("rechnung@oekostrom.example.at"),
                    Optional.of(new ElectronicAddress("9915", "ATU12345678"))))
            .buyer(
                new Party(
                    "Bundesamt für Beschaffung",
                    buyerAddress(),
                    "ATU87654321",
                    Optional.empty(),
                    Optional.of(new ElectronicAddress("9915", "ATU87654321"))))
            .addLine(
                new InvoiceLine(
                    "1",
                    "Beratungsleistung März",
                    new BigDecimal("2"),
                    "HUR",
                    new BigDecimal("100.00"),
                    VatRate.STANDARD_20))
            .addLine(
                new InvoiceLine(
                    "2",
                    "Druckwerke (ermäßigt)",
                    new BigDecimal("3"),
                    "C62",
                    new BigDecimal("50.00"),
                    VatRate.REDUCED_10))
            .paymentMeans(new PaymentMeans(new Iban("AT611904300234573201"), "BKAUATWW"))
            .paymentTerms("Zahlbar binnen 30 Tagen netto.");
    customise.accept(builder);
    return builder.build();
  }

  private static Address sellerAddress() {
    return new Address("Grünmarktgasse 5", "Wien", "1010", "AT");
  }

  private static Address buyerAddress() {
    return new Address("Ballhausplatz 2", "Wien", "1010", "AT");
  }
}
