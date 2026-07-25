package com.stoicera.einvoice.rendering;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * What a reader actually sees, asserted by extracting the rendered PDF's text back out.
 *
 * <p>Asserting on extracted text rather than on byte output is deliberate: the bytes are PDFBox's
 * business and change with any version bump, while "the invoice number appears", "the VAT is broken
 * out per rate" and "the amounts are formatted the German way" are the properties that would make a
 * recipient reject the document. Those are what a print view is for, and they are what these tests
 * pin.
 */
class InvoicePdfRendererTest {

  private final InvoicePdfRenderer renderer = new InvoicePdfRenderer();

  @Test
  void producesAValidSinglePagePdf() {
    byte[] pdf = renderer.render(invoice(builder -> {}));

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
        .startsWith("%PDF-");
    withDocument(pdf, document -> assertThat(document.getNumberOfPages()).isEqualTo(1));
  }

  @Test
  void showsTheGermanInvoiceHeadingAndNumber() {
    String text = textOf(renderer.render(invoice(builder -> {})));

    assertThat(text).contains("Rechnung 2026-000123");
    assertThat(text).doesNotContain("Gutschrift");
  }

  @Test
  void titlesACreditNoteAsAGutschrift() {
    String text = textOf(renderer.render(invoice(b -> b.type(InvoiceTypeCode.CREDIT_NOTE))));

    assertThat(text).contains("Gutschrift 2026-000123");
    assertThat(text).contains("Gutschriftsbetrag");
  }

  @Test
  void showsBothPartiesWithTheirAddresses() {
    String text = textOf(renderer.render(invoice(builder -> {})));

    assertThat(text).contains("Ökostrom & Wärme GmbH");
    assertThat(text).contains("Grünmarktgasse 5");
    assertThat(text).contains("Bundesamt für Beschaffung");
    assertThat(text).contains("Ballhausplatz 2");
    assertThat(text).contains("Rechnungsempfänger");
  }

  @Test
  void showsTheGermanMetadataLabelsForEveryFieldPresent() {
    String text = textOf(renderer.render(invoice(b -> b.deliveryDate(LocalDate.of(2026, 6, 30)))));

    assertThat(text).contains("Rechnungsdatum").contains("01.07.2026");
    assertThat(text).contains("Fällig am").contains("31.07.2026");
    assertThat(text).contains("Lieferdatum").contains("30.06.2026");
    assertThat(text).contains("Auftragsreferenz").contains("BBG-2026-4711");
    assertThat(text).contains("Lieferantennummer").contains("LF-4711");
    assertThat(text).contains("UID des Rechnungsstellers").contains("ATU12345678");
  }

  @Test
  void showsAServicePeriodInsteadOfADeliveryDateWhenThatIsWhatTheInvoiceCarries() {
    String text =
        textOf(
            renderer.render(
                invoice(
                    b ->
                        b.servicePeriod(
                            new ServicePeriod(
                                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))))));

