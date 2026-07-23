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
}
