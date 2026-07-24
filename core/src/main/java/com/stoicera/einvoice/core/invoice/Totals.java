package com.stoicera.einvoice.core.invoice;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;

/**
 * Document totals per EN 16931 BG-22 (subset): no document-level allowances/charges and no prepaid
 * amount yet, hence payable == gross.
 */
public record Totals(Money netTotal, Money taxTotal, Money grossTotal, Money payableAmount) {

  public Totals {
    if (netTotal == null || taxTotal == null || grossTotal == null || payableAmount == null) {
      throw new InvariantViolationException("Totals components must not be null");
    }
    if (!grossTotal.equals(netTotal.plus(taxTotal))) {
      throw new InvariantViolationException(
          "Gross total %s does not equal net %s + tax %s"
              .formatted(grossTotal, netTotal, taxTotal));
    }
    if (!payableAmount.equals(grossTotal)) {
      throw new InvariantViolationException(
          "Payable amount %s does not equal gross total %s".formatted(payableAmount, grossTotal));
    }
  }
}
