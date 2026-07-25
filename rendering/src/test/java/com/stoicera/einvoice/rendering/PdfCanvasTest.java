package com.stoicera.einvoice.rendering;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * The layout primitives, tested directly. Word wrapping and page breaking are the two places where
 * a bug produces a document that is technically a PDF and visibly wrong — text running off the page
 * edge, or a table row half-drawn across a break — so they are pinned here rather than inferred
 * from a rendered invoice.
 */
class PdfCanvasTest {

  private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
  private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

  @Test
  void spansTheContentAreaOfAnA4Page() {
    withCanvas(
        canvas -> {
          assertThat(canvas.left()).isEqualTo(PdfCanvas.MARGIN_LEFT);
          // A4 is 595.28 pt wide; the content area sits between the two margins.
          assertThat(canvas.contentWidth()).isCloseTo(483.28f, Offset.offset(0.5f));
          assertThat(canvas.right()).isGreaterThan(canvas.left());
        });
  }

  @Test
  void keepsShortTextOnOneLine() {
    withCanvas(canvas -> assertThat(canvas.wrap("kurz", 9f, 200f)).containsExactly("kurz"));
  }

  /**
   * The column is wide enough for the longest single word, so every break must land on a space and
   * the text must rejoin exactly. (A narrower column would break mid-word instead — that is the
   * next test, and mixing the two would prove neither.)
   */
  @Test
  void wrapsOnSpacesWhenTextExceedsTheWidth() {
    withCanvas(
        canvas -> {
          List<String> lines =
              canvas.wrap("Beratungsleistung für das erste Quartal des Jahres", 9f, 120f);

          assertThat(lines).hasSizeGreaterThan(1);
          assertThat(String.join(" ", lines))
              .isEqualTo("Beratungsleistung für das erste Quartal des Jahres");
          assertThat(lines)
              .allSatisfy(
                  line ->
                      assertThat(PrintableText.width(line, REGULAR, 9f)).isLessThanOrEqualTo(120f));
        });
  }

  /** A single word wider than the column must be broken, not allowed to overflow the cell. */
  @Test
  void breaksAWordThatIsItselfTooWide() {
    withCanvas(
        canvas -> {
          List<String> lines = canvas.wrap("Donaudampfschifffahrtsgesellschaft", 9f, 40f);

          assertThat(lines).hasSizeGreaterThan(1);
          assertThat(String.join("", lines)).isEqualTo("Donaudampfschifffahrtsgesellschaft");
          assertThat(lines)
              .allSatisfy(
                  line ->
                      assertThat(PrintableText.width(line, REGULAR, 9f)).isLessThanOrEqualTo(40f));
        });
  }

  @Test
  void wrapsAnEmptyStringToASingleEmptyLine() {
    withCanvas(canvas -> assertThat(canvas.wrap("", 9f, 100f)).containsExactly(""));
  }

  @Test
  void doesNotBreakThePageWhileThereIsRoom() {
    withDocument(
        (document, canvas) -> {
          assertThat(canvas.ensureRoom(20f)).isFalse();
          assertThat(document.getNumberOfPages()).isEqualTo(1);
        });
  }

  @Test
  void breaksToANewPageWhenTheContentWouldCrossTheBottomMargin() {
    withDocument(
        (document, canvas) -> {
          canvas.moveDown(700f); // near the bottom of an A4 page

          assertThat(canvas.ensureRoom(60f)).isTrue();
          assertThat(document.getNumberOfPages()).isEqualTo(2);
        });
  }

  private static void withCanvas(Consumer<PdfCanvas> assertion) {
    withDocument((document, canvas) -> assertion.accept(canvas));
  }

  private static void withDocument(DocumentAssertion assertion) {
    try (PDDocument document = new PDDocument()) {
      try (PdfCanvas canvas = new PdfCanvas(document, REGULAR, BOLD)) {
        assertion.accept(document, canvas);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Test document could not be built", e);
    }
  }

  @FunctionalInterface
  private interface DocumentAssertion {
    void accept(PDDocument document, PdfCanvas canvas);
  }
}
