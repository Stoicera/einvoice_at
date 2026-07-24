package com.stoicera.einvoice.mapping;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Hand-built canonical invoices for the ebInterface 6.1 mapper tests. The German umlauts in party
 * and address fields are deliberate: they exercise UTF-8 round-tripping through the marshaller.
 */
public final class Fixtures {

  private Fixtures() {}

  /**
   * A fully populated Austrian B2G invoice: two taxed lines (20 % and 10 %), an Auftragsreferenz, a
   * Lieferantennummer, SEPA payment details with a checksum-valid IBAN and BIC, a due date and
   * payment terms — every optional the mapper can carry is present.
   */
  public static Invoice sampleB2gInvoice() {
    return Invoice.builder()
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
                new Address("Grünmarktgasse 5", "Wien", "1010", "AT"),
                "ATU12345678"))
        .buyer(
            new Party(
                "Bundesministerium für Öffentliches",
                new Address("Ballhausplatz 2", "Wien", "1010", "AT"),
                "ATU87654321"))
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
        .paymentTerms("Zahlbar binnen 30 Tagen netto. 2 % Skonto bei Zahlung binnen 10 Tagen.")
        .build();
  }

  /**
   * The leanest invoice the mapper accepts: a single taxed line and none of the optionals
   * (Auftragsreferenz, Lieferantennummer, payment means, due date, payment terms). Exercises the
   * absent-branch of every optional the {@link #sampleB2gInvoice()} populates.
   */
  public static Invoice minimalB2bInvoice() {
    return Invoice.builder()
        .invoiceNumber("2026-000999")
        .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
        .issueDate(LocalDate.of(2026, 7, 24))
        .currency(Money.EUR)
        .seller(
            new Party(
                "Kleinbetrieb Groß e.U.",
                new Address("Hauptstraße 1", "Graz", "8010", "AT"),
                "ATU11111111"))
        .buyer(
            new Party(
                "Handels GmbH", new Address("Ringstraße 9", "Linz", "4020", "AT"), "ATU22222222"))
        .addLine(
            new InvoiceLine(
                "1",
                "Werkstoffe",
                new BigDecimal("10"),
                "KGM",
                new BigDecimal("12.50"),
                VatRate.STANDARD_20))
        .build();
  }

  /**
   * A credit note carrying a reverse-charge line (category AE). The builder supplies the default
   * BR-AE-10 exemption reason; the mapper must echo it into the tax breakdown.
   */
  public static Invoice reverseChargeCreditNote() {
    return Invoice.builder()
        .invoiceNumber("2026-CN-0007")
        .type(InvoiceTypeCode.CREDIT_NOTE)
        .issueDate(LocalDate.of(2026, 7, 10))
        .currency(Money.EUR)
        .seller(
            new Party(
                "Süd Bau GmbH",
                new Address("Mozartweg 3", "Salzburg", "5020", "AT"),
                "ATU33333333"))
        .buyer(
            new Party(
                "Nord Bau AG", new Address("Donaukanal 12", "Wien", "1020", "AT"), "ATU44444444"))
        .addLine(
            new InvoiceLine(
                "1",
                "Bauleistung (Reverse Charge)",
                new BigDecimal("1"),
                "C62",
                new BigDecimal("5000.00"),
                VatRate.REVERSE_CHARGE))
        .build();
  }

  /**
   * The exemption reason (code + text) that {@link #exemptInvoice()} attaches to its EXEMPT line.
   */
  public static VatExemptionReason exemptReason() {
    return new VatExemptionReason("VATEX-EU-G", "Innergemeinschaftliche Lieferung");
  }

  /**
   * The canonical invoice described field-for-field by {@code samples/invoice-b2g-sample.json}
   * (repo root). {@link com.stoicera.einvoice.mapping.json.InvoiceJsonReaderTest} parses that file
   * and asserts record equality against this fixture — keep the two in lockstep by construction if
   * either ever changes.
   */
  public static Invoice jsonSampleB2gInvoice() {
    return Invoice.builder()
        .invoiceNumber("RE-2026-0042")
        .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
        .issueDate(LocalDate.of(2026, 7, 24))
        .dueDate(LocalDate.of(2026, 8, 23))
        .currency(Money.EUR)
        .orderReference("BBG-2026-4711")
        .supplierNumber("L-100234")
        .seller(
            new Party(
                "Stoicera Software GesbR",
                new Address("Hauptplatz 1", "Linz", "4020", "AT"),
                "ATU12345678"))
        .buyer(
            new Party(
                "Bundesbeschaffung GmbH",
                new Address("Lassallestraße 9b", "Wien", "1020", "AT"),
                "ATU87654321"))
        .addLine(
            new InvoiceLine(
                "1",
                "Softwareentwicklung Juli 2026",
                new BigDecimal("80"),
                "HUR",
                new BigDecimal("120.00"),
                VatRate.STANDARD_20))
        .addLine(
            new InvoiceLine(
                "2",
                "Fachliteratur",
                new BigDecimal("3"),
                "C62",
                new BigDecimal("45.50"),
                VatRate.REDUCED_10))
        .paymentMeans(new PaymentMeans(new Iban("AT611904300234573201"), "BKAUATWW"))
        .paymentTerms("Zahlbar innerhalb von 30 Tagen ohne Abzug")
        .build();
  }

  /**
   * An invoice with a tax-exempt line (category E). The mapper must echo both the exemption
   * reason's code and text ({@link #exemptReason()}) into the tax breakdown.
   */
  public static Invoice exemptInvoice() {
    return Invoice.builder()
        .invoiceNumber("2026-EX-0042")
        .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
        .issueDate(LocalDate.of(2026, 7, 12))
        .currency(Money.EUR)
        .seller(
            new Party(
                "Export Öl GmbH",
                new Address("Hafenstraße 7", "Wien", "1200", "AT"),
                "ATU55555555"))
        .buyer(
            new Party(
                "Abnehmer S.r.l.",
                new Address("Via Roma 1", "Bozen", "39100", "IT"),
                "IT12345670017"))
        .exemptionReason(VatCategory.EXEMPT, exemptReason())
        .addLine(
            new InvoiceLine(
                "1",
                "Lieferung (steuerfrei)",
                new BigDecimal("4"),
                "C62",
                new BigDecimal("250.00"),
                VatRate.EXEMPT))
        .build();
  }
}
