package com.stoicera.einvoice.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.math.BigDecimal;
import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

class MoneyTest {

  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void normalizesScaleToTwo() {
    assertThat(Money.of("5", Money.EUR).amount()).isEqualTo(new BigDecimal("5.00"));
    assertThat(Money.of("5", Money.EUR)).isEqualTo(Money.of("5.00", Money.EUR));
  }

  @Test
  void rejectsScaleBeyondTwo() {
    assertThatThrownBy(() -> Money.of(new BigDecimal("1.005"), Money.EUR))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("scale");
  }

  @Test
  void roundedAppliesHalfUp() {
    assertThat(Money.rounded(new BigDecimal("1.005"), Money.EUR))
        .isEqualTo(Money.of("1.01", Money.EUR));
    assertThat(Money.rounded(new BigDecimal("-1.005"), Money.EUR))
        .isEqualTo(Money.of("-1.01", Money.EUR));
    assertThat(Money.rounded(new BigDecimal("2.004"), Money.EUR))
        .isEqualTo(Money.of("2.00", Money.EUR));
  }

  @Test
  void arithmetic() {
    Money a = Money.of("10.50", Money.EUR);
    Money b = Money.of("0.75", Money.EUR);
    assertThat(a.plus(b)).isEqualTo(Money.of("11.25", Money.EUR));
    assertThat(a.minus(b)).isEqualTo(Money.of("9.75", Money.EUR));
    assertThat(b.minus(a)).isEqualTo(Money.of("-9.75", Money.EUR));
    assertThat(a.negated()).isEqualTo(Money.of("-10.50", Money.EUR));
    assertThat(a.times(new BigDecimal("0.20"))).isEqualTo(Money.of("2.10", Money.EUR));
    assertThat(Money.of("0.10", Money.EUR).times(new BigDecimal("0.13")))
        .isEqualTo(Money.of("0.01", Money.EUR));
  }

  @Test
  void predicatesAndZero() {
    assertThat(Money.zero(Money.EUR).isZero()).isTrue();
    assertThat(Money.of("-0.01", Money.EUR).isNegative()).isTrue();
    assertThat(Money.of("0.01", Money.EUR).isPositive()).isTrue();
    assertThat(Money.of("0.00", Money.EUR).isPositive()).isFalse();
  }

  @Test
  void comparableConsistentWithEquals() {
    assertThat(Money.of("1.00", Money.EUR)).isEqualByComparingTo(Money.of("1.00", Money.EUR));
    assertThat(Money.of("1.00", Money.EUR).compareTo(Money.of("2.00", Money.EUR))).isNegative();
  }

  @Test
  void rejectsCurrencyMix() {
    assertThatThrownBy(() -> Money.of("1.00", Money.EUR).plus(Money.of("1.00", USD)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("currency");
    assertThatThrownBy(() -> Money.of("1.00", Money.EUR).compareTo(Money.of("1.00", USD)))
        .isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> new Money(null, Money.EUR))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
        .isInstanceOf(InvariantViolationException.class);
  }

  // jqwik canary: proves the jqwik engine runs alongside JUnit Platform 6.
  // If this does not execute (check surefire output lists "jqwik"), STOP and report.
  @Property
  void plusThenMinusIsIdentity(
      @ForAll @BigRange(min = "-99999", max = "99999") @Scale(2) BigDecimal a,
      @ForAll @BigRange(min = "-99999", max = "99999") @Scale(2) BigDecimal b) {
    Money ma = Money.of(a, Money.EUR);
    Money mb = Money.of(b, Money.EUR);
    assertThat(ma.plus(mb).minus(mb)).isEqualTo(ma);
  }
}
