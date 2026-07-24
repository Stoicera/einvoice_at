package com.stoicera.einvoice.mapping.json;

/**
 * Thrown when JSON input does not conform to the canonical-invoice JSON shape documented in {@code
 * samples/README.md}: malformed syntax, an unknown property, a JSON node of the wrong type (e.g. a
 * number where a money/quantity field requires a string), an unmapped enumeration value, or an
 * unparsable amount/date/currency string.
 *
 * <p>This is strictly a boundary-shape exception. A JSON document that is well-formed but describes
 * an invoice violating a domain invariant (a checksum-invalid IBAN, a blank invoice number, a
 * missing seller) is {@code core}'s concern: {@link InvoiceJsonReader} lets {@code
 * com.stoicera.einvoice.core.InvariantViolationException} propagate untouched in that case — the
 * domain's voice is never rewrapped as a JSON-shape problem.
 *
 * <p>Messages carry JSON pointer-ish context (the offending field path) so a caller can locate the
 * problem, but never echo the raw payload value beyond the offending field/property name — the
 * input is untrusted and the message must stay safe to log. The echoed property name and field path
 * are themselves length-bounded (core's bounded-echo discipline), so an attacker-controlled
 * document with a huge property name cannot force an unbounded-length exception message.
 */
public final class InvoiceJsonException extends RuntimeException {

  public InvoiceJsonException(String message) {
    super(message);
  }

  public InvoiceJsonException(String message, Throwable cause) {
    super(message, cause);
  }
}
