package com.stoicera.einvoice.core.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.money.Money;
import java.math.BigDecimal;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Arithmetic properties of {@link Money}. Each operation is checked against a plain-{@link
 * BigDecimal} expectation computed on the raw amounts — never against the same {@code Money} method
 * on both sides, which would be a same-code-path tautology (finding P1.2). {@link
 * #compareToIsConsistentWithEquals} is a deliberate cross-contract check (compareTo vs equals), not
 * arithmetic, and stays as documented.
 */
class MoneyPropertyTest {

  @Provide
  Arbitrary<BigDecimal> amounts() {
    return Generators.moneyAmounts();
  }

  @Property
  void additionMatchesBigDecimalSum(
      @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b) {
    Money sum = Money.of(a, Money.EUR).plus(Money.of(b, Money.EUR));
    assertThat(sum.amount()).isEqualByComparingTo(a.add(b));
  }

  @Property
  void chainedAdditionMatchesBigDecimalSum(
      @ForAll("amounts") BigDecimal a,
      @ForAll("amounts") BigDecimal b,
      @ForAll("amounts") BigDecimal c) {
    Money sum = Money.of(a, Money.EUR).plus(Money.of(b, Money.EUR)).plus(Money.of(c, Money.EUR));
    assertThat(sum.amount()).isEqualByComparingTo(a.add(b).add(c));
  }

  @Property
  void negationMatchesBigDecimalNegate(@ForAll("amounts") BigDecimal a) {
    Money ma = Money.of(a, Money.EUR);
    assertThat(ma.negated().amount()).isEqualByComparingTo(a.negate());
    // additive-inverse cross-check: plus(negated) collapses to zero.
    assertThat(ma.plus(ma.negated())).isEqualTo(Money.zero(Money.EUR));
  }

  @Property
  void timesDeviatesFromExactProductByAtMostHalfACent(
      @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal factor) {
    Money product = Money.of(a, Money.EUR).times(factor);
    BigDecimal exact = a.multiply(factor);
    assertThat(product.amount().subtract(exact).abs()).isLessThanOrEqualTo(new BigDecimal("0.005"));
  }

  @Property
  void compareToIsConsistentWithEquals(
      @ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b) {
    Money ma = Money.of(a, Money.EUR);
    Money mb = Money.of(b, Money.EUR);
    assertThat(ma.compareTo(mb) == 0).isEqualTo(ma.equals(mb));
  }
}
