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
            error(EErrorLevel.ERROR, "PEPPOL-EN16931-R020", "Endpoint identifier scheme missing"));

    assertThat(finding.ruleId()).isEqualTo("PEPPOL-EN16931-R020");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageEn()).isEqualTo("Endpoint identifier scheme missing");
    assertThat(finding.messageDe())
        .isEqualTo("Peppol BIS Billing 3.0: Endpoint identifier scheme missing");
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
