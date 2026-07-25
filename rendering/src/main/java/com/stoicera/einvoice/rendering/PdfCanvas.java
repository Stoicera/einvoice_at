package com.stoicera.einvoice.rendering;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * A small drawing surface over PDFBox: a cursor that flows down an A4 page, breaks to a new page
 * when it runs out of room, and draws sanitised text.
 *
 * <p>PDFBox is a PDF library, not a layout engine — it positions glyphs at coordinates and nothing
 * more. Rather than sprinkle coordinate arithmetic and page-break checks through the invoice
 * template, all of it lives here, so {@link InvoicePdfRenderer} reads as a description of an
 * invoice rather than as a sequence of {@code newLineAtOffset} calls. That separation is the whole
 * reason the template is legible.
 *
 * <p>Not thread-safe and not meant to be: one canvas renders one document.
 */
final class PdfCanvas implements AutoCloseable {

  static final float MARGIN_LEFT = 56f; // ~2 cm
  static final float MARGIN_RIGHT = 56f;
  static final float MARGIN_TOP = 56f;
  static final float MARGIN_BOTTOM = 64f; // extra room for the footer

  private final PDDocument document;
  private final PDFont regular;
  private final PDFont bold;

  private PDPage page;
  private PDPageContentStream content;
  private float y;

  PdfCanvas(PDDocument document, PDFont regular, PDFont bold) {
    this.document = document;
    this.regular = regular;
    this.bold = bold;
    newPage();
  }

  /** The usable width between the margins. */
  float contentWidth() {
    return page.getMediaBox().getWidth() - MARGIN_LEFT - MARGIN_RIGHT;
  }

  float left() {
    return MARGIN_LEFT;
  }

  float right() {
    return page.getMediaBox().getWidth() - MARGIN_RIGHT;
  }

  void moveDown(float points) {
    y -= points;
  }

  /**
   * Breaks to a new page when {@code neededHeight} would not fit above the bottom margin.
   *
   * @return {@code true} when a page break happened, so a caller that draws a table can repeat its
   *     header row
   */
  boolean ensureRoom(float neededHeight) {
    if (y - neededHeight >= MARGIN_BOTTOM) {
      return false;
    }
    closeContent();
    newPage();
    return true;
  }

  void text(String value, float x, float size, boolean emphasised) {
    PDFont font = emphasised ? bold : regular;
    String printable = PrintableText.sanitize(value, font);
    run(
        () -> {
          content.beginText();
          content.setFont(font, size);
          content.newLineAtOffset(x, y);
          content.showText(printable);
          content.endText();
        });
  }

  /** Draws {@code value} so that its right edge sits at {@code rightEdge} — for money columns. */
  void textRightAligned(String value, float rightEdge, float size, boolean emphasised) {
    PDFont font = emphasised ? bold : regular;
    String printable = PrintableText.sanitize(value, font);
    text(printable, rightEdge - PrintableText.width(printable, font, size), size, emphasised);
  }

  /** A horizontal rule across the content width at the current cursor. */
  void rule() {
    run(
        () -> {
          content.setLineWidth(0.5f);
          content.moveTo(left(), y);
          content.lineTo(right(), y);
          content.stroke();
        });
  }

  /**
   * Splits {@code value} into lines that each fit {@code maxWidth}, breaking on spaces where
   * possible and mid-word when a single word is itself too long.
   */
  List<String> wrap(String value, float size, float maxWidth) {
    String printable = PrintableText.sanitize(value, regular);
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    for (String word : printable.split(" ")) {
      String candidate = current.isEmpty() ? word : current + " " + word;
      if (PrintableText.width(candidate, regular, size) <= maxWidth) {
        current = new StringBuilder(candidate);
        continue;
      }
      if (!current.isEmpty()) {
        lines.add(current.toString());
        current = new StringBuilder();
      }
      // A single word wider than the column: break it rather than overflow the cell.
      String remainder = word;
      while (PrintableText.width(remainder, regular, size) > maxWidth && remainder.length() > 1) {
        int fit = 1;
        while (fit < remainder.length()
            && PrintableText.width(remainder.substring(0, fit + 1), regular, size) <= maxWidth) {
          fit++;
        }
        lines.add(remainder.substring(0, fit));
        remainder = remainder.substring(fit);
      }
      current = new StringBuilder(remainder);
    }
    if (!current.isEmpty()) {
      lines.add(current.toString());
    }
    return lines.isEmpty() ? List.of("") : lines;
  }

  /** Stamps "Seite n von m" on every page. Called once, after all content is laid out. */
  void stampFooters(String labelFormat) {
    closeContent();
    for (int index = 0; index < document.getNumberOfPages(); index++) {
      PDPage target = document.getPage(index);
      String label = labelFormat.formatted(index + 1, document.getNumberOfPages());
      String printable = PrintableText.sanitize(label, regular);
      try (PDPageContentStream footer =
          new PDPageContentStream(
              document, target, PDPageContentStream.AppendMode.APPEND, true, true)) {
        footer.beginText();
        footer.setFont(regular, 8f);
        footer.newLineAtOffset(
            target.getMediaBox().getWidth()
                - MARGIN_RIGHT
                - PrintableText.width(printable, regular, 8f),
            MARGIN_BOTTOM / 2f);
        footer.showText(printable);
        footer.endText();
      } catch (IOException e) {
        throw new UncheckedIOException("Could not stamp the page footer", e);
      }
    }
  }

  @Override
  public void close() {
    closeContent();
  }

  private void newPage() {
    page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    y = page.getMediaBox().getHeight() - MARGIN_TOP;
    try {
      content = new PDPageContentStream(document, page);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not open a PDF content stream", e);
    }
  }

  private void closeContent() {
    if (content == null) {
      return;
    }
    try {
      content.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not close the PDF content stream", e);
    }
    content = null;
  }

  /** Runs a PDFBox drawing call, translating its checked IOException into an unchecked one. */
  private void run(IoAction action) {
    try {
      action.run();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write to the PDF content stream", e);
    }
  }

  @FunctionalInterface
  private interface IoAction {
    void run() throws IOException;
  }
}
