package com.stoicera.einvoice.validation.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.schematron.svrl.SVRLFailedAssert;
import com.helger.schematron.svrl.jaxb.FailedAssert;
import com.helger.schematron.svrl.jaxb.Text;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SVRL-to-{@link Finding} mapping in {@link SchematronRuleCatalog}, exercised
 * with synthetic {@link SVRLFailedAssert}s so both the catalogued and the fallback paths are
 * covered without depending on which rules the {@code .sch} happens to contain today.
 */
class SchematronRuleCatalogTest {

  /**
   * Builds a synthetic failed assert the way ph-schematron would, from a JAXB {@code FailedAssert}.
   */
  private static SVRLFailedAssert failedAssert(String id, String location, String text) {
    Text svrlText = new Text();
    svrlText.addContent(text);
    FailedAssert failedAssert = new FailedAssert();
    failedAssert.setId(id);
    failedAssert.setLocation(location);
    failedAssert.setText(svrlText);
    return new SVRLFailedAssert(failedAssert);
  }

  @Test
  void catalogedRuleMapsToItsOwnBilingualMessagesNotTheRawSvrlText() {
    Finding finding =
        SchematronRuleCatalog.toFinding(
            failedAssert("AT-B2G-01", "/eb:Invoice", "raw svrl text must be ignored here"));

    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-01");
    assertThat(finding.location()).isEqualTo("/eb:Invoice");
    assertThat(finding.messageDe()).startsWith("Auftragsreferenz fehlt:");
    assertThat(finding.messageEn()).startsWith("Order reference missing:");
    assertThat(finding.messageDe()).doesNotContain("raw svrl text");
  }

  @Test
  void uncatalogedRuleWithOverlongSvrlTextIsBoundedInsteadOfThrowing() {
    // P1-2 latent twin: an uncatalogued assert whose SVRL text (possibly document-derived) exceeds
    // Finding's 4096-char cap must be bounded, not thrown, so the fallback keeps the finding.
    String overlong = "x".repeat(5000);

    Finding finding =
        SchematronRuleCatalog.toFinding(failedAssert("AT-B2G-99", "/eb:Invoice", overlong));

    assertThat(finding.ruleId()).isEqualTo("AT-B2G-99");
    assertThat(finding.messageDe().length()).isLessThanOrEqualTo(4096);
    assertThat(finding.messageEn().length()).isLessThanOrEqualTo(4096);
    assertThat(finding.messageDe()).endsWith("…");
    assertThat(finding.messageEn()).endsWith("…");
  }

  @Test
  void uncatalogedRuleWithBlankSvrlTextFallsBackToFixedTextInsteadOfThrowing() {
    // T1-carried: a blank (empty/whitespace-only) SVRL text would be passed straight to Finding.of,
    // whose non-blank invariant then throws — breaking the validator's never-throws contract. The
    // fixed fallback text keeps a usable, bilingual finding with the id preserved.
    Finding finding =
        SchematronRuleCatalog.toFinding(failedAssert("AT-B2G-99", "/eb:Invoice", "   "));

    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-99");
    assertThat(finding.messageDe()).isNotBlank();
    assertThat(finding.messageEn()).isNotBlank();
  }

  @Test
  void uncatalogedRuleFallsBackToRawSvrlTextInBothLanguagesKeepingTheId() {
    Finding finding =
        SchematronRuleCatalog.toFinding(
            failedAssert("AT-B2G-99", "/eb:Invoice/eb:Detail", "Unmapped rule fired"));

    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    // The id is kept exactly as SVRL reported it — an uncatalogued finding is never dropped.
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-99");
    assertThat(finding.location()).isEqualTo("/eb:Invoice/eb:Detail");
    assertThat(finding.messageDe()).isEqualTo("Unmapped rule fired");
    assertThat(finding.messageEn()).isEqualTo("Unmapped rule fired");
  }
}
