package com.stoicera.einvoice.core.payment;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.util.regex.Pattern;

/** SEPA credit transfer details per EN 16931 BG-17 (subset). */
public record PaymentMeans(Iban iban, String bic) {

  private static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[0-9A-Z]{2}([0-9A-Z]{3})?");

  public PaymentMeans {
    if (iban == null) {
      throw new InvariantViolationException("IBAN must not be null");
    }
    if (bic != null) {
      bic = bic.trim().toUpperCase();
      if (!BIC.matcher(bic).matches()) {
        throw new InvariantViolationException("BIC '%s' is malformed".formatted(bic));
      }
    }
  }
}
