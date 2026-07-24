package com.stoicera.einvoice.core.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import org.junit.jupiter.api.Test;

class VatExemptionReasonTest {

  @Test
  void requiresCodeOrText() {
    assertThatThrownBy(() -> new VatExemptionReason(null, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("BT-121")
        .hasMessageContaining("BT-120");
    assertThatThrownBy(() -> new VatExemptionReason("  ", ""))
        .isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void codeOnlyAndTextOnlyAreValid() {
    assertThat(new VatExemptionReason("VATEX-EU-AE", null).code()).isEqualTo("VATEX-EU-AE");
    assertThat(new VatExemptionReason(null, "Kleinunternehmer § 6 Abs 1 Z 27 UStG").text())
        .isEqualTo("Kleinunternehmer § 6 Abs 1 Z 27 UStG");
  }

  @Test
  void trimsComponentsAndBlankBecomesNull() {
    VatExemptionReason reason = new VatExemptionReason(" VATEX-EU-AE ", "  ");
    assertThat(reason.code()).isEqualTo("VATEX-EU-AE");
    assertThat(reason.text()).isNull();
  }

  @Test
  void reverseChargeConstantCarriesTheStandardValues() {
    assertThat(VatExemptionReason.REVERSE_CHARGE.code()).isEqualTo("VATEX-EU-AE");
    assertThat(VatExemptionReason.REVERSE_CHARGE.text()).isEqualTo("Reverse charge");
  }

  @Test
  void codeLengthIsCappedAtThirtyTwoCharacters() {
    String atLimit = "x".repeat(32);
    assertThat(new VatExemptionReason(atLimit, null).code()).hasSize(32);
    String overLimit = "x".repeat(33);
    assertThatThrownBy(() -> new VatExemptionReason(overLimit, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("code");
  }

  @Test
  void textLengthIsCappedAtOneThousandTwentyFourCharacters() {
    String atLimit = "x".repeat(1024);
    assertThat(new VatExemptionReason(null, atLimit).text()).hasSize(1024);
    String overLimit = "x".repeat(1025);
    assertThatThrownBy(() -> new VatExemptionReason(null, overLimit))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("text");
  }

  @Test
  void codeLengthGuardAppliesToTheRawValueBeforeTrimming() {
    // Trimmed down to 11 chars, well within the 32-char cap — but the raw, padded value is
    // over it. The guard must reject the raw value before trim() discards the padding, the
    // same pre-normalization-guard pattern as Address/PaymentMeans (countryCode/BIC).
    String paddedOverLimit = " ".repeat(20) + "VATEX-EU-AE" + " ".repeat(20);
    assertThatThrownBy(() -> new VatExemptionReason(paddedOverLimit, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("code");
  }

  @Test
  void textLengthGuardAppliesToTheRawValueBeforeTrimming() {
    // Trimmed down to 5 chars, well within the 1024-char cap — but the raw, padded value is
    // over it. The guard must reject the raw value before trim() discards the padding.
    String paddedOverLimit = " ".repeat(1020) + "hello" + " ".repeat(1020);
    assertThatThrownBy(() -> new VatExemptionReason(null, paddedOverLimit))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("text");
  }
}
