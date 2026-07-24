package com.stoicera.einvoice.core.payment;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.internal.Texts;
import java.util.Locale;
import java.util.regex.Pattern;

/** SEPA credit transfer details per EN 16931 BG-17 (subset). */
public record PaymentMeans(Iban iban, String bic) {

  private static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[0-9A-Z]{2}([0-9A-Z]{3})?");

  /**
   * Defensive DoS bound, not a business rule: a real BIC is at most 11 characters, but the guard
   * must reject an arbitrarily long value before {@code trim()}/{@code toUpperCase()} materializes
   * a full copy of it. 16 gives slack for surrounding whitespace.
   */
  private static final int MAX_BIC_LENGTH = 16;

  public PaymentMeans {
    if (iban == null) {
      throw new InvariantViolationException("IBAN must not be null");
    }
    if (bic != null) {
      if (bic.length() > MAX_BIC_LENGTH) {
        throw new InvariantViolationException(
            "BIC exceeds %d characters".formatted(MAX_BIC_LENGTH));
      }
      bic = bic.trim().toUpperCase(Locale.ROOT);
      if (!BIC.matcher(bic).matches()) {
        throw new InvariantViolationException(
            "BIC '%s' is malformed".formatted(Texts.safeEcho(bic)));
      }
    }
  }
}
