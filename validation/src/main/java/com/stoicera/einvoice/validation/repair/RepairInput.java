package com.stoicera.einvoice.validation.repair;

/**
 * Values the user supplies for changes the document itself cannot answer.
 *
 * <p>Today one field: the Auftragsreferenz ({@code AT-B2G-01}), which only the ordering federal
 * body knows. The value is stripped; a blank value counts as not supplied. It is rejected — not
 * silently shortened — when it exceeds the ebInterface {@code IDType} limit of 255 characters or
 * contains a character XML 1.0 cannot carry, because writing it would produce a document the
 * re-validation could only reject for a reason the user did not cause.
 *
 * @param orderReference the Auftragsreferenz, or {@code null} when not supplied
 */
public record RepairInput(String orderReference) {

  /** Upper bound of ebInterface 6.1 {@code IDType}, the type of {@code OrderID}. */
  public static final int MAX_ORDER_REFERENCE_LENGTH = 255;

  private static final RepairInput NONE = new RepairInput(null);

  public RepairInput {
    orderReference = normalize(orderReference);
  }

  /** No user input at all — the proposal uses only what the document contains. */
  public static RepairInput none() {
    return NONE;
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String stripped = value.strip();
    // Code points, not UTF-16 units: XSD maxLength counts characters as XML sees them.
    if (stripped.codePointCount(0, stripped.length()) > MAX_ORDER_REFERENCE_LENGTH) {
      throw new IllegalArgumentException(
          "Die Auftragsreferenz darf höchstens %d Zeichen lang sein."
              .formatted(MAX_ORDER_REFERENCE_LENGTH));
    }
    if (!stripped.codePoints().allMatch(RepairInput::isXmlChar)) {
      throw new IllegalArgumentException("Die Auftragsreferenz enthält ein unzulässiges Zeichen.");
    }
    return stripped;
  }

  /** XML 1.0 {@code Char} production, minus the line breaks a one-line reference never needs. */
  private static boolean isXmlChar(int c) {
    return (c >= 0x20 && c <= 0xD7FF)
        || (c >= 0xE000 && c <= 0xFFFD)
        || (c >= 0x10000 && c <= 0x10FFFF);
  }
}
