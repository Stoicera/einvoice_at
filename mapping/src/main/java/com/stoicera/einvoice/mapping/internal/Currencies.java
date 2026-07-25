package com.stoicera.einvoice.mapping.internal;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.internal.Texts;
import com.stoicera.einvoice.core.money.Money;
import java.util.Currency;

/**
 * Internal — not API.
 *
 * <p>Turns a currency code read out of a <em>foreign</em> invoice document into a {@link Currency},
 * or into a domain rejection.
 *
 * <h2>Why this is not a one-line call to the JDK</h2>
 *
 * <p>{@link Currency#getInstance(String)} throws a raw {@link IllegalArgumentException} for
 * anything that is not an ISO 4217 code. Both reverse mappers used to call it directly on a value
 * taken straight from an upload, and neither format constrains that value at the point it is read:
 * {@code cbc:DocumentCurrencyCode} is an unconstrained string in UBL 2.1 (the code list is a
 * Schematron matter), and while the ebInterface XSD does restrict {@code InvoiceCurrency}, both
 * adapters read with schema validation deliberately off, because validating is the validation
 * module's job.
 *
 * <p>The consequence was a reachable crash: {@code POST /convert} answered <strong>500</strong> for
 * a document that was merely invalid, and logged a stack trace per request — while every other bad
 * value on the same path (an IBAN, a VAT rate, a missing party) raised {@link
 * InvariantViolationException} and became a well-described 422. The currency was the one place a
 * JDK factory was trusted to behave like a {@code core} invariant. M4 hostile review, finding F2.
 *
 * <p>The JDK's own message echoes the offending value <em>unbounded</em> — a 5 000-character
 * "currency code" produces a 5 000-character exception message, which then lands in a log line. The
 * echo here goes through {@link Texts#safeEcho}, the same bounded-echo discipline {@code core}
 * uses, so neither the caller's response nor the log can be inflated by hostile input.
 *
 * <p>Note that {@code mapping}'s JSON boundary does <em>not</em> use this helper, and should not:
 * {@code InvoiceJsonReader} reports the same condition as an {@code InvoiceJsonException} naming
 * the offending <em>field</em>, which is the right vocabulary for a caller who sent us JSON and the
 * reason that path never had this bug.
 */
public final class Currencies {

  private Currencies() {}

  /**
   * The currency for {@code code}, or {@link Money#EUR} when the document states none.
   *
   * <p>An absent code defaults; a <em>present but unusable</em> one is rejected. The distinction
   * matters: a document that says nothing about its currency is incomplete in a way EN 16931 lets
   * the reader resolve, whereas a document that says {@code "BOGUS"} has stated something false,
   * and silently substituting euros for it would invent an amount's meaning.
   *
   * @param code the raw code as the source document carried it, or {@code null} when absent
   * @return the resolved currency, never {@code null}
   * @throws InvariantViolationException {@code code} is present but is not an ISO 4217 code
   */
  public static Currency parseOrDefault(String code) {
    if (code == null) {
      return Money.EUR;
    }
    try {
      return Currency.getInstance(code.trim());
    } catch (IllegalArgumentException e) {
      throw new InvariantViolationException(
          "Currency code '%s' is not a valid ISO 4217 currency code"
              .formatted(Texts.safeEcho(code)));
    }
  }
}
