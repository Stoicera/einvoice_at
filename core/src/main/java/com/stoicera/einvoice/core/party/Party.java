package com.stoicera.einvoice.core.party;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.text.Texts;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Seller or buyer per EN 16931 BG-4/BG-7 (subset).
 *
 * <p>{@code vatId} is optional in the model (e.g. Kleinunternehmer without UID); whether it is
 * required for a given profile is a business rule of the validation module, not a core invariant.
 *
 * <p>{@code email} is likewise optional here (added M3, BT-43/BT-58, BG-6/BG-9/e-rechnung.gv.at
 * biller contact); the AT-B2G profile's requirement that a biller carry one is a validation-module
 * business rule, not a core invariant — see {@link #Party(String, Address, String)} for the pre-M3
 * shape.
 *
 * <p>{@code electronicAddress} (added M4, BT-34/BT-49 with its mandatory scheme BT-34-1/BT-49-1) is
 * the network routing address, a different thing from {@code email} — see {@link
 * ElectronicAddress}. It is optional here too: Peppol BIS Billing 3.0 requires it, ebInterface 6.1
 * has nowhere to put it, and a canonical invoice must be able to represent both worlds. Which
 * profile demands it is the validation module's business, and a conversion that cannot carry it
 * says so in its report rather than dropping it silently.
 */
public record Party(
    String name,
    Address address,
    String vatId,
    Optional<String> email,
    Optional<ElectronicAddress> electronicAddress) {

  private static final Pattern EU_VAT_ID = Pattern.compile("[A-Z]{2}[0-9A-Z]{2,13}");

  /**
   * Minimal shape check, deliberately not RFC 5322: a single {@code @} separating a non-empty local
   * part from a non-empty domain part, neither containing whitespace.
   */
  private static final Pattern EMAIL_SHAPE = Pattern.compile("[^\\s@]+@[^\\s@]+");

  /** Defensive DoS bound, not a business rule: free-text party name must stay bounded. */
  private static final int MAX_NAME_LENGTH = 256;

  /** Defensive DoS bound, not a business rule: free-text email must stay bounded. */
  private static final int MAX_EMAIL_LENGTH = 256;

  public Party {
    if (name == null || name.isBlank()) {
      throw new InvariantViolationException("Party name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new InvariantViolationException(
          "Party name exceeds %d characters".formatted(MAX_NAME_LENGTH));
    }
    if (address == null) {
      throw new InvariantViolationException("Party address must not be null");
    }
    if (vatId != null) {
      vatId = vatId.trim();
      if (!EU_VAT_ID.matcher(vatId).matches()) {
        throw new InvariantViolationException(
            "VAT id '%s' is not a valid EU VAT id".formatted(Texts.safeEcho(vatId)));
      }
    }
    if (email == null) {
      throw new InvariantViolationException(
          "Party email must not be null; use Optional.empty() when absent");
    }
    if (email.isPresent()) {
      String raw = email.get();
      // Guard the length before trim()/matches(): a caller-supplied value must not force an
      // unbounded copy or an unbounded regex scan before it is rejected.
      if (raw.length() > MAX_EMAIL_LENGTH) {
        throw new InvariantViolationException(
            "Party email exceeds %d characters".formatted(MAX_EMAIL_LENGTH));
      }
      String trimmed = raw.trim();
      if (!EMAIL_SHAPE.matcher(trimmed).matches()) {
        throw new InvariantViolationException(
            "Email '%s' is not a valid email address".formatted(Texts.safeEcho(trimmed)));
      }
      email = Optional.of(trimmed);
    }
    if (electronicAddress == null) {
      throw new InvariantViolationException(
          "Party electronicAddress must not be null; use Optional.empty() when absent");
    }
  }

  /**
   * Pre-M4 shape, kept so existing callers compile unchanged: {@code electronicAddress} defaults to
   * absent.
   */
  public Party(String name, Address address, String vatId, Optional<String> email) {
    this(name, address, vatId, email, Optional.empty());
  }

  /**
   * Pre-M3 shape, kept so existing callers compile unchanged: {@code email} and {@code
   * electronicAddress} both default to absent.
   */
  public Party(String name, Address address, String vatId) {
    this(name, address, vatId, Optional.empty(), Optional.empty());
  }
}
