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

  public InvoiceLine {
    requireNonBlank(id, "line id");
    requireNonBlank(description, "line description");
    requireNonBlank(unitCode, "unit code");
    if (quantity == null || quantity.signum() == 0) {
      throw new InvariantViolationException("Line quantity must be non-zero");
    }
    if (quantity.scale() > MAX_SCALE) {
      throw new InvariantViolationException(
          "Line quantity %s exceeds scale %d".formatted(quantity.toPlainString(), MAX_SCALE));
    }
    if (unitPrice == null || unitPrice.signum() < 0) {
      throw new InvariantViolationException("Unit price must be non-negative (EN 16931 BR-27)");
    }
    if (unitPrice.scale() > MAX_SCALE) {
      throw new InvariantViolationException(
          "Unit price %s exceeds scale %d".formatted(unitPrice.toPlainString(), MAX_SCALE));
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
}
