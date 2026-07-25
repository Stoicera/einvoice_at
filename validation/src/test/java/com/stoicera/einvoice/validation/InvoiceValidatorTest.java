package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import org.junit.jupiter.api.Test;

class InvoiceValidatorTest {

  private final InvoiceValidator validator = new InvoiceValidator();

  /**
   * An unidentifiable document reports profile {@code none}, not {@code at-b2g}.
   *
   * <p>This changed in M4 and is a deliberate correction rather than a consequence: the validator
   * had exactly one profile when it only spoke ebInterface, so stamping {@code at-b2g} onto a
   * document it could not even parse cost nothing. With two profiles that would be a claim about a
   * document nobody has identified — so "we do not know what this is" now reports no profile at
   * all.
   */
  @Test
  void malformedXmlYieldsSingleXml01AndIsInvalid() {
    ValidationReport report = validator.validate(TestDocuments.bytes(TestDocuments.malformed()));

    assertThat(report.sourceFormat()).isEqualTo("unknown");
    assertThat(report.profile()).isEqualTo(InvoiceValidator.PROFILE_NONE);
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
              assertThat(finding.ruleId()).isEqualTo("XSD-01");
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
  void overlongXsdValueDoesNotThrowAndProducesBoundedFinding() {
    // P1-2: an XSD-invalid value longer than Finding's 4096-char message cap used to make
    // validate() throw InvariantViolationException out of the documented "never throws" contract.
    ValidationReport report =
        validator.validate(TestDocuments.bytes(TestDocuments.ebInterface61WithOverlongXsdValue()));

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.isValid()).isFalse();
    assertThat(report.findingsOf(Severity.ERROR)).isNotEmpty();

    // No finding overflows the core caps, whatever the parser echoed.
    assertThat(report.findings())
        .allSatisfy(
            finding -> {
              assertThat(finding.messageDe().length()).isLessThanOrEqualTo(4096);
              assertThat(finding.messageEn().length()).isLessThanOrEqualTo(4096);
              if (finding.location() != null) {
                assertThat(finding.location().length()).isLessThanOrEqualTo(1024);
              }
            });
    // At least one XSD finding echoed the overlong value and was therefore truncated with the
    // ellipsis marker in both languages.
    assertThat(report.findings())
        .anySatisfy(
            finding -> {
              assertThat(finding.ruleId()).isEqualTo("XSD-01");
              assertThat(finding.messageDe()).endsWith("…");
              assertThat(finding.messageEn()).endsWith("…");
            });
  }

  @Test
  void oversizedInputYieldsSingleXml02ErrorAndStops() {
    // P2-9 (size half): a document one byte above the module's defensive input cap is rejected up
    // front with a single XML-02 finding, German first, before any parse is attempted.
    byte[] oversized = new byte[InvoiceValidator.MAX_INPUT_BYTES + 1];

    ValidationReport report = validator.validate(oversized);

    assertThat(report.sourceFormat()).isEqualTo("unknown");
    assertThat(report.findings()).hasSize(1);
    Finding finding = report.findings().get(0);
    assertThat(finding.ruleId()).isEqualTo("XML-02");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageDe())
        .isEqualTo("Dokument überschreitet die maximale Größe von 20 MB.");
    assertThat(finding.messageEn()).isEqualTo("Document exceeds the maximum size of 20 MB.");
    assertThat(report.isValid()).isFalse();
  }

  @Test
  void inputExactlyAtCapIsNotRejectedAsTooLargeAndReachesTheParser() {
    // Boundary of the defensive size cap: input of exactly MAX_INPUT_BYTES must clear the size
    // guard (which rejects only strictly larger uploads) and reach the parser, where these non-XML
    // bytes become a single XML-01 — never an XML-02. Pins the `>` boundary against a `>=` slip
    // that would reject a legitimately-sized document up front.
    byte[] atCap = new byte[InvoiceValidator.MAX_INPUT_BYTES];

    ValidationReport report = validator.validate(atCap);

    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().get(0).ruleId()).isEqualTo("XML-01");
    assertThat(report.isValid()).isFalse();
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
