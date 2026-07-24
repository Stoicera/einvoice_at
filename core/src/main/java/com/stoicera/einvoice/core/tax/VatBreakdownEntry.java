package com.stoicera.einvoice.core.tax;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;

/**
 * One VAT category subtotal per EN 16931 BG-23: taxable base, the tax on that base, and — for
 * categories AE and E — the exemption reason (BT-120/BT-121) required by BR-AE-10/BR-E-10.
 * Categories S and Z must not carry a reason (BR-S-10/BR-Z-10).
 */
public record VatBreakdownEntry(
    VatRate rate, Money taxableAmount, Money taxAmount, VatExemptionReason exemptionReason) {

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
    if (rate.category().requiresExemptionReason() && exemptionReason == null) {
      throw new InvariantViolationException(
          "VAT category %s requires an exemption reason (EN 16931 BR-%s-10)"
              .formatted(rate.category().code(), rate.category().code()));
    }
    if (!rate.category().requiresExemptionReason() && exemptionReason != null) {
      throw new InvariantViolationException(
          "VAT category %s must not carry an exemption reason (EN 16931 BR-%s-10)"
              .formatted(rate.category().code(), rate.category().code()));
    }
  }

  /** Entry without exemption reason — valid only for categories S and Z. */
  public VatBreakdownEntry(VatRate rate, Money taxableAmount, Money taxAmount) {
    this(rate, taxableAmount, taxAmount, null);
  }
}
