package com.stoicera.einvoice.validation.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.TestDocuments;
import com.stoicera.einvoice.validation.ValidationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the AT-B2G business-rule stage in isolation: the stage walks the parsed 6.1 tree and
 * flags every {@code BeneficiaryAccount/IBAN} that fails the mod-97 checksum as {@code AT-B2G-02}.
 * The facade guarantees an XSD-valid, parsed tree before this stage runs; these tests feed it
 * documents directly.
 */
class BusinessRuleStageTest {

  private final BusinessRuleStage stage = new BusinessRuleStage();

  private List<Finding> run(String xml) {
    return stage.apply(new ValidationContext(TestDocuments.bytes(xml)));
  }

  @Test
  void checksumBrokenIbanYieldsSingleAtB2g02ErrorThatNeverEchoesTheIban() {
    List<Finding> findings = run(TestDocuments.ebInterface61WithBrokenIban());

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-02");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageDe())
        .isEqualTo("IBAN im Empfängerkonto 1 ist ungültig (Prüfsummenfehler).");
    assertThat(finding.messageEn())
        .isEqualTo("IBAN in beneficiary account 1 is invalid (checksum failure).");
    assertThat(finding.location())
        .isEqualTo("/Invoice/PaymentMethod/UniversalBankTransaction/BeneficiaryAccount[1]/IBAN");
    // The IBAN is bank-account PII and must never leak into the finding — not the message, not the
    // location.
    assertThat(finding.messageDe()).doesNotContain(TestDocuments.CHECKSUM_BROKEN_IBAN);
    assertThat(finding.messageEn()).doesNotContain(TestDocuments.CHECKSUM_BROKEN_IBAN);
    assertThat(finding.location()).doesNotContain(TestDocuments.CHECKSUM_BROKEN_IBAN);
  }

  @Test
  void validIbanYieldsNoFinding() {
    assertThat(run(TestDocuments.validEbInterface61WithValidIban())).isEmpty();
  }

  @Test
  void noPaymentPaymentMethodYieldsNoFinding() {
    // The base fixture's PaymentMethod is the minimal NoPayment variant (required since AT-B2G-05):
    // no UniversalBankTransaction, so no beneficiary account/IBAN for this stage to check at all.
    assertThat(run(TestDocuments.validEbInterface61())).isEmpty();
  }

  @Test
  void absentPaymentMethodYieldsNoFinding() {
    assertThat(run(TestDocuments.ebInterface61WithoutPaymentMethod())).isEmpty();
  }

  @Test
  void beneficiaryAccountWithoutIbanElementYieldsNoFinding() {
    assertThat(run(TestDocuments.ebInterface61WithBeneficiaryAccountButNoIban())).isEmpty();
  }

  @Test
  void secondOfTwoAccountsBrokenNamesAccountTwo() {
    List<Finding> findings = run(TestDocuments.ebInterface61WithSecondAccountBrokenIban());

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("AT-B2G-02");
    assertThat(finding.messageDe())
        .isEqualTo("IBAN im Empfängerkonto 2 ist ungültig (Prüfsummenfehler).");
    assertThat(finding.location())
        .isEqualTo("/Invoice/PaymentMethod/UniversalBankTransaction/BeneficiaryAccount[2]/IBAN");
  }
}
