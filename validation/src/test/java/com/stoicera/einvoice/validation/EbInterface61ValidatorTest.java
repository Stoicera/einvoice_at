package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import org.junit.jupiter.api.Test;

class EbInterface61ValidatorTest {

  private final EbInterface61Validator validator = new EbInterface61Validator();

  @Test
  void malformedXmlYieldsSingleXml01AndIsInvalid() {
    ValidationReport report = validator.validate(TestDocuments.bytes(TestDocuments.malformed()));

    assertThat(report.sourceFormat()).isEqualTo("unknown");
    assertThat(report.profile()).isEqualTo(EbInterface61Validator.PROFILE_AT_B2G);
    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().get(0).ruleId()).isEqualTo("XML-01");
    assertThat(report.findings().get(0).severity()).isEqualTo(Severity.ERROR);
    assertThat(report.isValid()).isFalse();
  }

  @Test
  void nullInputIsTreatedAsMalformedAndNeverThrows() {
    ValidationReport report = validator.validate(null);

    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().get(0).ruleId()).isEqualTo("XML-01");
    assertThat(report.isValid()).isFalse();
  }

  @Test
  void unknownNamespaceYieldsFormat01() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.unknownNamespace()));

    assertThat(report.sourceFormat()).isEqualTo("unknown");
    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().get(0).ruleId()).isEqualTo("FORMAT-01");
    assertThat(report.isValid()).isFalse();
  }

  @Test
  void ebInterface60YieldsFormat02NamingBothVersions() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.ebInterface60()));

    assertThat(report.sourceFormat()).isEqualTo("unknown");
    assertThat(report.findings()).hasSize(1);
    Finding finding = report.findings().get(0);
    assertThat(finding.ruleId()).isEqualTo("FORMAT-02");
    assertThat(finding.messageDe()).contains("6.0").contains("6.1");
    assertThat(report.isValid()).isFalse();
  }

  @Test
  void structurallyBrokenEbInterface61YieldsXsdErrorAndStopsBeforeLaterStages() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.brokenEbInterface61()));

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.isValid()).isFalse();
    assertThat(report.findingsOf(Severity.ERROR)).isNotEmpty();
    assertThat(report.findings())
        .anySatisfy(
            finding -> {
              assertThat(finding.ruleId()).isEqualTo("EBI61-XSD");
              assertThat(finding.messageDe())
                  .startsWith("Das Dokument verletzt das ebInterface-6.1-Schema: ");
              // messageEn must be the English rendering, not a German fetch reused verbatim.
              assertThat(finding.messageEn())
                  .doesNotStartWith("Das Dokument verletzt das ebInterface-6.1-Schema: ");
              assertThat(finding.messageEn()).isNotEqualTo(finding.messageDe());
            });
    // Gating: a document that fails the XSD stage must never reach the Schematron stage, so no
    // AT-B2G rule can fire on a structurally invalid tree.
    assertThat(report.findings()).noneMatch(finding -> finding.ruleId().startsWith("AT-B2G"));
  }

  @Test
  void schemaValidButMissingOrderReferenceFailsAtB2g01() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.ebInterface61WithoutOrderReference()));

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.isValid()).isFalse();
    assertThat(report.findings())
        .anySatisfy(
            finding -> {
              assertThat(finding.ruleId()).isEqualTo("AT-B2G-01");
              assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            });
    // The document is structurally schema-valid: the only error is the business rule, not XSD.
    assertThat(report.findingsOf(Severity.ERROR))
        .allSatisfy(finding -> assertThat(finding.ruleId()).isEqualTo("AT-B2G-01"));
  }

  @Test
  void validEbInterface61WithOrderReferenceIsValid() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.profile()).isEqualTo("at-b2g");
    assertThat(report.isValid()).isTrue();
    assertThat(report.findingsOf(Severity.ERROR)).isEmpty();
  }

  @Test
  void validEbInterface61WithValidIbanIsValid() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.validEbInterface61WithValidIban()));

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.isValid()).isTrue();
    assertThat(report.findings()).isEmpty();
  }

  @Test
  void documentViolatingBothAtRulesReportsBothInPipelineOrder() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.ebInterface61ViolatingBothAtRules()));

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.isValid()).isFalse();
    // Both AT-B2G rules fire, and the report preserves pipeline order: the Schematron stage
    // (AT-B2G-01) runs before the business-rule stage (AT-B2G-02), so that is the finding order.
    assertThat(report.findings())
        .extracting(Finding::ruleId)
        .containsExactly("AT-B2G-01", "AT-B2G-02");
    assertThat(report.findings())
        .allSatisfy(finding -> assertThat(finding.severity()).isEqualTo(Severity.ERROR));
  }
}
