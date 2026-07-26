package com.stoicera.einvoice.aiassist.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The scrubber's guarantee is universally quantified — "no IBAN reaches the provider, whatever the
 * message around it looks like" — so it is stated as a property rather than as a handful of
 * examples. Example-based tests can only ever show that the shapes someone thought of are masked.
 *
 * <p>The IBAN generator is the same ISO 13616 construction {@code core}'s {@code IbanPropertyTest}
 * uses, so the values here are the ones the domain model would accept as real.
 */
class PiiScrubberPropertyTest {

  /** Structurally valid Austrian IBANs (AT + 2 check digits + 16 BBAN digits). */
  @Provide
  Arbitrary<String> validAustrianIbans() {
    return Arbitraries.strings()
        .numeric()
        .ofLength(16)
        .map(PiiScrubberPropertyTest::withCheckDigits);
  }

  private static String withCheckDigits(String bban) {
    String numeric = bban + "102900";
    BigInteger remainder = new BigInteger(numeric).mod(BigInteger.valueOf(97));
    int check = 98 - remainder.intValueExact();
    return "AT%02d%s".formatted(check, bban);
  }

  /** Message text a Schematron or XSD diagnostic might wrap a value in. */
  @Provide
  Arbitrary<String> messageFragments() {
    return Arbitraries.of(
        "Wert '%s' ist ungültig",
        "Empfängerkonto %s nicht plausibel",
        "AT-B2G-02: %s",
        "[%s]",
        "%s",
        "Prüfsumme für %s fehlgeschlagen (PEPPOL-EN16931-R010)",
        "IBAN=%s;BIC=BKAUATWW");
  }

  @Property
  void noGeneratedIbanSurvivesScrubbing(
      @ForAll("validAustrianIbans") String iban, @ForAll("messageFragments") String fragment) {
    String scrubbed = PiiScrubber.scrub(fragment.formatted(iban));

    assertThat(scrubbed).doesNotContain(iban).contains("[IBAN]");
  }

  @Property
  void noGeneratedIbanSurvivesEvenWithCallerLiteralsInPlay(
      @ForAll("validAustrianIbans") String iban, @ForAll("messageFragments") String fragment) {
    // Literal redaction runs before pattern masking; this pins that the earlier pass cannot shift
    // the
    // text in a way that stops the IBAN pattern matching (e.g. by replacing a delimiter around it).
    String scrubbed =
        PiiScrubber.scrub(
            "Stoicera Software GesbR: " + fragment.formatted(iban),
            Set.of("Stoicera Software GesbR"));

    assertThat(scrubbed).doesNotContain(iban).contains("[IBAN]").doesNotContain("Stoicera");
  }

  @Property
  void scrubbingIsIdempotent(
      @ForAll("validAustrianIbans") String iban, @ForAll("messageFragments") String fragment) {
    // Matters because a cached explanation is keyed on the scrubbed text: if scrubbing twice
    // differed
    // from scrubbing once, a re-scrub anywhere in the chain would silently miss the cache.
    String once = PiiScrubber.scrub(fragment.formatted(iban));

    assertThat(PiiScrubber.scrub(once)).isEqualTo(once);
  }

  @Property
  void ruleIdentifiersAreNeverMasked(@ForAll("ruleIds") String ruleId) {
    assertThat(PiiScrubber.scrub("Regel " + ruleId + " verletzt")).contains(ruleId);
  }

  /** Every rule-id shape this platform actually emits, from both rule sets. */
  @Provide
  Arbitrary<String> ruleIds() {
    return Arbitraries.of(
        "AT-B2G-01",
        "AT-B2G-02",
        "AT-B2G-03",
        "AT-B2G-04",
        "AT-B2G-05",
        "XSD-01",
        "XML-01",
        "XML-02",
        "CONV-01",
        "CONV-04",
        "PEPPOL-01",
        "PEPPOL-EN16931-R010",
        "PEPPOL-EN16931-R020",
        "UBL-CR-412",
        "BR-01",
        "BR-CO-10");
  }
}
