package com.stoicera.einvoice.aiassist.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The scrubber is the module's privacy boundary, so these tests are written from both sides: what
 * must never survive, and what must never be destroyed. The second half matters as much — a
 * scrubber that also masked rule ids would look like privacy work while making every explanation
 * useless.
 */
class PiiScrubberTest {

  /** The canonical test IBAN this repository already uses in its samples and corpus. */
  private static final String IBAN = "AT611904300234573201";

  @Test
  void masksAnIban() {
    assertThat(PiiScrubber.scrub("Die IBAN " + IBAN + " ist ungültig."))
        .isEqualTo("Die IBAN [IBAN] ist ungültig.")
        .doesNotContain(IBAN);
  }

  @Test
  void masksAnIbanEvenWhenTheMessageQuotesIt() {
    // The realistic shape: a Schematron message echoing the offending value in quotes.
    assertThat(PiiScrubber.scrub("Wert '" + IBAN + "' verletzt Regel AT-B2G-02"))
        .isEqualTo("Wert '[IBAN]' verletzt Regel AT-B2G-02");
  }

  @Test
  void masksAnEmailAddress() {
    assertThat(PiiScrubber.scrub("Biller-E-Mail office@stoicera-software.at fehlt"))
        .isEqualTo("Biller-E-Mail [E-MAIL] fehlt");
  }

  @Test
  void masksAnAustrianVatId() {
    assertThat(PiiScrubber.scrub("UID ATU12345678 unbekannt")).isEqualTo("UID [UID] unbekannt");
  }

  @Test
  void masksAGenericEuVatId() {
    assertThat(PiiScrubber.scrub("Käufer-UID DE123456789")).isEqualTo("Käufer-UID [UID]");
  }

  @Test
  void masksALongDigitRun() {
    assertThat(PiiScrubber.scrub("Lieferantennummer 1002345678 fehlt"))
        .isEqualTo("Lieferantennummer [NUMMER] fehlt");
  }

  @Test
  void ibanIsMaskedAsAnIbanNotAsAVatId() {
    // The two patterns overlap on an IBAN's "AT61" prefix; order in the scrubber decides which
    // wins,
    // and reporting an IBAN as a UID would be a (small) lie in the prompt.
    assertThat(PiiScrubber.scrub(IBAN)).isEqualTo("[IBAN]");
  }

  @Test
  void emailWithDigitsIsMaskedWholeRatherThanPartly() {
    // EMAIL runs before LONG_DIGIT_RUN; the other order would leave "[NUMMER]@example.at".
    assertThat(PiiScrubber.scrub("konto12345678@example.at")).isEqualTo("[E-MAIL]");
  }

  @Test
  void keepsRuleIdentifiersIntact() {
    // Non-negotiable: the rule id is the one thing the explanation is actually about.
    String text = "PEPPOL-EN16931-R010, UBL-CR-412, BR-01 und AT-B2G-05 verletzt";
    assertThat(PiiScrubber.scrub(text)).isEqualTo(text);
  }

  @Test
  void keepsIsoDatesAndShortNumbersIntact() {
    String text = "Lieferdatum 2026-07-26, Steuersatz 20 %, Position 2, Betrag 9600.00";
    assertThat(PiiScrubber.scrub(text)).isEqualTo(text);
  }

  @Test
  void redactsCallerSuppliedNames() {
    assertThat(
            PiiScrubber.scrub(
                "Verkäufer Stoicera Software GesbR fehlt eine E-Mail",
                Set.of("Stoicera Software GesbR")))
        .isEqualTo("Verkäufer [NAME] fehlt eine E-Mail");
  }

  @Test
  void redactsNamesCaseInsensitively() {
    assertThat(PiiScrubber.scrub("bundesbeschaffung gmbh", Set.of("Bundesbeschaffung GmbH")))
        .isEqualTo("[NAME]");
  }

  @Test
  void redactsTheLongestNameFirstSoNoRemnantIsLeft() {
    // With the short literal applied first the result would be "[NAME] Software GesbR" — the
    // company
    // still legible. This is why longestFirst exists.
    assertThat(
            PiiScrubber.scrub(
                "Stoicera Software GesbR", List.of("Stoicera", "Stoicera Software GesbR")))
        .isEqualTo("[NAME]");
  }

  @Test
  void redactsNamesContainingRegexMetacharactersLiterally() {
    // A party name is untrusted input; unquoted, "Müller (GmbH & Co. KG)" would be a broken
    // pattern.
    assertThat(PiiScrubber.scrub("Rechnung an Müller (GmbH) [AT]", Set.of("Müller (GmbH) [AT]")))
        .isEqualTo("Rechnung an [NAME]");
  }

  @Test
  void skipsLiteralsTooShortToBeSafe() {
    // A two-character "name" would match inside ordinary words; ignoring it beats shredding the
    // text.
    assertThat(PiiScrubber.scrub("Die Regel AT-B2G-01 ist verletzt", Set.of("AT")))
        .isEqualTo("Die Regel AT-B2G-01 ist verletzt");
  }

  @Test
  void toleratesNullAndBlankLiteralsInTheCollection() {
    assertThat(PiiScrubber.scrub("Stoicera", Arrays.asList(null, "   ", "Stoicera")))
        .isEqualTo("[NAME]");
  }

  @Test
  void passesNullAndBlankTextThrough() {
    assertThat(PiiScrubber.scrub(null)).isNull();
    assertThat(PiiScrubber.scrub("")).isEmpty();
    assertThat(PiiScrubber.scrub("   ")).isEqualTo("   ");
  }

  @Test
  void toleratesANullLiteralCollection() {
    assertThat(PiiScrubber.scrub("IBAN " + IBAN, null)).isEqualTo("IBAN [IBAN]");
  }

  @Test
  void masksEveryOccurrenceNotJustTheFirst() {
    assertThat(PiiScrubber.scrub(IBAN + " und " + IBAN)).isEqualTo("[IBAN] und [IBAN]");
  }
}
