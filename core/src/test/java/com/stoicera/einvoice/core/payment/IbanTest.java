package com.stoicera.einvoice.core.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.math.BigInteger;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IbanTest {

  // Well-known official example IBANs with valid checksums.
  private static final String AT_VALID = "AT611904300234573201";
  private static final String DE_VALID = "DE89370400440532013000";

  @Test
  void acceptsValidIbansAndNormalizes() {
    assertThat(new Iban(AT_VALID).value()).isEqualTo(AT_VALID);
    assertThat(new Iban("at61 1904 3002 3457 3201").value()).isEqualTo(AT_VALID);
    assertThat(new Iban(DE_VALID).value()).isEqualTo(DE_VALID);
  }

  @Test
  void formatsInGroupsOfFour() {
    assertThat(new Iban(AT_VALID).formatted()).isEqualTo("AT61 1904 3002 3457 3201");
  }

  @Test
  void rejectsChecksumFailure() {
    assertThatThrownBy(() -> new Iban("AT611904300234573202"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void checksumFailureMessageNeverEchoesTheIban() {
    // AT_VALID with the last check digit flipped: still shape-valid, fails mod-97.
    assertThatThrownBy(() -> new Iban("AT611904300234573202"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("IBAN fails the mod-97 checksum")
        .hasMessageNotContaining("1904");
  }

  @Test
  void malformedMessageNeverEchoesTheIban() {
    assertThatThrownBy(() -> new Iban("1234567890123456"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("IBAN is malformed")
        .hasMessageNotContaining("1234567890123456");
  }

  @Test
  void checksumValidIbanWithNonStandardCountryLengthIsAcceptedByCore() {
    // AT IBANs are 20 chars; this 22-char value passes shape + mod-97. Core accepts by design:
    // country-specific length rules are a validation-module concern (see Iban Javadoc).
    // Construct any >20-char mod-97-valid candidate programmatically:
    String bban = "1904300234573201" + "00"; // 18 digits
    String candidate = withValidCheckDigits("AT", bban);
    assertThat(new Iban(candidate).value()).hasSize(22);
  }

  /**
   * Computes ISO 13616 mod-97 check digits from scratch, independently of {@link Iban}, so this
   * test does not validate production logic against itself.
   */
  private static String withValidCheckDigits(String countryCode, String bban) {
    String rearranged = bban + countryCode + "00";
    StringBuilder digits = new StringBuilder(rearranged.length() * 2);
    for (char c : rearranged.toCharArray()) {
      digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : c);
    }
    int checkDigits = 98 - new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
    return countryCode + "%02d".formatted(checkDigits) + bban;
  }

  @Test
  void rejectsMalformedIban() {
    assertThatThrownBy(() -> new Iban(null)).isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new Iban("")).isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new Iban("AT61")).isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new Iban("1234567890123456"))
        .isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void normalizationIsLocaleIndependent() {
    Locale previous = Locale.getDefault();
    Locale.setDefault(Locale.of("tr", "TR"));
    try {
      assertThat(new Iban("at61 1904 3002 3457 3201").value()).isEqualTo(AT_VALID);
      assertThat(new PaymentMeans(new Iban(AT_VALID), "gibaatww").bic()).isEqualTo("GIBAATWW");
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void paymentMeansValidatesBic() {
    Iban iban = new Iban(AT_VALID);
    assertThat(new PaymentMeans(iban, null).bic()).isNull();
    assertThat(new PaymentMeans(iban, "GIBAATWWXXX").bic()).isEqualTo("GIBAATWWXXX");
    assertThat(new PaymentMeans(iban, "gibaatww").bic()).isEqualTo("GIBAATWW");
    assertThatThrownBy(() -> new PaymentMeans(iban, "GIBA"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BIC");
    assertThatThrownBy(() -> new PaymentMeans(null, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("IBAN");
  }

  @Test
  void paymentMeansRejectsOverlongBic() {
    Iban iban = new Iban(AT_VALID);
    String overlong = "A".repeat(17);
    assertThatThrownBy(() -> new PaymentMeans(iban, overlong))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("BIC exceeds 16 characters");
  }

  @Test
  void malformedBicMessageSanitizesControlCharacters() {
    Iban iban = new Iban(AT_VALID);
    assertThatThrownBy(() -> new PaymentMeans(iban, "GIBA\nATWW"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("?")
        .hasMessageNotContaining("\n");
  }
}
