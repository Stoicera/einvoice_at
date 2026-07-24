package com.stoicera.einvoice.validation.internal;

/**
 * Caps foreign, attacker-influenced text to a safe length before it is handed to a {@code Finding}.
 *
 * <p>Xerces bakes the offending element/attribute value into its {@code cvc-*} diagnostic text, and
 * an SVRL {@code <value-of>} can likewise pull document content into an assert message; both then
 * flow into finding messages and locations. Core's {@code Finding} enforces hard DoS caps (4096
 * chars for a message, 1024 for a location) and <em>throws</em> when they are exceeded, so a single
 * over-long document value would otherwise crash {@code validate()} and break its never-throws
 * contract. Every seam where such text enters a {@code Finding} routes it through {@link
 * #cap(String, int)} first, with a limit chosen comfortably below the corresponding {@code Finding}
 * cap (leaving room for our own German lead-in prefix on XSD messages).
 *
 * <p>A truncated result ends with a single {@code …} (U+2026) marker so the report reader can see
 * the text was shortened; the marker is counted within the limit, so the returned string is never
 * longer than {@code maxChars}.
 */
public final class BoundedText {

  /** The horizontal-ellipsis marker appended to a truncated string. */
  static final char ELLIPSIS = '…';

  /**
   * Safe cap for a foreign-text finding message detail: well under core {@code Finding}'s
   * 4096-character message cap, leaving ample room for our own German lead-in prefix on XSD
   * messages.
   */
  public static final int MAX_MESSAGE_DETAIL = 2000;

  /**
   * Safe cap for a foreign-text finding location (an XPath or parser source name): well under core
   * {@code Finding}'s 1024-character location cap.
   */
  public static final int MAX_LOCATION = 512;

  private BoundedText() {}

  /**
   * Returns {@code text} unchanged when it is {@code null} or already within {@code maxChars};
   * otherwise returns its first {@code maxChars - 1} characters followed by the {@link #ELLIPSIS}
   * marker, so the result is exactly {@code maxChars} characters long.
   *
   * @param text the foreign text to bound; {@code null} is passed through (a {@code Finding}
   *     location may legitimately be {@code null})
   * @param maxChars the maximum length of the returned string; must be at least 1
   * @return the bounded text, or {@code null} when {@code text} is {@code null}
   */
  public static String cap(String text, int maxChars) {
    if (text == null || text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, maxChars - 1) + ELLIPSIS;
  }
}
