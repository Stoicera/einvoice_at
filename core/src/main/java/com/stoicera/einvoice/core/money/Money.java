package com.stoicera.einvoice.core.money;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * A monetary amount at fixed scale {@value #SCALE} in a single currency.
 *
 * <p>Derived amounts are rounded commercially ({@link RoundingMode#HALF_UP}) exactly once, at the
 * point where they enter the model via {@link #rounded(BigDecimal, Currency)} or {@link
 * #times(BigDecimal)}. Construction never rounds silently: amounts with more than {@value #SCALE}
 * decimals are rejected.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

  public static final int SCALE = 2;
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
  public static final Currency EUR = Currency.getInstance("EUR");

  public Money {
    if (amount == null) {
      throw new InvariantViolationException("Money amount must not be null");
    }
    if (currency == null) {
      throw new InvariantViolationException("Money currency must not be null");
    }
    if (amount.scale() > SCALE) {
      throw new InvariantViolationException(
          "Money amount %s exceeds scale %d; round explicitly via Money.rounded()"
              .formatted(amount.toPlainString(), SCALE));
    }
    amount = amount.setScale(SCALE);
  }

  public static Money of(String amount, Currency currency) {
    return new Money(new BigDecimal(amount), currency);
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  /** Rounds {@code raw} commercially to scale {@value #SCALE} — the single rounding step. */
  public static Money rounded(BigDecimal raw, Currency currency) {
    if (raw == null) {
      throw new InvariantViolationException("Money amount must not be null");
    }
    return new Money(raw.setScale(SCALE, ROUNDING), currency);
  }

  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  public Money plus(Money other) {
    return new Money(amount.add(sameCurrency(other).amount), currency);
  }

  public Money minus(Money other) {
    return new Money(amount.subtract(sameCurrency(other).amount), currency);
  }

  public Money negated() {
    return new Money(amount.negate(), currency);
  }

  /** Multiplies by {@code factor} and rounds the result commercially to scale {@value #SCALE}. */
  public Money times(BigDecimal factor) {
    return rounded(amount.multiply(factor), currency);
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  @Override
  public int compareTo(Money other) {
    return amount.compareTo(sameCurrency(other).amount);
  }

  private Money sameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new InvariantViolationException(
          "Money currency mismatch: %s vs %s".formatted(currency, other.currency));
    }
    return other;
  }

  @Override
  public String toString() {
    return amount.toPlainString() + " " + currency.getCurrencyCode();
  }
}
