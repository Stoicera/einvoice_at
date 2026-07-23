package com.stoicera.einvoice.core.party;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.util.regex.Pattern;

/**
 * Seller or buyer per EN 16931 BG-4/BG-7 (subset).
 *
 * <p>{@code vatId} is optional in the model (e.g. Kleinunternehmer without UID); whether it is
 * required for a given profile is a business rule of the validation module, not a core invariant.
 */
public record Party(String name, Address address, String vatId) {

  private static final Pattern EU_VAT_ID = Pattern.compile("[A-Z]{2}[0-9A-Z]{2,13}");

  public Party {
    if (name == null || name.isBlank()) {
      throw new InvariantViolationException("Party name must not be blank");
    }
    if (address == null) {
      throw new InvariantViolationException("Party address must not be null");
    }
    if (vatId != null) {
      vatId = vatId.trim();
      if (!EU_VAT_ID.matcher(vatId).matches()) {
        throw new InvariantViolationException(
            "VAT id '%s' is not a valid EU VAT id".formatted(vatId));
      }
    }
  }
}
