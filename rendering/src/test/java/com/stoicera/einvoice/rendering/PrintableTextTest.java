package com.stoicera.einvoice.rendering;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * The sanitising layer, tested directly rather than only through a rendered document — this is the
 * seam between caller-supplied text and a library that throws on anything it cannot encode.
 */
class PrintableTextTest {

  private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

  @Test
  void passesGermanTextThroughUnchanged() {
    String german = "Ökostrom & Wärme GmbH — Grünmarktgasse 5, Straße, groß, ÄÖÜäöüß";

    assertThat(PrintableText.sanitize(german, HELVETICA)).isEqualTo(german);
  }

  /** The euro sign is in WinAnsi, so a German invoice can print it. Pinned, not assumed. */
  @Test
  void passesTheEuroSignThrough() {
    assertThat(PrintableText.sanitize("1.234,56 €", HELVETICA)).isEqualTo("1.234,56 €");
  }

  @Test
  void replacesCharactersTheFontCannotEncode() {
    assertThat(PrintableText.sanitize("Ωμέγα", HELVETICA)).isEqualTo("?????");
    assertThat(PrintableText.sanitize("服务", HELVETICA)).isEqualTo("??");
  }

  /** An emoji is a surrogate pair; it must count as one replacement, not two. */
  @Test
  void replacesASupplementaryPlaneCharacterOnce() {
    assertThat(PrintableText.sanitize("a😀b", HELVETICA)).isEqualTo("a?b");
  }

  /**
   * Control characters have no glyph and a newline would terminate the PDF text object mid-string,
   * so they are replaced too — the same reasoning as core's bounded-echo helper.
   */
  @Test
  void replacesControlCharacters() {
    assertThat(PrintableText.sanitize("a\nb\tc d", HELVETICA)).isEqualTo("a?b?c d");
  }

  @Test
  void treatsNullAsEmpty() {
    assertThat(PrintableText.sanitize(null, HELVETICA)).isEmpty();
  }

  @Test
  void measuresWidthProportionallyToFontSize() {
    float atNine = PrintableText.width("Rechnung", HELVETICA, 9f);
    float atEighteen = PrintableText.width("Rechnung", HELVETICA, 18f);

    assertThat(atNine).isPositive();
    assertThat(atEighteen).isCloseTo(atNine * 2, Offset.offset(0.01f));
  }

  @Test
  void measuresAnEmptyStringAsZeroWidth() {
    assertThat(PrintableText.width("", HELVETICA, 9f)).isZero();
  }
}
