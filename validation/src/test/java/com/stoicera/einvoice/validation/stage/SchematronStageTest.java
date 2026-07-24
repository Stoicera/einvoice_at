package com.stoicera.einvoice.validation.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.TestDocuments;
import com.stoicera.einvoice.validation.ValidationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the AT-B2G Schematron stage in isolation: the stage evaluates our own {@code
 * at-b2g-ebinterface-6.1.sch} against the parsed DOM and maps every failed assert to a finding via
 * {@link SchematronRuleCatalog}. The facade guarantees a parsed, XSD-valid DOM before this stage
 * runs; these tests feed it DOMs directly.
 */
class SchematronStageTest {

  private final SchematronStage stage = new SchematronStage();

  private static final String AT_B2G_01_DE =
      "Auftragsreferenz fehlt: Rechnungen an Bundesdienststellen müssen eine Auftragsreferenz"
          + " (OrderReference/OrderID) enthalten.";
  private static final String AT_B2G_01_EN =
      "Order reference missing: invoices to Austrian federal bodies must carry an order reference"
          + " (OrderReference/OrderID).";

  private List<Finding> run(String xml) {
    return stage.apply(new ValidationContext(TestDocuments.bytes(xml)));
  }

  @Test
  void missingOrderReferenceYieldsSingleAtB2g01ErrorGermanFirst() {
    List<Finding> findings = run(TestDocuments.ebInterface61WithoutOrderReference());

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-01");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageDe()).isEqualTo(AT_B2G_01_DE);
    assertThat(finding.messageEn()).isEqualTo(AT_B2G_01_EN);
    assertThat(finding.location()).isNotBlank();
  }

  @Test
  void presentOrderReferenceYieldsNoAtB2g01() {
    assertThat(run(TestDocuments.validEbInterface61())).isEmpty();
  }

  @Test
  void whitespaceOnlyOrderIdYieldsAtB2g01() {
    List<Finding> findings = run(TestDocuments.ebInterface61BlankOrderReference());

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("AT-B2G-01");
    assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void bundledSchematronCompilesCleanly() {
    assertThat(SchematronStage.isCompiledSchematronValid()).isTrue();
  }
}
