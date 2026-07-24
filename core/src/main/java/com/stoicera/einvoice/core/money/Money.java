package com.stoicera.einvoice.core.money;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.internal.Texts;
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

  /**
   * Defensive ceiling: amounts beyond 15 integer digits are absurd for invoices and can OOM. This
   * cap applies to every {@code Money} instance regardless of construction path — {@link
   * #of(String, Currency)}, {@link #of(BigDecimal, Currency)}, {@link #rounded(BigDecimal,
   * Currency)}, and arithmetic results ({@link #plus}, {@link #minus}, {@link #times}) all funnel
   * through the canonical constructor, so none can produce a {@code Money} that exceeds it.
   */
  public static final int MAX_INTEGER_DIGITS = 15;

  public Money {
    if (amount == null) {
      throw new InvariantViolationException("Money amount must not be null");
    }
    if (currency == null) {
      throw new InvariantViolationException("Money currency must not be null");
    }
    if (integerDigits(amount) > MAX_INTEGER_DIGITS) {
      throw new InvariantViolationException(
          "Money amount exceeds %d integer digits".formatted(MAX_INTEGER_DIGITS));
    }
    if (amount.scale() > SCALE) {
      // Never echo amount.toPlainString() here: a value with an astronomical positive scale
      // (e.g. 1E-1000000000) passes the integer-digit cap above and would materialize a
      // ~1 GB string before truncation could help. State the two scale numbers instead.
      throw new InvariantViolationException(
          "Money amount scale %d exceeds scale %d; round explicitly via Money.rounded()"
              .formatted(amount.scale(), SCALE));
    }
    amount = amount.setScale(SCALE);
  }

  public static Money of(String amount, Currency currency) {
    if (amount == null) {
      throw new InvariantViolationException("Money amount must not be null");
    }
    try {
      return new Money(new BigDecimal(amount.trim()), currency);
    } catch (NumberFormatException e) {
      throw new InvariantViolationException(
          "Money amount '%s' is not a valid decimal".formatted(Texts.safeEcho(amount)));
    }
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  /** Rounds {@code raw} commercially to scale {@value #SCALE} — the single rounding step. */
  public static Money rounded(BigDecimal raw, Currency currency) {
    if (raw == null) {
      throw new InvariantViolationException("Money amount must not be null");
    }
    if (integerDigits(raw) > MAX_INTEGER_DIGITS) {
      throw new InvariantViolationException(
          "Money amount exceeds %d integer digits".formatted(MAX_INTEGER_DIGITS));
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

  private static int integerDigits(BigDecimal value) {
    return value.precision() - value.scale();
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
