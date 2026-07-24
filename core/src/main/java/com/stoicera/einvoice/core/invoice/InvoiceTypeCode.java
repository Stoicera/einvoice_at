package com.stoicera.einvoice.core.invoice;

/**
 * Invoice type code per EN 16931 BT-3 (UNTDID 1001 subset).
 *
 * <p>The canonical model carries credit notes as type 381 with positive amounts — direction comes
 * from the type code, not from the sign, mirroring UBL CreditNote and ebInterface CreditMemo
 * practice. Further UNTDID 1001 codes are added when mapping (M2/M4) demonstrates the need.
 */
public enum InvoiceTypeCode {
  COMMERCIAL_INVOICE("380"),
  CREDIT_NOTE("381");

  private final String code;

  InvoiceTypeCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
