package com.stoicera.einvoice.core.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
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
  void rejectsMalformedIban() {
    assertThatThrownBy(() -> new Iban(null)).isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new Iban("")).isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new Iban("AT61")).isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(() -> new Iban("1234567890123456"))
        .isInstanceOf(InvariantViolationException.class);
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
}
