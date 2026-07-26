package com.stoicera.einvoice.app.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.aiassist.ExplanationContext;
import com.stoicera.einvoice.app.invoice.InvoiceParties;
import com.stoicera.einvoice.app.report.ReportDetail;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two decisions {@code ReportExplanationService} makes before it spends any money — which
 * findings to explain, and what the model is allowed to see — asserted directly.
 *
 * <p>Both are pure functions of their inputs, and both express a claim that would otherwise only be
 * visible as an absence in a wire capture: {@code ExplainApiIT} can see that an explanation came
 * back, but it cannot show that the seller's name was on the redaction list, because no finding
 * message this validator produces happens to contain one. Asserting the context here is the honest
 * way to pin that promise, rather than a wire assertion that would pass whether or not the literals
 * were ever attached.
 */
class ReportExplanationServiceTest {

  // ------------------------------------------------------------------ what is sent

  @Test
  void anInvoiceTiedReportRedactsBothPartyNames() {
    ExplanationContext context =
        ReportExplanationService.contextFor(
            report(UUID.randomUUID()),
            new InvoiceParties("Stoicera Software GesbR", "Bundesbeschaffung GmbH"));

    assertThat(context.sensitiveLiterals())
        .containsExactlyInAnyOrder("Stoicera Software GesbR", "Bundesbeschaffung GmbH");
    assertThat(context.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(context.profile()).isEqualTo("at-b2g");
  }

  @Test
  void anAdHocReportHasNoLiteralsToRedact() {
    // The public validator's case, and the honest limit ADR-0010 records: nothing was stored, so
    // there are no party names to mask. The scrubber's pattern-based masking still applies.
    ExplanationContext context = ReportExplanationService.contextFor(report(null), null);

    assertThat(context.sensitiveLiterals()).isEmpty();
  }

  @Test
  void aMissingBuyerNameIsSkippedRatherThanRedactingNull() {
    // buyerName is optional in the canonical model; withPartyNames filters nulls, and this pins
    // that
    // the service relies on that rather than passing an empty string through as a literal — which
    // would ask the scrubber to replace every empty substring in the prompt.
    ExplanationContext context =
        ReportExplanationService.contextFor(
            report(UUID.randomUUID()), new InvoiceParties("Stoicera Software GesbR", null));

    assertThat(context.sensitiveLiterals()).containsExactly("Stoicera Software GesbR");
  }

  // ----------------------------------------------------------- what gets explained

  @Test
  void errorsAreExplainedBeforeWarningsAndInfos() {
    List<Finding> findings =
        List.of(
            finding(Severity.INFO, "INFO-1"),
            finding(Severity.WARN, "WARN-1"),
            finding(Severity.ERROR, "ERR-1"));

    assertThat(ReportExplanationService.targetsFor(findings, OptionalInt.empty(), 2))
        .containsExactly(2, 1);
  }

  @Test
  void findingsOfEqualSeverityKeepTheirReportOrder() {
    List<Finding> findings =
        List.of(
            finding(Severity.ERROR, "ERR-1"),
            finding(Severity.ERROR, "ERR-2"),
            finding(Severity.ERROR, "ERR-3"));

    assertThat(ReportExplanationService.targetsFor(findings, OptionalInt.empty(), 2))
        .containsExactly(0, 1);
  }

  /**
   * The bug this method was rewritten to avoid: a rule fires once per offending line, so identical
   * findings are normal. Selecting by value and mapping back with {@code List.indexOf} would return
   * position 0 three times — explaining the first finding repeatedly and the others never.
   */
  @Test
  void identicalRepeatedFindingsGetDistinctPositions() {
    Finding repeated = finding(Severity.ERROR, "BR-CO-10");
    List<Finding> findings = List.of(repeated, repeated, repeated);

    assertThat(ReportExplanationService.targetsFor(findings, OptionalInt.empty(), 3))
        .containsExactly(0, 1, 2);
  }

  @Test
  void theCapBoundsHowManyPositionsAreReturned() {
    List<Finding> findings =
        List.of(
            finding(Severity.ERROR, "ERR-1"),
            finding(Severity.ERROR, "ERR-2"),
            finding(Severity.ERROR, "ERR-3"));

    assertThat(ReportExplanationService.targetsFor(findings, OptionalInt.empty(), 1)).hasSize(1);
    assertThat(ReportExplanationService.targetsFor(findings, OptionalInt.empty(), 99)).hasSize(3);
  }

  @Test
  void aCleanReportSelectsNothing() {
    assertThat(ReportExplanationService.targetsFor(List.of(), OptionalInt.empty(), 10)).isEmpty();
  }

  @Test
  void anExplicitIndexSelectsExactlyThatPosition() {
    List<Finding> findings =
        List.of(finding(Severity.INFO, "INFO-1"), finding(Severity.ERROR, "ERR-1"));

    // Not re-sorted: an explicit request is honoured as given, severity notwithstanding.
    assertThat(ReportExplanationService.targetsFor(findings, OptionalInt.of(0), 10))
        .containsExactly(0);
  }

  @Test
  void anIndexPastTheEndIsRejected() {
    List<Finding> findings = List.of(finding(Severity.ERROR, "ERR-1"));

    assertThatThrownBy(() -> ReportExplanationService.targetsFor(findings, OptionalInt.of(1), 10))
        .isInstanceOf(InvalidFindingIndexException.class);
  }

  @Test
  void aNegativeIndexIsRejected() {
    List<Finding> findings = List.of(finding(Severity.ERROR, "ERR-1"));

    assertThatThrownBy(() -> ReportExplanationService.targetsFor(findings, OptionalInt.of(-1), 10))
        .isInstanceOf(InvalidFindingIndexException.class);
  }

  @Test
  void anIndexIntoAnEmptyReportIsRejected() {
    // A clean report has no position 0; refusing beats explaining nothing and calling it success.
    assertThatThrownBy(() -> ReportExplanationService.targetsFor(List.of(), OptionalInt.of(0), 10))
        .isInstanceOf(InvalidFindingIndexException.class);
  }

  // ---------------------------------------------------------------------- fixtures

  private static ReportDetail report(UUID invoiceId) {
    return new ReportDetail(
        UUID.randomUUID(), invoiceId, "ebinterface-6.1", "at-b2g", false, List.of(), Instant.now());
  }

  private static Finding finding(Severity severity, String ruleId) {
    return Finding.of(severity, ruleId, "/Invoice", "Deutsche Meldung", "English message");
  }
}
