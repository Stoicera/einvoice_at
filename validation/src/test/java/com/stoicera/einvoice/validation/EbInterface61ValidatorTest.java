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
            });
  }

  @Test
  void validEbInterface61IsValid() {
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.profile()).isEqualTo("at-b2g");
    assertThat(report.isValid()).isTrue();
    assertThat(report.findingsOf(Severity.ERROR)).isEmpty();
  }
}
