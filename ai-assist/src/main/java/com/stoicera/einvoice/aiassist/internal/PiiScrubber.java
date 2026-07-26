package com.stoicera.einvoice.aiassist.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal and financial data out of text before it is sent to a third-party LLM provider.
 *
 * <p>ENGINEERING_STANDARDS §8 forbids sending customer data to a provider without opt-in, and SPEC
 * §6 requires names and IBANs masked. This class is where that is actually done, and it has real
 * work: a Schematron or XSD diagnostic <em>quotes the offending document value verbatim</em> — that
 * is what makes those messages useful — so a finding's message text routinely contains an IBAN, a
 * UID or an e-mail address even though no document is ever sent.
 *
 * <h2>Two mechanisms, and the honest limits of each</h2>
 *
 * <ol>
 *   <li><strong>Literal redaction</strong> ({@link #scrub(String, Collection)}): the caller passes
 *       values it knows are sensitive — the seller and buyer names off the stored invoice — and
 *       every occurrence is replaced, case-insensitively. This is the only reliable way to mask a
 *       <em>name</em>: "Bundesbeschaffung GmbH" and "Auftragsreferenz" are the same shape to a
 *       regular expression, and a pattern that caught the first would eat half the rule text.
 *   <li><strong>Pattern masking</strong> (always applied): IBANs, e-mail addresses, EU VAT ids and
 *       long digit runs, which do have reliable shapes.
 * </ol>
 *
 * <p><strong>Known limit, stated rather than papered over:</strong> when the caller supplies no
 * literals — the public validator, where nothing about the upload is retained — a personal name
 * embedded in a rule message is <em>not</em> masked, because nothing here can tell it apart from
 * ordinary German prose. The structural mitigation is that the document itself never leaves this
 * platform: only the finding does. Recorded in {@code docs/privacy.md} and ADR-0010.
 *
 * <p>Masks are German words, matching the language of the text they appear in and of the
 * explanation the model is asked to produce.
 */
public final class PiiScrubber {

  /** What a masked value is replaced with. Bracketed so a model reads them as placeholders. */
  static final String MASK_NAME = "[NAME]";

  static final String MASK_EMAIL = "[E-MAIL]";
  static final String MASK_IBAN = "[IBAN]";
  static final String MASK_VAT_ID = "[UID]";
  static final String MASK_NUMBER = "[NUMMER]";

  /**
   * Shortest literal worth redacting. A one- or two-character "sensitive value" would match inside
   * half the words in the text and destroy the rule message; a caller passing one has a bug, and
   * silently ignoring it is better than silently shredding the input.
   */
  private static final int MIN_LITERAL_LENGTH = 3;

  /**
   * An IBAN: two letters, two check digits, then 10–30 alphanumerics (the ISO 13616 range, Norway's
   * 15 through Malta's 31 in total). Matched before the VAT-id pattern, which its prefix also fits.
   */
  private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}\\b");

  /**
   * An e-mail address — deliberately loose. Precision costs nothing here and a false positive only
   * masks something that looked like an address.
   */
  private static final Pattern EMAIL =
      Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

  /**
   * An EU VAT id: a country prefix and its national number, including Austria's {@code ATU} + 8
   * digits.
   *
   * <p><strong>Deliberately not the widest possible pattern.</strong> A general {@code
   * [A-Z]{2}[A-Z0-9]{8,12}} would also swallow the rule identifiers that are the whole point of the
   * explanation — and a masked {@code PEPPOL-EN16931-R010} would make the finding unexplainable
   * while looking like privacy work. Requiring digits after the prefix keeps rule ids (which are
   * letter-and-hyphen shaped) out of range.
   */
  private static final Pattern VAT_ID =
      Pattern.compile("\\b(?:ATU[0-9]{8}|[A-Z]{2}[0-9]{8,12})\\b");

  /**
   * Seven or more consecutive digits: account numbers, {@code Lieferantennummer}s, phone numbers,
   * tax numbers. Seven is chosen against what must survive, not arbitrarily — an ISO date ({@code
   * 2026-07-26}) has no run longer than four, and the numeric part of an EN 16931 rule id ({@code
   * EN16931}) is five. The cost is that an amount of a million or more is masked too; erring that
   * way round is the right side to err on.
   */
  private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{7,}");

  private PiiScrubber() {}

  /** Pattern masking only — for callers that know nothing about the document behind the finding. */
  public static String scrub(String text) {
    return scrub(text, List.of());
  }

  /**
   * Redacts {@code sensitiveLiterals} (case-insensitively, longest first) and then applies pattern
   * masking.
   *
   * <p>Longest first matters: with "Stoicera" and "Stoicera Software GesbR" both supplied, masking
   * the short one first would leave "{@code [NAME] Software GesbR}" — the company name still
   * legible. A null or too-short literal is skipped rather than throwing; see {@link
   * #MIN_LITERAL_LENGTH}.
   *
   * @param text the text to scrub; {@code null} yields {@code null}
   * @param sensitiveLiterals values the caller knows to be personal data, e.g. party names
   */
  public static String scrub(String text, Collection<String> sensitiveLiterals) {
    if (text == null || text.isBlank()) {
      return text;
    }
    String scrubbed = text;
    for (String literal : longestFirst(sensitiveLiterals)) {
      scrubbed =
          Pattern.compile(Pattern.quote(literal), Pattern.CASE_INSENSITIVE)
              .matcher(scrubbed)
              .replaceAll(Matcher.quoteReplacement(MASK_NAME));
    }
    // Order is load-bearing: an IBAN's leading "AT61" also satisfies the VAT-id pattern, so IBAN
    // runs
    // first or an IBAN would be reported as a masked UID. E-mail runs before both because an
    // address
    // can contain digit runs that would otherwise be masked inside it.
    scrubbed = EMAIL.matcher(scrubbed).replaceAll(MASK_EMAIL);
    scrubbed = IBAN.matcher(scrubbed).replaceAll(MASK_IBAN);
    scrubbed = VAT_ID.matcher(scrubbed).replaceAll(MASK_VAT_ID);
    scrubbed = LONG_DIGIT_RUN.matcher(scrubbed).replaceAll(MASK_NUMBER);
    return scrubbed;
  }

  private static List<String> longestFirst(Collection<String> literals) {
    if (literals == null || literals.isEmpty()) {
      return List.of();
    }
    List<String> usable = new ArrayList<>();
    for (String literal : literals) {
      if (literal != null && literal.strip().length() >= MIN_LITERAL_LENGTH) {
        usable.add(literal.strip());
      }
    }
    usable.sort(Comparator.comparingInt(String::length).reversed());
    return usable;
  }
}
