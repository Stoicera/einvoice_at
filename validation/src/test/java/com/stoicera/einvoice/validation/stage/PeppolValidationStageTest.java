package com.stoicera.einvoice.validation.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helger.diagnostics.error.SingleError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.DocumentFormat;
import com.stoicera.einvoice.validation.RuleIds;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.internal.BoundedText;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit-level behaviour of {@link PeppolValidationStage}. The stage's real work — actually judging a
 * document against the OpenPeppol rule set — is proven end to end by {@code PeppolRoundTripTest}
 * and the golden-file corpus; what is pinned here is the wiring around it: the version pin, the
 * dispatch, and the translation of one foreign diagnostic into a {@code Finding}.
 */
class PeppolValidationStageTest {

  private final PeppolValidationStage stage = new PeppolValidationStage();

  /**
   * The pin must resolve. A phive-rules upgrade that drops {@link
   * PeppolValidationStage#RULE_SET_VERSION} would otherwise surface as a runtime failure on the
   * first upload rather than as a failing build.
   */
  @Test
  void thePinnedRuleSetIsRegistered() {
    assertThat(PeppolValidationStage.isPinnedRuleSetRegistered()).isTrue();
    assertThat(PeppolValidationStage.RULE_SET_VERSION).isEqualTo("2025.11");
  }

  @Test
  void refusesToValidateANonUblDocument() {
    ValidationContext ctx =
        new ValidationContext(
            ("<?xml version=\"1.0\"?><Invoice xmlns=\"http://www.ebinterface.at/schema/6p1/\"/>")
                .getBytes(StandardCharsets.UTF_8));
    ctx.format(DocumentFormat.EBINTERFACE_61);

    assertThatThrownBy(() -> stage.apply(ctx))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UBL");
  }

  /** A rule's own identifier is what the finding is keyed on — never replaced by a local code. */
  @Test
  void usesTheSchematronAssertionIdAsTheRuleId() {
    Finding finding =
        PeppolValidationStage.toFinding(
            error(
                EErrorLevel.ERROR,
                "PEPPOL-EN16931-R020",
                "Seller electronic address MUST be provided"));

    assertThat(finding.ruleId()).isEqualTo("PEPPOL-EN16931-R020");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    // The official English wording is preserved verbatim, translated or not (ADR-0007).
    assertThat(finding.messageEn()).isEqualTo("Seller electronic address MUST be provided");
  }

  /**
   * M5: a catalogued rule gets a real German message instead of M4's German frame around English.
   * This is the milestone closing the gap M4 recorded honestly rather than papering over.
   */
  @Test
  void usesTheGermanTranslationWhenTheRuleIsCatalogued() {
    Finding finding =
        PeppolValidationStage.toFinding(
            error(
                EErrorLevel.ERROR,
                "PEPPOL-EN16931-R020",
                "Seller electronic address MUST be provided"));

    assertThat(finding.messageDe())
        .isEqualTo(
            "Peppol BIS Billing 3.0: Die elektronische Adresse des Verkäufers (BT-34) muss"
                + " angegeben werden.")
        // A German message that still quotes the English rule text would mean the lookup silently
        // missed.
        .doesNotContain("MUST be provided");
  }

  @Test
  void keepsTheGermanFrameAroundEnglishForAnUncataloguedRule() {
    // The fallback is deliberately unchanged from M4: worse than a translation, never wrong. Uses a
    // rule id the catalog does not cover, so this test keeps testing the fallback even as the
    // catalog
    // grows.
    Finding finding =
        PeppolValidationStage.toFinding(
            error(
                EErrorLevel.WARN, "UBL-CR-412", "cbc:CompanyLegalFormCode should not be present"));

    assertThat(finding.messageDe())
        .isEqualTo("Peppol BIS Billing 3.0: cbc:CompanyLegalFormCode should not be present");
  }

