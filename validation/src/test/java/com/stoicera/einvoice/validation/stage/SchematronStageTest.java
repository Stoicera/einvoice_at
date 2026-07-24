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

  private static final String AT_B2G_03_DE =
      "Für Bundesdienststellen ist eine E-Mail-Adresse des Rechnungsstellers erforderlich"
          + " (Biller/Address/Email).";
  private static final String AT_B2G_03_EN =
      "Invoices to Austrian federal bodies require the biller's e-mail address"
          + " (Biller/Address/Email).";

  private static final String AT_B2G_04_DE =
      "Für Bundesdienststellen ist die Lieferantennummer erforderlich"
          + " (Biller/InvoiceRecipientsBillerID).";
  private static final String AT_B2G_04_EN =
      "Invoices to Austrian federal bodies require the supplier number"
          + " (Biller/InvoiceRecipientsBillerID).";

  private static final String AT_B2G_05_DE =
      "Eine Zahlungsmethode ist erforderlich (PaymentMethod: UniversalBankTransaction oder"
          + " NoPayment).";
  private static final String AT_B2G_05_EN =
      "A payment method is required (PaymentMethod: UniversalBankTransaction or NoPayment).";

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
  void missingBillerEmailYieldsSingleAtB2g03ErrorGermanFirst() {
    List<Finding> findings = run(TestDocuments.ebInterface61WithoutBillerEmail());

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-03");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageDe()).isEqualTo(AT_B2G_03_DE);
    assertThat(finding.messageEn()).isEqualTo(AT_B2G_03_EN);
    assertThat(finding.location()).isNotBlank();
  }

  @Test
  void missingSupplierNumberYieldsSingleAtB2g04ErrorGermanFirst() {
    List<Finding> findings = run(TestDocuments.ebInterface61WithoutSupplierNumber());

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-04");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageDe()).isEqualTo(AT_B2G_04_DE);
    assertThat(finding.messageEn()).isEqualTo(AT_B2G_04_EN);
  }

  @Test
  void missingPaymentMethodYieldsSingleAtB2g05ErrorGermanFirst() {
    List<Finding> findings = run(TestDocuments.ebInterface61WithoutPaymentMethod());

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-05");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageDe()).isEqualTo(AT_B2G_05_DE);
    assertThat(finding.messageEn()).isEqualTo(AT_B2G_05_EN);
  }

  @Test
  void bundledSchematronCompilesCleanly() {
    assertThat(SchematronStage.isCompiledSchematronValid()).isTrue();
  }
}
