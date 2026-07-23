package com.stoicera.einvoice.core.party;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.util.regex.Pattern;

/** Postal address per EN 16931 BG-5/BG-8 (subset). */
public record Address(String street, String city, String postalCode, String countryCode) {

  private static final Pattern ISO_3166_ALPHA2 = Pattern.compile("[A-Z]{2}");

  public Address {
    requireNonBlank(street, "street");
    requireNonBlank(city, "city");
    requireNonBlank(postalCode, "postal code");
    requireNonBlank(countryCode, "country code");
    if (!ISO_3166_ALPHA2.matcher(countryCode).matches()) {
      throw new InvariantViolationException(
          "country code '%s' is not ISO 3166-1 alpha-2".formatted(countryCode));
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new InvariantViolationException("Address %s must not be blank".formatted(field));
    }
  }
}
