package com.stoicera.einvoice.core.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.money.Money;
import java.math.BigDecimal;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class MoneyProperties {

  @Provide
  Arbitrary<BigDecimal> amounts() {
    return Generators.moneyAmounts();
  }

  @Property
  void additionIsCommutative(@ForAll("amounts") BigDecimal a, @ForAll("amounts") BigDecimal b) {
    Money ma = Money.of(a, Money.EUR);
    Money mb = Money.of(b, Money.EUR);
    assertThat(ma.plus(mb)).isEqualTo(mb.plus(ma));
  }

  @Property
  void additionIsAssociative(
      @ForAll("amounts") BigDecimal a,
      @ForAll("amounts") BigDecimal b,
      @ForAll("amounts") BigDecimal c) {
    Money ma = Money.of(a, Money.EUR);
    Money mb = Money.of(b, Money.EUR);
    Money mc = Money.of(c, Money.EUR);
    assertThat(ma.plus(mb).plus(mc)).isEqualTo(ma.plus(mb.plus(mc)));
  }

  @Property
  void negationIsAdditiveInverse(@ForAll("amounts") BigDecimal a) {
    Money ma = Money.of(a, Money.EUR);
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