  @Test
  void everyCataloguedGermanMessageIsUsableAsAFinding() {
    // Guards the whole catalog at once against the two ways an entry can be wrong in a way no
    // individual test would notice: a blank value, or one long enough that Finding's own cap would
    // throw out of validate() and break the never-throws contract.
    assertThat(PeppolMessagesDe.all()).isNotEmpty();
    assertThat(PeppolMessagesDe.all())
        .allSatisfy(
            (ruleId, german) -> {
              assertThat(german).as("German message for %s", ruleId).isNotBlank();
              assertThat(german)
                  .as("German message for %s must fit Finding's cap", ruleId)
                  .hasSizeLessThanOrEqualTo(BoundedText.MAX_MESSAGE_DETAIL);
              // Constructing the finding is the real assertion: it runs Finding's own invariants.
              assertThat(Finding.of(Severity.ERROR, ruleId, null, german, "en")).isNotNull();
            });
  }

  @Test
  void theCatalogCoversEveryPeppolSpecificRuleOfThePinnedRuleSet() {
    // The PEPPOL-EN16931-R* layer is small and entirely relevant to an Austrian filer, so partial
    // coverage of it would be an oversight rather than a scoping decision. The EN 16931 layer is
    // deliberately partial — see PeppolMessagesDe's Javadoc.
    assertThat(PeppolMessagesDe.all().keySet())
        .contains(
            "PEPPOL-EN16931-R001",
            "PEPPOL-EN16931-R002",
            "PEPPOL-EN16931-R003",
            "PEPPOL-EN16931-R004",
            "PEPPOL-EN16931-R005",
            "PEPPOL-EN16931-R007",
            "PEPPOL-EN16931-R008",
            "PEPPOL-EN16931-R010",
            "PEPPOL-EN16931-R020",
            "PEPPOL-EN16931-R040",
            "PEPPOL-EN16931-R041",
            "PEPPOL-EN16931-R042",
            "PEPPOL-EN16931-R043",
            "PEPPOL-EN16931-R044",
            "PEPPOL-EN16931-R046",
            "PEPPOL-EN16931-R051",
            "PEPPOL-EN16931-R053",
            "PEPPOL-EN16931-R054",
            "PEPPOL-EN16931-R055",
            "PEPPOL-EN16931-R061",
            "PEPPOL-EN16931-R080",
            "PEPPOL-EN16931-R100",
            "PEPPOL-EN16931-R101",
            "PEPPOL-EN16931-R110",
            "PEPPOL-EN16931-R111",
            "PEPPOL-EN16931-R120",
            "PEPPOL-EN16931-R121",
            "PEPPOL-EN16931-R130");
  }

  /**
   * M5 hostile review, F9. A test with this name existed and asserted that {@code forRule("BR-02")}
   * was present and {@code forRule("does-not-exist")} was empty — a lookup test wearing a bounding
   * test's name, which is worse than no test, because the test list read as though the case were
   * covered. This one constructs a translation no real entry has and asserts the truncation.
   */
  @Test
  void boundsAnOverlongTranslationTheSameWayAsForeignText() {
    String overlong = "Ü".repeat(BoundedText.MAX_MESSAGE_DETAIL + 500);

    String bounded = PeppolValidationStage.boundedGerman(overlong);

    assertThat(bounded).hasSize(BoundedText.MAX_MESSAGE_DETAIL).endsWith("…");
    // The reason the cap exists at all: Finding throws above its own limit, and toFinding runs
    // inside validate(), whose contract is that it never throws. The bounded value must be usable.
    assertThat(
            Finding.of(Severity.ERROR, "BR-02", null, "Peppol BIS Billing 3.0: " + bounded, "en"))
        .isNotNull();
  }

  @Test
  void leavesATranslationThatFitsExactlyAsItIs() {
    // The other side of the boundary, so the cap cannot silently start truncating real entries.
    String exact = "Ü".repeat(BoundedText.MAX_MESSAGE_DETAIL);

    assertThat(PeppolValidationStage.boundedGerman(exact)).isEqualTo(exact);
  }

