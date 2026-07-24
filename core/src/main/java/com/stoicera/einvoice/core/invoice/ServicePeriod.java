package com.stoicera.einvoice.core.invoice;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.time.LocalDate;

/**
 * Service period (Leistungszeitraum) per EN 16931 BG-14: the span of days over which a supply was
 * rendered, used on {@link Invoice} as an alternative to a single {@link
 * Invoice.Builder#deliveryDate(LocalDate) delivery date} when the supply is not a single-day event
 * (§ 11 Abs 1 Z 4 UStG requires the day of delivery *or* the period, never both — that
 * mutual-exclusion invariant is enforced by {@code Invoice}, not here).
 */
public record ServicePeriod(LocalDate from, LocalDate to) {

  public ServicePeriod {
    if (from == null) {
      throw new InvariantViolationException("Service period start date must not be null");
    }
    if (to == null) {
      throw new InvariantViolationException("Service period end date must not be null");
    }
    if (to.isBefore(from)) {
      throw new InvariantViolationException(
          "Service period end date %s must not be before start date %s".formatted(to, from));
    }
  }
}
