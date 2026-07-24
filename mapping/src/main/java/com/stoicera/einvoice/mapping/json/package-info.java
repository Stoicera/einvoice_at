/**
 * Strict JSON boundary reader for the canonical-invoice document shape ({@link
 * com.stoicera.einvoice.mapping.json.InvoiceJsonReader}), documented in {@code samples/README.md}.
 *
 * <p><strong>Boundary contract.</strong> This package is where untrusted JSON bytes first become a
 * {@code core} {@code Invoice} — it is deliberately stricter than Jackson's defaults (unknown
 * properties rejected, money/quantity fields must be JSON strings, never numbers) and it speaks
 * with two distinct voices on failure: {@link
 * com.stoicera.einvoice.mapping.json.InvoiceJsonException} for a shape problem (malformed syntax,
 * wrong JSON type, an unmapped enum value), and {@code core}'s own {@code
 * InvariantViolationException} — let through untouched, never rewrapped — for a well-formed
 * document that describes a domain-invalid invoice. Exception messages may name an offending
 * field/property path but never echo raw payload values, and the echoed path itself is
 * length-bounded, so a hostile document cannot force an unbounded-length exception message.
 */
package com.stoicera.einvoice.mapping.json;
