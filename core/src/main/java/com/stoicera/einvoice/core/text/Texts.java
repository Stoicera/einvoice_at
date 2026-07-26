package com.stoicera.einvoice.core.text;

/**
 * Shared helper for embedding untrusted, caller-supplied strings in exception messages without
 * turning those messages into a log-injection or unbounded-memory vector.
 *
 * <p><strong>This lived in {@code core.internal} until M5.</strong> It was marked "Internal — not
 * API" while {@code core} was its only consumer, and the M1 hostile review left a note to promote
 * it "once {@code app} starts consuming it directly" — because a second module reaching into
 * another module's {@code internal} package is a pattern that spreads. The trigger actually fired
 * one milestone earlier and at a different module than the note predicted: {@code mapping} has
 * imported it since M2 ({@code InvoiceJsonReader}, {@code Currencies}), and M5's {@code ai-assist}
 * makes three. Promoted here rather than re-marked internal a third time — the bounded-echo
 * discipline is a rule every module that formats an untrusted value into a message needs, so it is
 * API by use.
 */
public final class Texts {

  private static final int MAX_ECHO_LENGTH = 64;

  private Texts() {}

  /**
   * Renders {@code value} safely for inclusion in an exception message: control characters
   * (including newlines, used for log injection) are replaced with {@code ?}, and the result is
   * truncated to {@value #MAX_ECHO_LENGTH} characters with a trailing {@code …} so a caller cannot
   * force an unbounded-length message. Returns the literal string {@code "null"} for a null input.
   */
  public static String safeEcho(String value) {
    if (value == null) {
      return "null";
    }
    int limit = Math.min(value.length(), MAX_ECHO_LENGTH);
    StringBuilder out = new StringBuilder(limit + 1);
    for (int i = 0; i < limit; i++) {
      char c = value.charAt(i);
      out.append(Character.isISOControl(c) ? '?' : c);
    }
    if (value.length() > MAX_ECHO_LENGTH) {
      out.append('…');
    }
    return out.toString();
  }
}
