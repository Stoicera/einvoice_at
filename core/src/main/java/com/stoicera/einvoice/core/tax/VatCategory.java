package com.stoicera.einvoice.core.tax;

/**
 * EN 16931 VAT category (UNCL 5305 subset relevant for Austrian invoices).
 *
 * <p>Reduced Austrian rates (13 %, 10 %) use category {@code S} with a different percentage, per EN
 * 16931 / Peppol BIS practice.
 */
public enum VatCategory {
  STANDARD("S"),
  ZERO_RATED("Z"),
  REVERSE_CHARGE("AE"),
  EXEMPT("E");

  private final String code;

  VatCategory(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
