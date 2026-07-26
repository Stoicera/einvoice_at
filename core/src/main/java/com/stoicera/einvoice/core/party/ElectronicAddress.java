package com.stoicera.einvoice.core.party;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.text.Texts;
import java.util.regex.Pattern;

/**
 * A party's electronic address — EN 16931 BT-34 (seller) / BT-49 (buyer), together with its
 * mandatory scheme identifier BT-34-1 / BT-49-1.
 *
 * <p>This is the address a document is <em>routed</em> to on a network such as Peppol, and it is
 * deliberately not the same thing as {@link Party#email()} (BT-43/BT-58), which is a human contact
 * address. EN 16931 keeps them apart and so does this model: an invoice can carry either, both, or
 * neither.
 *
 * <p><strong>Why it is caller-supplied and never derived.</strong> Peppol BIS Billing 3.0 requires
 * both endpoints, and it would be easy to synthesise one from a party's VAT id. This model does
 * not: an electronic address identifies a mailbox on a network, and a VAT id is not one — a party
 * can be VAT-registered and not reachable on Peppol at all, or reachable under a completely
 * different identifier. Inventing it would be exactly the kind of derived-from-thin-air data
 * ADR-0003 exists to forbid, and it would produce a document that routes somewhere real and wrong.
 * Where the value is genuinely unknown, the field stays absent and the conversion report says so.
 *
 * @param scheme the EAS scheme identifier (BT-34-1/BT-49-1), a four-digit code from the Peppol
 *     Electronic Address Scheme code list, e.g. {@code "9915"}; membership in that list is a
 *     Peppol-profile question the validation module answers, not an invariant here
 * @param value the address itself within that scheme (BT-34/BT-49)
 */
public record ElectronicAddress(String scheme, String value) {

  /**
   * The EAS code list is four-digit numeric throughout (verified against the code list the
   * OpenPeppol Schematron itself carries). Shape only: <em>which</em> four-digit codes are current
   * is a rule-set question that changes with each Peppol release, so it belongs to the validation
   * module, not to a domain invariant that would go stale.
   */
  private static final Pattern EAS_SCHEME = Pattern.compile("[0-9]{4}");

  private static final int MAX_VALUE_LENGTH = 256;

  public ElectronicAddress {
    if (scheme == null || scheme.isBlank()) {
      throw new InvariantViolationException(
          "Electronic address scheme (BT-34-1/BT-49-1) must not be blank");
    }
    // Guard the length before trim(): a caller-supplied value must not force an unbounded copy
    // before it is rejected (same pre-normalization-guard pattern as Address/PaymentMeans/Party).
    if (scheme.length() > MAX_VALUE_LENGTH) {
      throw new InvariantViolationException(
          "Electronic address scheme exceeds %d characters".formatted(MAX_VALUE_LENGTH));
    }
    scheme = scheme.trim();
    if (!EAS_SCHEME.matcher(scheme).matches()) {
      throw new InvariantViolationException(
          "Electronic address scheme '%s' is not a four-digit EAS code"
              .formatted(Texts.safeEcho(scheme)));
    }
    if (value == null || value.isBlank()) {
      throw new InvariantViolationException("Electronic address (BT-34/BT-49) must not be blank");
    }
    if (value.length() > MAX_VALUE_LENGTH) {
      throw new InvariantViolationException(
          "Electronic address exceeds %d characters".formatted(MAX_VALUE_LENGTH));
    }
    value = value.trim();
  }
}
