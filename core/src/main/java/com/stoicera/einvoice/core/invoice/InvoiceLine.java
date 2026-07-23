package com.stoicera.einvoice.core.invoice;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.util.Currency;

/**
 * Invoice line per EN 16931 BG-25 (subset). Quantity may be negative (credit line); the item net
 * price must not be (BR-27). Unit code is a UN/ECE Recommendation 20 code, e.g. C62, HUR, KGM —
 * code-list validation is a concern of the validation module.
 */
public record InvoiceLine(
    String id,
    String description,
    BigDecimal quantity,
    String unitCode,
    BigDecimal unitPrice,
    VatRate vatRate) {

  private static final int MAX_SCALE = 4;

  /**
   * Defensive DoS bound, not a business rule: 7 integer digits for quantity, combined with {@link
   * #MAX_UNIT_PRICE_INTEGER_DIGITS}'s 8, keeps the line-net product (quantity × unitPrice) always
   * representable within {@link Money#MAX_INTEGER_DIGITS}'s 15 digits.
   */
  private static final int MAX_QUANTITY_INTEGER_DIGITS = 7;

  /**
   * Defensive DoS bound, not a business rule: 8 integer digits for unit price, combined with {@link
   * #MAX_QUANTITY_INTEGER_DIGITS}'s 7, keeps the line-net product (quantity × unitPrice) always
   * representable within {@link Money#MAX_INTEGER_DIGITS}'s 15 digits.
   */
  private static final int MAX_UNIT_PRICE_INTEGER_DIGITS = 8;

  /** Defensive DoS bound, not a business rule: free-text line fields must stay bounded. */
  private static final int MAX_ID_LENGTH = 128;

  private static final int MAX_DESCRIPTION_LENGTH = 4096;
  private static final int MAX_UNIT_CODE_LENGTH = 8;

  public InvoiceLine {
    requireNonBlank(id, "line id");
    requireNonBlank(description, "line description");
    requireNonBlank(unitCode, "unit code");
    requireMaxLength(id, MAX_ID_LENGTH, "line id");
    requireMaxLength(description, MAX_DESCRIPTION_LENGTH, "line description");
    requireMaxLength(unitCode, MAX_UNIT_CODE_LENGTH, "unit code");
    if (quantity == null) {
      throw new InvariantViolationException("Line quantity must not be null");
    }
    if (quantity.signum() == 0) {
      throw new InvariantViolationException("Line quantity must be non-zero");
    }
    if (quantity.precision() - quantity.scale() > MAX_QUANTITY_INTEGER_DIGITS) {
      throw new InvariantViolationException(
          "Line quantity exceeds %d integer digits".formatted(MAX_QUANTITY_INTEGER_DIGITS));
    }
    if (quantity.scale() > MAX_SCALE) {
      // Never echo toPlainString(): an astronomical scale would materialize a huge string
      // before truncation could help. State the two scale numbers instead (see Money).
      throw new InvariantViolationException(
          "Line quantity scale %d exceeds scale %d".formatted(quantity.scale(), MAX_SCALE));
    }
    if (unitPrice == null) {
      throw new InvariantViolationException("Unit price must not be null");
    }
    if (unitPrice.signum() < 0) {
      throw new InvariantViolationException("Unit price must be non-negative (EN 16931 BR-27)");
    }
    if (unitPrice.precision() - unitPrice.scale() > MAX_UNIT_PRICE_INTEGER_DIGITS) {
      throw new InvariantViolationException(
          "Unit price exceeds %d integer digits".formatted(MAX_UNIT_PRICE_INTEGER_DIGITS));
    }
    if (unitPrice.scale() > MAX_SCALE) {
      throw new InvariantViolationException(
          "Unit price scale %d exceeds scale %d".formatted(unitPrice.scale(), MAX_SCALE));
    }
    if (vatRate == null) {
      throw new InvariantViolationException("Line VAT rate must not be null");
    }
  }

  /** Line net amount: quantity × unit price, rounded commercially once (EN 16931 BT-131). */
  public Money netAmount(Currency currency) {
    return Money.rounded(quantity.multiply(unitPrice), currency);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new InvariantViolationException("%s must not be blank".formatted(field));
    }
  }

  private static void requireMaxLength(String value, int max, String field) {
    if (value != null && value.length() > max) {
      throw new InvariantViolationException("%s exceeds %d characters".formatted(field, max));
    }
  }
}
