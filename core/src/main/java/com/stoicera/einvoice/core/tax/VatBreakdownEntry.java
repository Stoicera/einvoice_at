package com.stoicera.einvoice.core.tax;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;

/** One VAT category subtotal per EN 16931 BG-23: taxable base and the tax on that base. */
public record VatBreakdownEntry(VatRate rate, Money taxableAmount, Money taxAmount) {

  public VatBreakdownEntry {
    if (rate == null || taxableAmount == null || taxAmount == null) {
      throw new InvariantViolationException("VAT breakdown entry components must not be null");
    }
    Money expected = rate.taxOn(taxableAmount);
    if (!taxAmount.equals(expected)) {
      throw new InvariantViolationException(
          "Tax amount %s does not equal %s %% of %s (expected %s)"
              .formatted(taxAmount, rate.percentage(), taxableAmount, expected));
    }
  }
}