  @Test
  void looksTheTranslationUpByTheRuleSetsOwnAssertionId() {
    // What the old boundsAnOverlongTranslation… test actually asserted, under its real name.
    assertThat(PeppolMessagesDe.forRule("BR-02")).isPresent();
    assertThat(PeppolMessagesDe.forRule("does-not-exist")).isEmpty();
  }

  /**
   * M5 hostile review, F10. {@code PeppolMessagesDe.SIZE_NOTE} is rendered into that class's
   * Javadoc through {@code {@value}} and described there as "asserted by the stage test". It was
   * asserted by nothing — the constant appeared nowhere outside its own file — and it was wrong: it
   * said 78, the catalogue held 80. The number is also repeated in {@code docs/worklog.md},
   * including in the checklist for the mandatory 2026-08-17 Peppol 2026.5 upgrade, so the count a
   * future maintainer re-verifies against the new assertion texts was the wrong count.
   *
   * <p>This is what makes the sentence true. The assertion is deliberately on the exact number
   * rather than a range: a documentation constant that drifts silently is the thing being fixed.
   */
  @Test
  void theDocumentedCatalogueSizeIsTheActualCatalogueSize() {
    assertThat(PeppolMessagesDe.SIZE_NOTE)
        .withFailMessage(
            "PeppolMessagesDe.SIZE_NOTE says \"%s\" but the catalogue holds %d entries. Update the"
                + " constant — it is rendered into the class Javadoc and quoted in docs/worklog.md.",
            PeppolMessagesDe.SIZE_NOTE, PeppolMessagesDe.all().size())
        .isEqualTo(PeppolMessagesDe.all().size() + " of them");
  }

  @Test
  void fallsBackToPeppol01WhenTheDiagnosticCarriesNoAssertionId() {
    assertThat(PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, null, "boom")).ruleId())
        .isEqualTo(RuleIds.PEPPOL_01);
    assertThat(PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, "  ", "boom")).ruleId())
        .isEqualTo(RuleIds.PEPPOL_01);
  }

  @Test
  void mapsBelowErrorLevelsToAWarning() {
    assertThat(
            PeppolValidationStage.toFinding(error(EErrorLevel.WARN, "UBL-CR-412", "should not"))
                .severity())
        .isEqualTo(Severity.WARN);
    assertThat(
            PeppolValidationStage.toFinding(error(EErrorLevel.FATAL_ERROR, "BR-01", "must"))
                .severity())
        .isEqualTo(Severity.ERROR);
  }

  /**
   * A Schematron message can echo document content verbatim, so it is bounded before it reaches
   * {@code Finding} — whose own caps throw when exceeded. Same reachable-crash class the M2 hostile
   * review closed for the XSD stage; closed here by construction rather than rediscovered.
   */
  @Test
  void boundsForeignTextBeforeItReachesTheFinding() {
    String hostile = "x".repeat(BoundedText.MAX_MESSAGE_DETAIL * 3);

    Finding finding = PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, "BR-01", hostile));

    assertThat(finding.messageEn()).hasSize(BoundedText.MAX_MESSAGE_DETAIL);
  }

  @Test
  void boundsAnOverlongRuleIdRatherThanLettingFindingReject() {
    String hostileId = "R".repeat(BoundedText.MAX_RULE_ID * 3);

    Finding finding = PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, hostileId, "boom"));

    assertThat(finding.ruleId()).hasSize(BoundedText.MAX_RULE_ID);
  }

  @Test
  void fallsBackToAFixedDetailWhenTheRuleSetSuppliesNoText() {
    Finding finding = PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, "BR-01", null));

    assertThat(finding.messageEn()).contains("Unbekannter Peppol-Prüffehler");
  }

  private static com.helger.diagnostics.error.IError error(
      EErrorLevel level, String id, String text) {
    return SingleError.builder().errorLevel(level).errorID(id).errorText(text).build();
  }
}
