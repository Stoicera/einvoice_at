package com.stoicera.einvoice.core.payment;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * IBAN per ISO 13616, validated by shape and mod-97 checksum. Country-specific length rules are a
 * concern of the validation module, not enforced here.
 */
public record Iban(String value) {

  private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[0-9A-Z]{11,30}");
  private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

  public Iban {
    if (value == null || value.isBlank()) {
      throw new InvariantViolationException("IBAN must not be blank");
    }
    value = value.replace(" ", "").toUpperCase(Locale.ROOT);
    if (!SHAPE.matcher(value).matches()) {
      throw new InvariantViolationException("IBAN '%s' is malformed".formatted(value));
    }
    if (!checksumValid(value)) {
      throw new InvariantViolationException("IBAN '%s' fails the mod-97 checksum".formatted(value));
    }
  }

  private static boolean checksumValid(String iban) {
    String rearranged = iban.substring(4) + iban.substring(0, 4);
    StringBuilder digits = new StringBuilder(rearranged.length() * 2);
    for (char c : rearranged.toCharArray()) {
      digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : c);
    }
    return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValueExact() == 1;
  }

  /** The IBAN in groups of four, e.g. {@code AT61 1904 3002 3457 3201}. */
  public String formatted() {
    return value.replaceAll("(.{4})(?=.)", "$1 ");
  }
}
