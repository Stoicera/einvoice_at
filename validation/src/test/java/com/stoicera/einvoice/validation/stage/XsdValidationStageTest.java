package com.stoicera.einvoice.validation.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.SingleError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.TestDocuments;
import com.stoicera.einvoice.validation.ValidationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class XsdValidationStageTest {

  private final XsdValidationStage stage = new XsdValidationStage();

  @Test
  void validDocumentProducesNoFindings() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(stage.apply(ctx)).isEmpty();
  }

  @Test
  void structurallyBrokenDocumentYieldsXsdErrorWithGermanLeadIn() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.brokenEbInterface61()));

    List<Finding> findings = stage.apply(ctx);

    assertThat(findings).isNotEmpty();
    assertThat(findings)
        .allSatisfy(
            finding -> {
              assertThat(finding.ruleId()).isEqualTo("EBI61-XSD");
              assertThat(finding.messageDe())
                  .startsWith("Das Dokument verletzt das ebInterface-6.1-Schema: ");
            });
    assertThat(findings)
        .anySatisfy(finding -> assertThat(finding.severity()).isEqualTo(Severity.ERROR));
  }

  @Test
  void severityMappingErrorAndAboveIsError() {
    assertThat(XsdValidationStage.severityOf(EErrorLevel.FATAL_ERROR)).isEqualTo(Severity.ERROR);
    assertThat(XsdValidationStage.severityOf(EErrorLevel.ERROR)).isEqualTo(Severity.ERROR);
  }

  @Test
  void severityMappingBelowErrorIsWarn() {
    assertThat(XsdValidationStage.severityOf(EErrorLevel.WARN)).isEqualTo(Severity.WARN);
    assertThat(XsdValidationStage.severityOf(EErrorLevel.INFO)).isEqualTo(Severity.WARN);
    assertThat(XsdValidationStage.severityOf(EErrorLevel.SUCCESS)).isEqualTo(Severity.WARN);
  }

  @Test
  void detailTextUsesTheParserMessageWhenPresent() {
    IError error = SingleError.builderError().errorText("cvc-complex-type: boom").build();

    assertThat(XsdValidationStage.detailText(error)).isEqualTo("cvc-complex-type: boom");
  }

  @Test
  void detailTextFallsBackWhenTheParserMessageIsMissingOrBlank() {
    IError noText = SingleError.builderError().build();
    IError blankText = SingleError.builderError().errorText("   ").build();

    assertThat(XsdValidationStage.detailText(noText))
        .isEqualTo("Unbekannter Schemafehler (unknown schema error)");
    assertThat(XsdValidationStage.detailText(blankText))
        .isEqualTo("Unbekannter Schemafehler (unknown schema error)");
  }

  @Test
  void structurallyBrokenDocumentYieldsAGenuinelyEnglishMessageEn() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.brokenEbInterface61()));

    List<Finding> findings = stage.apply(ctx);

    assertThat(findings).isNotEmpty();
    assertThat(findings)
        .allSatisfy(
            finding -> {
              // Pinned against the bundled Xerces version for cvc-complex-type.2.4.a, empirically
              // verified: Locale.GERMAN renders "Ungueltiger Content ... gefunden", Locale.ENGLISH
              // renders "Invalid content was found starting with element". If a JDK/Xerces upgrade
              // changes the exact phrasing, re-verify both locale renderings empirically (do not
              // just widen the assertion) and update the pinned phrase together with the ADR note.
              assertThat(finding.messageEn()).contains("Invalid content was found");
              assertThat(finding.messageEn()).doesNotContain("Ungültiger");
              assertThat(finding.messageEn())
                  .doesNotStartWith("Das Dokument verletzt das ebInterface-6.1-Schema: ");
              assertThat(finding.messageEn()).isNotEqualTo(finding.messageDe());
            });
  }

  @Test
  void
      toFindingWrapsTheGermanParserMessageBehindTheGermanLeadInAndUsesTheEnglishParserMessageForMessageEn() {
    IError germanError =
        SingleError.builderError()
            .errorText("Ungueltiger Inhalt")
            .errorLocation("upload.xml")
            .build();
    IError englishError = SingleError.builderError().errorText("Invalid content").build();

    Finding finding = XsdValidationStage.toFinding(germanError, englishError);

    assertThat(finding.ruleId()).isEqualTo("EBI61-XSD");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.location()).isEqualTo("upload.xml");
    assertThat(finding.messageDe())
        .isEqualTo("Das Dokument verletzt das ebInterface-6.1-Schema: Ungueltiger Inhalt");
    assertThat(finding.messageEn()).isEqualTo("Invalid content");
  }

  @Test
  void toFindingFallsBackToTheLocaleIndependentTextWhenTheEnglishParserMessageIsMissing() {
    IError germanError =
        SingleError.builderError()
            .errorText("Ungueltiger Inhalt")
            .errorLocation("upload.xml")
            .build();
    IError englishErrorWithoutText = SingleError.builderError().errorLocation("upload.xml").build();

    Finding finding = XsdValidationStage.toFinding(germanError, englishErrorWithoutText);

    assertThat(finding.messageEn()).isEqualTo(englishErrorWithoutText.getAsStringLocaleIndepdent());
    assertThat(finding.messageEn()).isNotEqualTo("Ungueltiger Inhalt");
  }

  @Test
  void toFindingFallsBackToTheGermanDetailWhenNoEnglishCounterpartExists() {
    IError germanError =
        SingleError.builderError()
            .errorText("Ungueltiger Inhalt")
            .errorLocation("upload.xml")
            .build();

    Finding finding = XsdValidationStage.toFinding(germanError, null);

    // Defensive last resort only: the two locale runs should always produce matching error
    // counts for the same document, so this path is not expected to be reached in production.
    assertThat(finding.messageEn()).isEqualTo("Ungueltiger Inhalt");
  }
}