    assertThat(text).contains("Leistungszeitraum").contains("01.06.2026").contains("30.06.2026");
    assertThat(text).doesNotContain("Lieferdatum");
  }

  @Test
  void showsTheLineItemTableWithGermanHeadings() {
    String text = textOf(renderer.render(invoice(builder -> {})));

    assertThat(text)
        .contains("Pos.")
        .contains("Bezeichnung")
        .contains("Menge")
        .contains("Einheit")
        .contains("Einzelpreis")
        .contains("USt")
        .contains("Betrag");
    assertThat(text).contains("Beratungsleistung März");
    assertThat(text).contains("HUR");
  }

  /**
   * § 11 UStG requires the tax to be shown per rate, so the breakdown is a table of its own rather
   * than a single total — a legal requirement of the print view, not a design flourish.
   */
  @Test
  void breaksTheVatOutPerRate() {
    String text = textOf(renderer.render(invoice(builder -> {})));

    assertThat(text).contains("Steueraufstellung");
    assertThat(text).contains("Bemessungsgrundlage").contains("Steuerbetrag");
    assertThat(text).contains("20,00 %").contains("10,00 %");
    // 2 × 100.00 at 20 % and 3 × 50.00 at 10 %.
    assertThat(text).contains("200,00").contains("40,00");
    assertThat(text).contains("150,00").contains("15,00");
  }

  @Test
  void showsTotalsWithGermanLabelsAndNumberFormatting() {
    String text = textOf(renderer.render(invoice(builder -> {})));

    assertThat(text).contains("Nettobetrag").contains("350,00 EUR");
    assertThat(text).contains("Umsatzsteuer").contains("55,00 EUR");
    assertThat(text).contains("Zahlbetrag").contains("405,00 EUR");
    // German formatting throughout: comma as the decimal mark, never a point.
    assertThat(text).doesNotContain("405.00");
  }

  @Test
  void showsThePaymentDetailsIncludingAFormattedIban() {
    String text = textOf(renderer.render(invoice(builder -> {})));

    assertThat(text).contains("Zahlungsinformationen");
    assertThat(text).contains("AT61 1904 3002 3457 3201"); // grouped for a human to read
    assertThat(text).contains("BKAUATWW");
    assertThat(text).contains("Zahlbar binnen 30 Tagen");
  }

  @Test
  void omitsThePaymentBlockEntirelyWhenThereIsNothingToSay() {
    Invoice noPayment =
        Invoice.builder()
            .invoiceNumber("2026-000200")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(seller())
            .buyer(buyer())
            .addLine(line("1", "Leistung", "1", "C62", "100.00", VatRate.STANDARD_20))
            .build();

    assertThat(textOf(renderer.render(noPayment))).doesNotContain("Zahlungsinformationen");
  }

  @Test
  void statesTheExemptionReasonForAReverseChargeInvoice() {
    Invoice reverseCharge =
        Invoice.builder()
            .invoiceNumber("2026-000900")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(seller())
            .buyer(buyer())
            .addLine(line("1", "Bauleistung", "1", "C62", "1000.00", VatRate.REVERSE_CHARGE))
            .exemptionReason(
                VatCategory.REVERSE_CHARGE,
                new VatExemptionReason("VATEX-EU-AE", "Übergang der Steuerschuld gemäß § 19 UStG"))
            .build();

    String text = textOf(renderer.render(reverseCharge));

    assertThat(text).contains("Übergang der Steuerschuld");
    // A zero-rate category prints its code rather than a meaningless "0,00 %".
    assertThat(text).contains("AE");
  }

  /** Quantities keep their own scale: 1,5 hours must not print as 1,50. */
  @Test
  void printsQuantitiesAtTheirOwnScale() {
    Invoice fractional =
        Invoice.builder()
            .invoiceNumber("2026-000300")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(seller())
            .buyer(buyer())
            .addLine(line("1", "Beratung", "1.5", "HUR", "100.00", VatRate.STANDARD_20))
            .addLine(line("2", "Pauschale", "2", "C62", "10.00", VatRate.STANDARD_20))
            .build();

    String text = textOf(renderer.render(fractional));

    assertThat(text).contains("1,5");
    assertThat(text).doesNotContain("1,50 ");
  }

  /** Thousands are grouped the German way, so a large invoice stays readable. */
  @Test
  void groupsThousandsWithADot() {
    Invoice large =
        Invoice.builder()
            .invoiceNumber("2026-000400")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(seller())
            .buyer(buyer())
            .addLine(line("1", "Großprojekt", "1", "C62", "1234567.00", VatRate.STANDARD_20))
            .build();

    assertThat(textOf(renderer.render(large))).contains("1.234.567,00");
  }

  /** A long invoice flows onto further pages, and every page is numbered. */
  @Test
  void breaksOntoFurtherPagesAndNumbersThem() {
    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber("2026-000500")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(seller())
            .buyer(buyer());
    for (int i = 1; i <= 60; i++) {
      builder.addLine(
          line(String.valueOf(i), "Position " + i, "1", "C62", "10.00", VatRate.STANDARD_20));
    }

    byte[] pdf = renderer.render(builder.build());

    withDocument(pdf, document -> assertThat(document.getNumberOfPages()).isGreaterThan(1));
    String text = textOf(pdf);
    assertThat(text).contains("Seite 1 von");
    assertThat(text).contains("Seite 2 von");
    // The line-item header repeats on the continuation page rather than leaving orphan rows.
    assertThat(text.split("Bezeichnung", -1)).hasSizeGreaterThan(2);
  }

  /**
   * A caller-supplied name outside WinAnsi must not crash the render. PDFBox throws on an
   * unencodable character, and invoice text comes from outside — so the replacement happens by
   * design, and the rest of the document still renders.
   */
  @Test
  void replacesUnsupportedCharactersInsteadOfFailing() {
    Invoice foreignScript =
        Invoice.builder()
            .invoiceNumber("2026-000600")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(new Party("Ωμέγα ΑΕ", new Address("Οδός 1", "Αθήνα", "10431", "GR"), null))
            .buyer(buyer())
            .addLine(line("1", "Υπηρεσία 服务", "1", "C62", "100.00", VatRate.STANDARD_20))
            .build();

    String text = textOf(renderer.render(foreignScript));

    assertThat(text).contains("?");
    // The document is still a usable invoice: everything representable survived.
    assertThat(text).contains("Rechnung 2026-000600").contains("100,00");
  }

  private static Invoice invoice(Consumer<Invoice.Builder> customise) {
    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber("2026-000123")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .dueDate(LocalDate.of(2026, 7, 31))
            .currency(Money.EUR)
            .orderReference("BBG-2026-4711")
            .supplierNumber("LF-4711")
            .seller(seller())
            .buyer(buyer())
            .addLine(line("1", "Beratungsleistung März", "2", "HUR", "100.00", VatRate.STANDARD_20))
            .addLine(line("2", "Druckwerke", "3", "C62", "50.00", VatRate.REDUCED_10))
            .paymentMeans(new PaymentMeans(new Iban("AT611904300234573201"), "BKAUATWW"))
            .paymentTerms("Zahlbar binnen 30 Tagen netto.");
    customise.accept(builder);
    return builder.build();
  }

  private static Party seller() {
    return new Party(
        "Ökostrom & Wärme GmbH",
        new Address("Grünmarktgasse 5", "Wien", "1010", "AT"),
        "ATU12345678",
        Optional.of("rechnung@oekostrom.example.at"));
  }

  private static Party buyer() {
    return new Party(
        "Bundesamt für Beschaffung",
        new Address("Ballhausplatz 2", "Wien", "1010", "AT"),
        "ATU87654321");
  }

  private static InvoiceLine line(
      String id, String description, String quantity, String unit, String price, VatRate rate) {
    return new InvoiceLine(
        id, description, new BigDecimal(quantity), unit, new BigDecimal(price), rate);
  }

  private static String textOf(byte[] pdf) {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(document);
    } catch (IOException e) {
      throw new UncheckedIOException("Rendered PDF could not be read back", e);
    }
  }

  private static void withDocument(byte[] pdf, Consumer<PDDocument> assertion) {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertion.accept(document);
    } catch (IOException e) {
      throw new UncheckedIOException("Rendered PDF could not be read back", e);
    }
  }
}
