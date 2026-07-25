package com.stoicera.einvoice.rendering;

import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Makes caller-supplied text safe to draw, and measures it.
 *
 * <h2>Why sanitising is not optional</h2>
 *
 * <p>PDFBox's {@code showText} <strong>throws</strong> on any character the font's encoding cannot
 * represent. The Standard 14 fonts use WinAnsiEncoding, which covers German comfortably — umlauts,
 * ß, the euro sign — and covers, say, Greek or Chinese not at all. Invoice text is caller-supplied:
 * a party name, a line description, payment terms. So a renderer that hands that text straight to
 * PDFBox turns "a customer named Ωμέγα" into a 500, which is precisely the class of reachable crash
 * the M2 hostile review closed for the validator's finding text.
 *
 * <p>Unsupported characters are therefore replaced with {@code ?} rather than dropped: a visible
 * placeholder tells a reader something was there, where silent removal would quietly change a name.
 *
 * <p>The alternative — embedding a full Unicode TrueType font — was not taken (ADR-0008): it adds a
 * font file and its licence to the repository to serve scripts an Austrian invoice does not use,
 * and the Standard 14 fonts are guaranteed present in every PDF viewer without embedding anything.
 */
final class PrintableText {

  /** Stand-in for a character the font cannot represent. */
  private static final char REPLACEMENT = '?';

  private PrintableText() {}

  /**
   * {@code text} with every character the font cannot encode replaced by {@code ?}, and every
   * control character (which PDF text objects cannot carry either) replaced the same way.
   *
   * @param text the caller-supplied text; {@code null} becomes an empty string
   * @param font the font the text will be drawn with
   */
  static String sanitize(String text, PDFont font) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints()
        .forEach(
            codePoint -> {
              String character = new String(Character.toChars(codePoint));
              out.append(isPrintable(character, font) ? character : REPLACEMENT);
            });
    return out.toString();
  }

  private static boolean isPrintable(String character, PDFont font) {
    if (character.codePointAt(0) < 0x20) {
      return false; // control characters: no glyph, and newlines would break the text object
    }
    try {
      font.getStringWidth(character);
      return true;
    } catch (Exception e) {
      // getStringWidth is how PDFBox reports "this font cannot encode that": it throws
      // IllegalArgumentException for an unmappable character and IOException for a broken font
      // program. Either way the character cannot be drawn, which is all this method needs to know.
      return false;
    }
  }

  /** The width of {@code text} in points at {@code fontSize}, for already-sanitised text. */
  static float width(String text, PDFont font, float fontSize) {
    try {
      return font.getStringWidth(text) / 1000f * fontSize;
    } catch (Exception e) {
      // Unreachable for sanitised text — every character was width-measured during sanitising.
      throw new IllegalStateException("Sanitised text could not be measured", e);
    }
  }
}
