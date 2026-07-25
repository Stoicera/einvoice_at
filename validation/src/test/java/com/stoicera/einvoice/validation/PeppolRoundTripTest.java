package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.formats.ubl.Ubl21CreditNoteStrategy;
import com.stoicera.einvoice.formats.ubl.Ubl21InvoiceStrategy;
import com.stoicera.einvoice.mapping.ubl.InvoiceToUblMapper;
import com.stoicera.einvoice.mapping.ubl.UblDocument;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The milestone's acceptance test for the UBL side: an invoice this platform generates is judged by
 * the <em>official</em> OpenPeppol rule set, not by our own opinion of it.
 *
 * <p>This is the UBL counterpart of {@code EndToEndGenerationTest} and the reason the Peppol
 * artefacts are consumed unmodified. It is deliberately an end-to-end assertion over the real
 * chain: canonical {@code Invoice} → {@link InvoiceToUblMapper} → UBL strategy → {@link
 * InvoiceValidator} → the pinned OpenPeppol VES.
 */
class PeppolRoundTripTest {

  private static final InvoiceToUblMapper MAPPER = new InvoiceToUblMapper();
  private static final Ubl21InvoiceStrategy INVOICES = new Ubl21InvoiceStrategy();
  private static final Ubl21CreditNoteStrategy CREDIT_NOTES = new Ubl21CreditNoteStrategy();

  private final InvoiceValidator validator = new InvoiceValidator();

  @Test
  void aFullyPopulatedInvoicePassesTheOfficialPeppolRuleSet() {
    ValidationReport report = validate(PeppolFixtures.peppolReadyInvoice());

    assertThat(report.sourceFormat()).isEqualTo(DocumentFormat.UBL_INVOICE.sourceFormat());
    assertThat(report.profile()).isEqualTo(InvoiceValidator.PROFILE_PEPPOL_BIS_3);
    assertThat(report.findings())
        .withFailMessage("expected a Peppol-valid invoice, got: %s", describe(report))
        .isEmpty();
    assertThat(report.isValid()).isTrue();
  }

  @Test
  void aFullyPopulatedCreditNotePassesTheOfficialPeppolRuleSet() {
    ValidationReport report = validate(PeppolFixtures.peppolReadyCreditNote());

    assertThat(report.sourceFormat()).isEqualTo(DocumentFormat.UBL_CREDIT_NOTE.sourceFormat());
    assertThat(report.findings())
        .withFailMessage("expected a Peppol-valid credit note, got: %s", describe(report))
        .isEmpty();
    assertThat(report.isValid()).isTrue();
  }

  /**
   * The other half of the claim: the rule set is genuinely being run, and genuinely rejects.
   * Without a negative case, "no findings" could equally mean "no rules executed" — the same
   * vacuous-pass trap the architecture tests guard against with their non-empty-import assertion.
   */
  @Test
  void anInvoiceMissingPeppolMandatoryDataIsRejectedWithTheRulesOwnIdentifiers() {
    ValidationReport report = validate(PeppolFixtures.invoiceWithoutElectronicAddresses());

    assertThat(report.isValid()).isFalse();
    assertThat(report.findings()).isNotEmpty();
    assertThat(report.findings())
        .allSatisfy(
            finding -> assertThat(finding.messageDe()).startsWith("Peppol BIS Billing 3.0:"));
    // Rule ids come from the official rule set itself, not from a flat project-local code.
    assertThat(report.findings())
        .anySatisfy(finding -> assertThat(finding.ruleId()).startsWith("PEPPOL-EN16931-"));
    assertThat(report.findings())
        .filteredOn(finding -> finding.severity() == Severity.ERROR)
        .isNotEmpty();
  }

  private ValidationReport validate(Invoice invoice) {
    String xml =
        switch (MAPPER.map(invoice)) {
          case UblDocument.CommercialInvoice(var document) -> INVOICES.write(document);
          case UblDocument.CreditNote(var document) -> CREDIT_NOTES.write(document);
        };
    return validator.validate(xml.getBytes(StandardCharsets.UTF_8));
  }

  private static String describe(ValidationReport report) {
    StringBuilder out = new StringBuilder();
    for (Finding finding : report.findings()) {
      out.append(
          "%n  [%s] %s — %s".formatted(finding.severity(), finding.ruleId(), finding.messageEn()));
    }
    return out.toString();
  }
}
