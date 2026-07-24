package com.stoicera.einvoice.core.tax;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * A VAT category with its percentage, normalized to scale 2.
 *
 * <p>Category {@code S} requires a positive percentage; all other categories require 0 %.
 */
public record VatRate(VatCategory category, BigDecimal percentage) implements Comparable<VatRate> {

  public static final VatRate STANDARD_20 = new VatRate(VatCategory.STANDARD, new BigDecimal("20"));
  public static final VatRate REDUCED_13 = new VatRate(VatCategory.STANDARD, new BigDecimal("13"));
  public static final VatRate REDUCED_10 = new VatRate(VatCategory.STANDARD, new BigDecimal("10"));
  public static final VatRate ZERO = new VatRate(VatCategory.ZERO_RATED, BigDecimal.ZERO);
  public static final VatRate REVERSE_CHARGE =
      new VatRate(VatCategory.REVERSE_CHARGE, BigDecimal.ZERO);
  public static final VatRate EXEMPT = new VatRate(VatCategory.EXEMPT, BigDecimal.ZERO);

  private static final Comparator<VatRate> ORDER =
      Comparator.comparing((VatRate r) -> r.category.ordinal())
          .thenComparing(VatRate::percentage, Comparator.reverseOrder());

  public VatRate {
    if (category == null) {
      throw new InvariantViolationException("VAT category must not be null");
    }
    if (percentage == null) {
      throw new InvariantViolationException("VAT percentage must not be null");
    }
    if (percentage.scale() > 2) {
      // Never echo percentage.toPlainString() here: an astronomical scale would materialize a
      // huge string before truncation could help. State the scale number instead (see Money,
      // InvoiceLine).
      throw new InvariantViolationException(
          "VAT percentage scale %d exceeds scale 2".formatted(percentage.scale()));
    }
    if (percentage.signum() < 0 || percentage.compareTo(new BigDecimal("100")) > 0) {
      throw new InvariantViolationException("VAT percentage out of range [0, 100]");
    }
    if (category == VatCategory.STANDARD && percentage.signum() <= 0) {
      throw new InvariantViolationException("Category S requires a positive percentage");
    }
    if (category != VatCategory.STANDARD && percentage.signum() != 0) {
      throw new InvariantViolationException(
          "Category %s requires a percentage of 0".formatted(category.code()));
    }
    percentage = percentage.setScale(2);
  }

  /** The Austrian rate set: 20 / 13 / 10 % plus zero-rated, reverse charge and exempt. */
  public static List<VatRate> austrianRates() {
    return List.of(STANDARD_20, REDUCED_13, REDUCED_10, ZERO, REVERSE_CHARGE, EXEMPT);
  }

  /** Tax on {@code base}, rounded commercially to scale 2 — the EN 16931 category-level rule. */
  public Money taxOn(Money base) {
    return base.times(percentage.movePointLeft(2));
  }

  @Override
  public int compareTo(VatRate other) {
    return ORDER.compare(this, other);
  }
}
