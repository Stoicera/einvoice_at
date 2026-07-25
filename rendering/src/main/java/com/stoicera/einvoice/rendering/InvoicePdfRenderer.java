package com.stoicera.einvoice.rendering;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Renders a canonical {@link Invoice} as a German A4 print view.
 *
 * <p>MILESTONES M4's acceptance wording is the design brief: "PDF sieht nach Rechnung aus, nicht
 * nach Debug-Ausgabe." So this is laid out the way an Austrian invoice is laid out — sender block,
 * recipient block, a metadata table, the line items, the VAT breakdown as its own table (§ 11 UStG
 * requires the tax to be shown per rate, not just as a total), then totals and payment details —
 * and every label is German. It is not a dump of the model's fields.
 *
 * <p>It renders the <strong>canonical</strong> invoice, never a format-specific tree. An
 * ebInterface document and the UBL document converted from it are the same invoice, so they must
 * print identically; going through {@code core} is what makes that true by construction rather than
 * by two renderers happening to agree.
 *
 * <p>Stateless and safe to share: each call builds its own document.
 *
 * <h2>Deliberate limits</h2>
 *
 * <ul>
 *   <li><strong>No logo, no colour, no letterhead.</strong> The canonical model carries none of
 *       that, and inventing branding for an invoice would be inventing content.
 *   <li><strong>Standard 14 fonts, WinAnsi.</strong> German is fully covered; text outside that
 *       encoding is replaced character-wise rather than crashing the render — see {@link
 *       PrintableText}.
 *   <li><strong>This is a print view, not a ZUGFeRD/Factur-X hybrid.</strong> No XML is embedded in
 *       the PDF. A hybrid document is a different artefact with its own conformance rules, and
 *       claiming one without meeting them would be worse than not offering it.
 * </ul>
 */
public final class InvoicePdfRenderer {

