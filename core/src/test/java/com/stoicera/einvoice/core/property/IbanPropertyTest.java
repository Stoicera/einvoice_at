package com.stoicera.einvoice.core.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.payment.Iban;
import java.math.BigInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class IbanPropertyTest {

  /** Generates structurally valid Austrian IBANs (AT + 2 check digits + 16 BBAN digits). */
  @Provide
  Arbitrary<String> validAustrianIbans() {
    return Arbitraries.strings().numeric().ofLength(16).map(IbanPropertyTest::withCheckDigits);
  }

  private static String withCheckDigits(String bban) {
    // Compute check digits per ISO 13616: digits of (BBAN + "AT00"), AT -> 10 29.
    String numeric = bban + "102900";
    BigInteger remainder = new BigInteger(numeric).mod(BigInteger.valueOf(97));
    int check = 98 - remainder.intValueExact();
    return "AT%02d%s".formatted(check, bban);
  }

  @Property
  void generatedIbansPassValidation(@ForAll("validAustrianIbans") String iban) {
    assertThat(new Iban(iban).value()).isEqualTo(iban);
  }

  @Property
  void singleDigitMutationBreaksTheChecksum(
      @ForAll("validAustrianIbans") String iban,
      @ForAll("positions") int position,
      @ForAll("deltas") int delta) {
    int index = 4 + (position % 16);
    char original = iban.charAt(index);
    char mutated = (char) ('0' + ((original - '0' + delta) % 10));
    String tampered = iban.substring(0, index) + mutated + iban.substring(index + 1);
    assertThatThrownBy(() -> new Iban(tampered)).isInstanceOf(InvariantViolationException.class);
  }

  @Provide
  Arbitrary<Integer> positions() {
    return Arbitraries.integers().between(0, 15);
  }

  @Provide
  Arbitrary<Integer> deltas() {
    return Arbitraries.integers().between(1, 9);
  }
}
