package com.stoicera.einvoice.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationReportTest {

  private static final Finding ERROR_FINDING =
      Finding.of(Severity.ERROR, "BR-CO-10", "/Invoice", "Fehler eins", "Error one");
  private static final Finding WARN_FINDING =
      Finding.of(Severity.WARN, "BR-42", "/Invoice/Line", "Warnung", "Warning");
  private static final Finding INFO_FINDING =
      Finding.of(Severity.INFO, "BR-7", null, "Hinweis", "Note");

  @Test
  void happyPathCreatesAReportCarryingFindingsInOrder() {
    ValidationReport report =
        new ValidationReport(
            "ebInterface 6.1", "AT-GOV", List.of(ERROR_FINDING, WARN_FINDING, INFO_FINDING));

    assertThat(report.sourceFormat()).isEqualTo("ebInterface 6.1");
    assertThat(report.profile()).isEqualTo("AT-GOV");
    assertThat(report.findings()).containsExactly(ERROR_FINDING, WARN_FINDING, INFO_FINDING);
  }

  @Test
  void emptyFindingsListIsAccepted() {
    ValidationReport report = new ValidationReport("UBL 2.1", "Peppol BIS 3.0", List.of());
    assertThat(report.findings()).isEmpty();
    assertThat(report.isValid()).isTrue();
  }

  @Test
  void findingsListIsDefensivelyCopiedAndUnmodifiable() {
    List<Finding> mutable = new ArrayList<>();
    mutable.add(ERROR_FINDING);
    ValidationReport report = new ValidationReport("ebInterface 6.1", "AT-GOV", mutable);

    mutable.add(WARN_FINDING);
    assertThat(report.findings()).containsExactly(ERROR_FINDING);

    assertThatThrownBy(() -> report.findings().add(WARN_FINDING))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNullAndBlankSourceFormat() {
    assertThatThrownBy(() -> new ValidationReport(null, "AT-GOV", List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report source format must not be blank");
    assertThatThrownBy(() -> new ValidationReport("  ", "AT-GOV", List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report source format must not be blank");
  }

  @Test
  void rejectsNullAndBlankProfile() {
    assertThatThrownBy(() -> new ValidationReport("ebInterface 6.1", null, List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report profile must not be blank");
    assertThatThrownBy(() -> new ValidationReport("ebInterface 6.1", " ", List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report profile must not be blank");
  }

  @Test
  void rejectsNullFindingsList() {
    assertThatThrownBy(() -> new ValidationReport("ebInterface 6.1", "AT-GOV", null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report findings must not be null");
  }

  @Test
  void rejectsNullEntryInFindingsList() {
    List<Finding> withNull = new ArrayList<>();
    withNull.add(ERROR_FINDING);
    withNull.add(null);
    assertThatThrownBy(() -> new ValidationReport("ebInterface 6.1", "AT-GOV", withNull))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report findings must not contain null entries");
  }

  @Test
  void sourceFormatLengthIsCappedAtSixtyFourCharacters() {
    String atLimit = "x".repeat(64);
    ValidationReport accepted = new ValidationReport(atLimit, "AT-GOV", List.of());
    assertThat(accepted.sourceFormat()).hasSize(64);

    String overLimit = "x".repeat(65);
    assertThatThrownBy(() -> new ValidationReport(overLimit, "AT-GOV", List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report source format exceeds 64 characters");
  }

  @Test
  void profileLengthIsCappedAtTwoFiftySixCharacters() {
    String atLimit = "x".repeat(256);
    ValidationReport accepted = new ValidationReport("ebInterface 6.1", atLimit, List.of());
    assertThat(accepted.profile()).hasSize(256);

    String overLimit = "x".repeat(257);
    assertThatThrownBy(() -> new ValidationReport("ebInterface 6.1", overLimit, List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Validation report profile exceeds 256 characters");
  }

  @Test
  void overLongInputIsRejectedWithoutEchoingTheRawValue() {
    String overLongProfile = "SECRET-" + "x".repeat(300);
    assertThatThrownBy(() -> new ValidationReport("ebInterface 6.1", overLongProfile, List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageNotContaining(overLongProfile)
        .hasMessageNotContaining("SECRET")
        .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(100));
  }

  @Test
  void isValidIsTrueWhenThereIsNoErrorFinding() {
    ValidationReport report =
        new ValidationReport("ebInterface 6.1", "AT-GOV", List.of(WARN_FINDING, INFO_FINDING));
    assertThat(report.isValid()).isTrue();
  }

  @Test
  void isValidIsFalseWhenThereIsAtLeastOneErrorFinding() {
    ValidationReport report =
        new ValidationReport(
            "ebInterface 6.1", "AT-GOV", List.of(ERROR_FINDING, WARN_FINDING, INFO_FINDING));
    assertThat(report.isValid()).isFalse();
  }

  @Test
  void findingsOfFiltersBySeverityPreservingOrder() {
    ValidationReport report =
        new ValidationReport(
            "ebInterface 6.1", "AT-GOV", List.of(ERROR_FINDING, WARN_FINDING, INFO_FINDING));

    assertThat(report.findingsOf(Severity.ERROR)).containsExactly(ERROR_FINDING);
    assertThat(report.findingsOf(Severity.WARN)).containsExactly(WARN_FINDING);
    assertThat(report.findingsOf(Severity.INFO)).containsExactly(INFO_FINDING);
  }

  @Test
  void findingsOfReturnsEmptyListWhenNoFindingHasThatSeverity() {
    ValidationReport report =
        new ValidationReport("ebInterface 6.1", "AT-GOV", List.of(WARN_FINDING));
    assertThat(report.findingsOf(Severity.ERROR)).isEmpty();
  }

  @Test
  void countOfCountsFindingsOfTheGivenSeverity() {
    Finding secondError =
        Finding.of(Severity.ERROR, "BR-CO-11", "/Invoice/Line[2]", "Fehler zwei", "Error two");
    ValidationReport report =
        new ValidationReport(
            "ebInterface 6.1",
            "AT-GOV",
            List.of(ERROR_FINDING, secondError, WARN_FINDING, INFO_FINDING));

    assertThat(report.countOf(Severity.ERROR)).isEqualTo(2L);
    assertThat(report.countOf(Severity.WARN)).isEqualTo(1L);
    assertThat(report.countOf(Severity.INFO)).isEqualTo(1L);
  }

  @Test
  void countOfIsZeroWhenThereAreNoFindings() {
    ValidationReport report = new ValidationReport("ebInterface 6.1", "AT-GOV", List.of());
    assertThat(report.countOf(Severity.ERROR)).isZero();
  }

  @Test
  void findingsOfRejectsNullSeverity() {
    ValidationReport report = new ValidationReport("ebInterface 6.1", "AT-GOV", List.of());
    assertThatThrownBy(() -> report.findingsOf(null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Severity must not be null");
  }

  @Test
  void countOfRejectsNullSeverity() {
    ValidationReport report = new ValidationReport("ebInterface 6.1", "AT-GOV", List.of());
    assertThatThrownBy(() -> report.countOf(null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Severity must not be null");
  }
}
