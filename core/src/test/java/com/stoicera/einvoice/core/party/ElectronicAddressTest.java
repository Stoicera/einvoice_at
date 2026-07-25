package com.stoicera.einvoice.core.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import org.junit.jupiter.api.Test;

class ElectronicAddressTest {

  @Test
  void carriesSchemeAndValue() {
    ElectronicAddress address = new ElectronicAddress("9915", "AT:VAT:ATU12345678");

    assertThat(address.scheme()).isEqualTo("9915");
    assertThat(address.value()).isEqualTo("AT:VAT:ATU12345678");
  }

  @Test
  void trimsBothComponents() {
    ElectronicAddress address = new ElectronicAddress("  9915  ", "  0088:1234  ");

    assertThat(address.scheme()).isEqualTo("9915");
    assertThat(address.value()).isEqualTo("0088:1234");
  }

  @Test
  void rejectsABlankOrNullScheme() {
    assertThatThrownBy(() -> new ElectronicAddress(null, "x"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BT-34-1");
    assertThatThrownBy(() -> new ElectronicAddress("  ", "x"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BT-34-1");
  }

  /**
   * Shape only, never membership: the EAS code list is four-digit numeric throughout, but which
   * four-digit codes are current changes with every Peppol release, so that question belongs to the
   * validation module rather than to an invariant here that would quietly go stale.
   */
  @Test
  void rejectsASchemeThatIsNotAFourDigitEasCode() {
    assertThatThrownBy(() -> new ElectronicAddress("991", "x"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("four-digit EAS code");
    assertThatThrownBy(() -> new ElectronicAddress("99155", "x"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("four-digit EAS code");
    assertThatThrownBy(() -> new ElectronicAddress("AT99", "x"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("four-digit EAS code");
  }

  @Test
  void acceptsAnyFourDigitSchemeWithoutJudgingItsMembership() {
    assertThat(new ElectronicAddress("0088", "1234567890128").scheme()).isEqualTo("0088");
    assertThat(new ElectronicAddress("0000", "x").scheme()).isEqualTo("0000");
  }

  @Test
  void rejectsABlankOrNullValue() {
    assertThatThrownBy(() -> new ElectronicAddress("9915", null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BT-34/BT-49");
    assertThatThrownBy(() -> new ElectronicAddress("9915", "   "))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BT-34/BT-49");
  }

  /** Defensive DoS bounds, guarded before trim() so no unbounded copy is made first. */
  @Test
  void rejectsAnOverlongSchemeOrValue() {
    String overlong = "9".repeat(257);

    assertThatThrownBy(() -> new ElectronicAddress(overlong, "x"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("exceeds 256 characters");
    assertThatThrownBy(() -> new ElectronicAddress("9915", overlong))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("exceeds 256 characters");
  }

  /** A rejected scheme is echoed through the bounded-echo helper, never raw. */
  @Test
  void doesNotEchoAnUnboundedSchemeIntoTheMessage() {
    String hostile = "A".repeat(200);

    assertThatThrownBy(() -> new ElectronicAddress(hostile, "x"))
        .isInstanceOf(InvariantViolationException.class)
        .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(hostile.length()));
  }
}
