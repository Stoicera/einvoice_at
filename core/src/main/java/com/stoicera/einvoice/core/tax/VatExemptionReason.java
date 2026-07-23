package com.stoicera.einvoice.core.tax;

import com.stoicera.einvoice.core.InvariantViolationException;

/**
 * VAT exemption reason per EN 16931: code (BT-121, VATEX code list) and/or free text (BT-120). At
 * least one of the two must be present. VATEX code-list validation is a concern of the validation
 * module, not enforced here (same treatment as unit codes).
 */
public record VatExemptionReason(String code, String text) {

  /** The reason mandated for category AE by EN 16931 BR-AE-10: "Reverse charge". */
  public static final VatExemptionReason REVERSE_CHARGE =
      new VatExemptionReason("VATEX-EU-AE", "Reverse charge");

  public VatExemptionReason {
    code = normalize(code);
    text = normalize(text);
    if (code == null && text == null) {
      throw new InvariantViolationException(
          "VAT exemption reason requires a code (BT-121) or a text (BT-120)");
    }
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
