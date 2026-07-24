package com.stoicera.einvoice.core.party;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.internal.Texts;
import java.util.Locale;
import java.util.regex.Pattern;

/** Postal address per EN 16931 BG-5/BG-8 (subset). */
public record Address(String street, String city, String postalCode, String countryCode) {

  private static final Pattern ISO_3166_ALPHA2 = Pattern.compile("[A-Z]{2}");

  /** Defensive DoS bound, not a business rule: free-text address fields must stay bounded. */
  private static final int MAX_STREET_CITY_LENGTH = 256;

  private static final int MAX_POSTAL_CODE_LENGTH = 16;

  /**
   * Defensive DoS bound, not a business rule: a real ISO 3166-1 alpha-2 code is 2 characters, but
   * the guard must reject an arbitrarily long value before {@code trim()}/{@code toUpperCase()}
   * materializes a full copy of it.
   */
  private static final int MAX_COUNTRY_CODE_LENGTH = 16;

  public Address {
    requireNonBlank(street, "street");
    requireNonBlank(city, "city");
    requireNonBlank(postalCode, "postal code");
    requireNonBlank(countryCode, "country code");
    requireMaxLength(street, MAX_STREET_CITY_LENGTH, "Street");
    requireMaxLength(city, MAX_STREET_CITY_LENGTH, "City");
    requireMaxLength(postalCode, MAX_POSTAL_CODE_LENGTH, "Postal code");
    if (countryCode.length() > MAX_COUNTRY_CODE_LENGTH) {
      throw new InvariantViolationException(
          "Address country code exceeds %d characters".formatted(MAX_COUNTRY_CODE_LENGTH));
    }
    countryCode = countryCode.trim().toUpperCase(Locale.ROOT);
    if (!ISO_3166_ALPHA2.matcher(countryCode).matches()) {
      throw new InvariantViolationException(
          "Country code '%s' is not ISO 3166-1 alpha-2".formatted(Texts.safeEcho(countryCode)));
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new InvariantViolationException("Address %s must not be blank".formatted(field));
    }
  }

  private static void requireMaxLength(String value, int max, String field) {
    if (value != null && value.length() > max) {
      throw new InvariantViolationException("%s exceeds %d characters".formatted(field, max));
    }
  }
}