  private static final float TITLE_SIZE = 16f;
  private static final float HEADING_SIZE = 9.5f;
  private static final float BODY_SIZE = 9f;
  private static final float SMALL_SIZE = 8f;
  private static final float LINE_HEIGHT = 12f;
  private static final float ROW_HEIGHT = 13f;

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * Renders {@code invoice} as a PDF.
   *
   * @param invoice the canonical invoice, never {@code null}
   * @return the PDF bytes
   */
  public byte[] render(Invoice invoice) {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

      try (PdfCanvas canvas = new PdfCanvas(document, regular, bold)) {
        drawSenderLine(canvas, invoice.seller());
        drawRecipient(canvas, invoice.buyer());
        drawTitle(canvas, invoice);
        drawMetadata(canvas, invoice);
        drawLineItems(canvas, invoice);
        drawVatBreakdown(canvas, invoice);
        drawTotals(canvas, invoice);
        drawPaymentDetails(canvas, invoice);
        drawSellerFooter(canvas, invoice.seller());
        canvas.stampFooters("Seite %d von %d");
      }

      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not render the invoice PDF", e);
    }
  }

  /** The one-line sender above the address block, the way a windowed envelope expects it. */
  private static void drawSenderLine(PdfCanvas canvas, Party seller) {
    canvas.text(
        "%s · %s · %s %s"
            .formatted(
                seller.name(),
                seller.address().street(),
                seller.address().postalCode(),
                seller.address().city()),
        canvas.left(),
        SMALL_SIZE,
        false);
    canvas.moveDown(LINE_HEIGHT * 2);
  }

  private static void drawRecipient(PdfCanvas canvas, Party buyer) {
    canvas.text("Rechnungsempfänger", canvas.left(), SMALL_SIZE, true);
    canvas.moveDown(LINE_HEIGHT);
    canvas.text(buyer.name(), canvas.left(), BODY_SIZE, true);
    canvas.moveDown(LINE_HEIGHT);
    canvas.text(buyer.address().street(), canvas.left(), BODY_SIZE, false);
    canvas.moveDown(LINE_HEIGHT);
    canvas.text(
        "%s %s".formatted(buyer.address().postalCode(), buyer.address().city()),
        canvas.left(),
        BODY_SIZE,
        false);
    canvas.moveDown(LINE_HEIGHT);
    canvas.text(buyer.address().countryCode(), canvas.left(), BODY_SIZE, false);
    if (buyer.vatId() != null) {
      canvas.moveDown(LINE_HEIGHT);
      canvas.text("UID: " + buyer.vatId(), canvas.left(), BODY_SIZE, false);
    }
    canvas.moveDown(LINE_HEIGHT * 2.5f);
  }

  private static void drawTitle(PdfCanvas canvas, Invoice invoice) {
    String title =
        invoice.type() == InvoiceTypeCode.CREDIT_NOTE
            ? "Gutschrift " + invoice.invoiceNumber()
            : "Rechnung " + invoice.invoiceNumber();
    canvas.text(title, canvas.left(), TITLE_SIZE, true);
    canvas.moveDown(LINE_HEIGHT * 1.6f);
  }

  /** The metadata block: two columns of label/value pairs, only the ones the invoice carries. */
  private static void drawMetadata(PdfCanvas canvas, Invoice invoice) {
    float labelX = canvas.left();
    float valueX = canvas.left() + 130f;

    metadataRow(canvas, labelX, valueX, "Rechnungsdatum", GERMAN_DATE.format(invoice.issueDate()));
    if (invoice.dueDate() != null) {
      metadataRow(canvas, labelX, valueX, "Fällig am", GERMAN_DATE.format(invoice.dueDate()));
    }
    invoice
        .deliveryDate()
        .ifPresent(
            date -> metadataRow(canvas, labelX, valueX, "Lieferdatum", GERMAN_DATE.format(date)));
    invoice
        .servicePeriod()
        .ifPresent(
            period ->
                metadataRow(
                    canvas,
                    labelX,
                    valueX,
                    "Leistungszeitraum",
                    "%s – %s"
                        .formatted(
                            GERMAN_DATE.format(period.from()), GERMAN_DATE.format(period.to()))));
    if (invoice.orderReference() != null) {
      metadataRow(canvas, labelX, valueX, "Auftragsreferenz", invoice.orderReference());
    }
    if (invoice.supplierNumber() != null) {
      metadataRow(canvas, labelX, valueX, "Lieferantennummer", invoice.supplierNumber());
    }
    if (invoice.seller().vatId() != null) {
      metadataRow(canvas, labelX, valueX, "UID des Rechnungsstellers", invoice.seller().vatId());
    }
    canvas.moveDown(LINE_HEIGHT);
  }

  private static void metadataRow(
      PdfCanvas canvas, float labelX, float valueX, String label, String value) {
    canvas.ensureRoom(LINE_HEIGHT);
    canvas.text(label, labelX, BODY_SIZE, false);
    canvas.text(value, valueX, BODY_SIZE, true);
    canvas.moveDown(LINE_HEIGHT);
  }

  private static void drawLineItems(PdfCanvas canvas, Invoice invoice) {
    Columns columns = Columns.of(canvas);

    canvas.ensureRoom(ROW_HEIGHT * 3);
    drawLineItemHeader(canvas, columns);

    for (InvoiceLine line : invoice.lines()) {
      List<String> descriptionLines =
          canvas.wrap(line.description(), BODY_SIZE, columns.descriptionWidth());
      float rowHeight = ROW_HEIGHT * descriptionLines.size();

      if (canvas.ensureRoom(rowHeight + ROW_HEIGHT)) {
        drawLineItemHeader(canvas, columns);
      }

      canvas.text(line.id(), columns.position(), BODY_SIZE, false);
      canvas.text(descriptionLines.getFirst(), columns.description(), BODY_SIZE, false);
      canvas.textRightAligned(quantity(line.quantity()), columns.quantityRight(), BODY_SIZE, false);
      canvas.text(line.unitCode(), columns.unit(), BODY_SIZE, false);
      canvas.textRightAligned(amount(line.unitPrice()), columns.unitPriceRight(), BODY_SIZE, false);
      canvas.textRightAligned(
          vatLabel(line.vatRate().percentage(), line.vatRate().category().code()),
          columns.vatRight(),
          BODY_SIZE,
          false);
      canvas.textRightAligned(
          amount(line.netAmount(invoice.currency()).amount()),
          columns.amountRight(),
          BODY_SIZE,
          false);
      canvas.moveDown(ROW_HEIGHT);

      // Continuation lines of a wrapped description sit under the description column only.
      for (String continuation : descriptionLines.subList(1, descriptionLines.size())) {
        canvas.text(continuation, columns.description(), BODY_SIZE, false);
        canvas.moveDown(ROW_HEIGHT);
      }
    }

    canvas.rule();
    canvas.moveDown(LINE_HEIGHT);
  }

  private static void drawLineItemHeader(PdfCanvas canvas, Columns columns) {
    canvas.text("Pos.", columns.position(), HEADING_SIZE, true);
    canvas.text("Bezeichnung", columns.description(), HEADING_SIZE, true);
    canvas.textRightAligned("Menge", columns.quantityRight(), HEADING_SIZE, true);
    canvas.text("Einheit", columns.unit(), HEADING_SIZE, true);
    canvas.textRightAligned("Einzelpreis", columns.unitPriceRight(), HEADING_SIZE, true);
    canvas.textRightAligned("USt", columns.vatRight(), HEADING_SIZE, true);
    canvas.textRightAligned("Betrag", columns.amountRight(), HEADING_SIZE, true);
    canvas.moveDown(4f);
    canvas.rule();
    canvas.moveDown(ROW_HEIGHT);
  }

  /**
   * The VAT breakdown as its own table. § 11 UStG requires the tax amount to be shown per rate, so
   * this is a legal requirement of the print view rather than a nicety — and it is where an
   * exemption reason (BT-120/BT-121) is stated, which categories AE and E must carry.
   */
  private static void drawVatBreakdown(PdfCanvas canvas, Invoice invoice) {
    Columns columns = Columns.of(canvas);

    canvas.ensureRoom(ROW_HEIGHT * (invoice.vatBreakdown().size() + 2));
    canvas.text("Steueraufstellung", canvas.left(), HEADING_SIZE, true);
    canvas.moveDown(ROW_HEIGHT);
    canvas.text("Steuersatz", columns.description(), SMALL_SIZE, true);
    canvas.textRightAligned("Bemessungsgrundlage", columns.unitPriceRight(), SMALL_SIZE, true);
    canvas.textRightAligned("Steuerbetrag", columns.amountRight(), SMALL_SIZE, true);
    canvas.moveDown(ROW_HEIGHT);

    for (VatBreakdownEntry entry : invoice.vatBreakdown()) {
      canvas.ensureRoom(ROW_HEIGHT * 2);
      canvas.text(
          vatLabel(entry.rate().percentage(), entry.rate().category().code()),
          columns.description(),
          BODY_SIZE,
          false);
      canvas.textRightAligned(
          money(entry.taxableAmount()), columns.unitPriceRight(), BODY_SIZE, false);
      canvas.textRightAligned(money(entry.taxAmount()), columns.amountRight(), BODY_SIZE, false);
      canvas.moveDown(ROW_HEIGHT);

      if (entry.exemptionReason() != null) {
        String reason =
            entry.exemptionReason().text() != null
                ? entry.exemptionReason().text()
                : entry.exemptionReason().code();
        for (String wrapped :
            canvas.wrap(reason, SMALL_SIZE, columns.amountRight() - columns.description())) {
          canvas.ensureRoom(ROW_HEIGHT);
          canvas.text(wrapped, columns.description(), SMALL_SIZE, false);
          canvas.moveDown(ROW_HEIGHT);
        }
      }
    }
    canvas.moveDown(LINE_HEIGHT * 0.5f);
  }

  private static void drawTotals(PdfCanvas canvas, Invoice invoice) {
    Columns columns = Columns.of(canvas);

    canvas.ensureRoom(ROW_HEIGHT * 4);
    totalRow(canvas, columns, "Nettobetrag", money(invoice.totals().netTotal()), false);
    totalRow(canvas, columns, "Umsatzsteuer", money(invoice.totals().taxTotal()), false);
    canvas.rule();
    canvas.moveDown(ROW_HEIGHT);
    totalRow(
        canvas,
        columns,
        invoice.type() == InvoiceTypeCode.CREDIT_NOTE ? "Gutschriftsbetrag" : "Zahlbetrag",
        money(invoice.totals().payableAmount()),
        true);
    canvas.moveDown(LINE_HEIGHT);
  }

  private static void totalRow(
      PdfCanvas canvas, Columns columns, String label, String value, boolean emphasised) {
    canvas.textRightAligned(label, columns.unitPriceRight(), BODY_SIZE, emphasised);
    canvas.textRightAligned(value, columns.amountRight(), BODY_SIZE, emphasised);
    canvas.moveDown(ROW_HEIGHT);
  }

  private static void drawPaymentDetails(PdfCanvas canvas, Invoice invoice) {
    PaymentMeans means = invoice.paymentMeans();
    if (means == null && invoice.paymentTerms() == null) {
      return;
    }

    canvas.ensureRoom(ROW_HEIGHT * 4);
    canvas.text("Zahlungsinformationen", canvas.left(), HEADING_SIZE, true);
    canvas.moveDown(ROW_HEIGHT);

    if (means != null) {
      canvas.text("IBAN: " + means.iban().formatted(), canvas.left(), BODY_SIZE, false);
      canvas.moveDown(LINE_HEIGHT);
      if (means.bic() != null) {
        canvas.text("BIC: " + means.bic(), canvas.left(), BODY_SIZE, false);
        canvas.moveDown(LINE_HEIGHT);
      }
      canvas.text(
          "Bitte geben Sie bei der Überweisung die Rechnungsnummer "
              + invoice.invoiceNumber()
              + " an.",
          canvas.left(),
          SMALL_SIZE,
          false);
      canvas.moveDown(LINE_HEIGHT);
    }

    if (invoice.paymentTerms() != null) {
      for (String wrapped : canvas.wrap(invoice.paymentTerms(), BODY_SIZE, canvas.contentWidth())) {
        canvas.ensureRoom(LINE_HEIGHT);
        canvas.text(wrapped, canvas.left(), BODY_SIZE, false);
        canvas.moveDown(LINE_HEIGHT);
      }
    }
  }

  /** The seller's own details, where an Austrian invoice puts them: at the bottom. */
  private static void drawSellerFooter(PdfCanvas canvas, Party seller) {
    canvas.ensureRoom(ROW_HEIGHT * 3);
    canvas.moveDown(LINE_HEIGHT);
    canvas.rule();
    canvas.moveDown(LINE_HEIGHT);
    canvas.text(seller.name(), canvas.left(), SMALL_SIZE, true);
    canvas.moveDown(LINE_HEIGHT);
    canvas.text(
        "%s, %s %s, %s"
            .formatted(
                seller.address().street(),
                seller.address().postalCode(),
                seller.address().city(),
                seller.address().countryCode()),
        canvas.left(),
        SMALL_SIZE,
        false);
    if (seller.email().isPresent()) {
      canvas.moveDown(LINE_HEIGHT);
      canvas.text("E-Mail: " + seller.email().get(), canvas.left(), SMALL_SIZE, false);
    }
  }

  /** {@code 20,00 %} — or the bare category code for a zero-rated/exempt/reverse-charge line. */
  private static String vatLabel(BigDecimal percentage, String categoryCode) {
    if (percentage.signum() == 0) {
      return categoryCode;
    }
    return decimals(2).format(percentage) + " %";
  }

  private static String money(Money value) {
    return amount(value.amount()) + " " + value.currency().getCurrencyCode();
  }

  private static String amount(BigDecimal value) {
    return decimals(2).format(value);
  }

  /** Quantities keep their own scale — 1,5 h must not print as 1,50 h. */
  private static String quantity(BigDecimal value) {
    return decimals(Math.max(0, value.stripTrailingZeros().scale())).format(value);
  }

  /** German number formatting: comma as the decimal mark, dot as the thousands separator. */
  private static DecimalFormat decimals(int fractionDigits) {
    DecimalFormat format = new DecimalFormat();
    format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.GERMANY));
    format.setGroupingUsed(true);
    format.setGroupingSize(3);
    format.setMinimumFractionDigits(fractionDigits);
    format.setMaximumFractionDigits(fractionDigits);
    return format;
  }

  /**
   * The line-item table's column geometry, derived from the page width so the table always spans
   * the content area.
   */
  private record Columns(
      float position,
      float description,
      float quantityRight,
      float unit,
      float unitPriceRight,
      float vatRight,
      float amountRight) {

    static Columns of(PdfCanvas canvas) {
      float left = canvas.left();
      float right = canvas.right();
      return new Columns(
          left, left + 28f, left + 268f, left + 276f, left + 372f, left + 412f, right);
    }

    /**
     * The gutter is deliberately generous: the quantity column is right-aligned, so a description
     * that runs to its full width would sit flush against a number and read as one string. Measured
     * on the sample invoice, where a 53-character description was landing within a hair of "300".
     */
    float descriptionWidth() {
      return quantityRight() - description() - 16f;
    }
  }
}
