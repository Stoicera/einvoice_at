package com.stoicera.einvoice.core.internal;

/**
 * Internal — not API.
 *
 * <p>Shared helper for embedding untrusted, caller-supplied strings in exception messages without
 * turning those messages into a log-injection or unbounded-memory vector.
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
