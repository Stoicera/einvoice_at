package com.stoicera.einvoice.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import org.junit.jupiter.api.Test;

class FindingTest {

  @Test
  void ofCreatesAFindingWithoutAnAiExplanation() {
    Finding finding =
        Finding.of(Severity.ERROR, "BR-CO-10", "/Invoice/cbc:ID", "Fehlertext", "Error text");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.ruleId()).isEqualTo("BR-CO-10");
    assertThat(finding.location()).isEqualTo("/Invoice/cbc:ID");
    assertThat(finding.messageDe()).isEqualTo("Fehlertext");
    assertThat(finding.messageEn()).isEqualTo("Error text");
    assertThat(finding.aiExplanation()).isNull();
  }

  @Test
  void canonicalConstructorAcceptsANullLocationAndAiExplanation() {
    Finding finding = new Finding(Severity.WARN, "BR-42", null, "Warnung", "Warning", null);
    assertThat(finding.location()).isNull();
    assertThat(finding.aiExplanation()).isNull();
  }

  @Test
  void canonicalConstructorAcceptsAnExplicitAiExplanation() {
    Finding finding = new Finding(Severity.INFO, "BR-1", "line:3", "Hinweis", "Note", "Weil ...");
    assertThat(finding.aiExplanation()).isEqualTo("Weil ...");
  }

  @Test
  void withAiExplanationReturnsANewInstanceLeavingOtherFieldsUnchanged() {
    Finding original = Finding.of(Severity.ERROR, "BR-1", "loc", "Fehler", "Error");
    Finding explained = original.withAiExplanation("Erklärung");

    assertThat(explained).isNotSameAs(original);
    assertThat(explained.aiExplanation()).isEqualTo("Erklärung");
    assertThat(original.aiExplanation()).isNull();
    assertThat(explained.severity()).isEqualTo(original.severity());
    assertThat(explained.ruleId()).isEqualTo(original.ruleId());
    assertThat(explained.location()).isEqualTo(original.location());
    assertThat(explained.messageDe()).isEqualTo(original.messageDe());
    assertThat(explained.messageEn()).isEqualTo(original.messageEn());
  }

  @Test
  void rejectsNullSeverity() {
    assertThatThrownBy(() -> Finding.of(null, "BR-1", "loc", "Fehler", "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding severity must not be null");
  }

  @Test
  void rejectsNullAndBlankRuleId() {
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, null, "loc", "Fehler", "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding rule id must not be blank");
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "  ", "loc", "Fehler", "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding rule id must not be blank");
  }

  @Test
  void rejectsNullAndBlankGermanMessage() {
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "BR-1", "loc", null, "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding German message must not be blank");
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "BR-1", "loc", " ", "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding German message must not be blank");
  }

  @Test
  void rejectsNullAndBlankEnglishMessage() {
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "BR-1", "loc", "Fehler", null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding English message must not be blank");
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "BR-1", "loc", "Fehler", " "))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding English message must not be blank");
  }

  @Test
  void ruleIdLengthIsCappedAtOneTwentyEightCharacters() {
    String atLimit = "x".repeat(128);
    Finding accepted = Finding.of(Severity.ERROR, atLimit, "loc", "Fehler", "Error");
    assertThat(accepted.ruleId()).hasSize(128);

    String overLimit = "x".repeat(129);
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, overLimit, "loc", "Fehler", "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding rule id exceeds 128 characters");
  }

  @Test
  void locationLengthIsCappedAtOneThousandTwentyFourCharacters() {
    String atLimit = "x".repeat(1024);
    Finding accepted = Finding.of(Severity.ERROR, "BR-1", atLimit, "Fehler", "Error");
    assertThat(accepted.location()).hasSize(1024);

    String overLimit = "x".repeat(1025);
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "BR-1", overLimit, "Fehler", "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding location exceeds 1024 characters");
  }

  @Test
  void germanMessageLengthIsCappedAtFourThousandNinetySixCharacters() {
    String atLimit = "x".repeat(4096);
    Finding accepted = Finding.of(Severity.ERROR, "BR-1", "loc", atLimit, "Error");
    assertThat(accepted.messageDe()).hasSize(4096);

    String overLimit = "x".repeat(4097);
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "BR-1", "loc", overLimit, "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding German message exceeds 4096 characters");
  }

  @Test
  void englishMessageLengthIsCappedAtFourThousandNinetySixCharacters() {
    String atLimit = "x".repeat(4096);
    Finding accepted = Finding.of(Severity.ERROR, "BR-1", "loc", "Fehler", atLimit);
    assertThat(accepted.messageEn()).hasSize(4096);

    String overLimit = "x".repeat(4097);
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, "BR-1", "loc", "Fehler", overLimit))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding English message exceeds 4096 characters");
  }

  @Test
  void aiExplanationLengthIsCappedAtEightThousandOneNinetyTwoCharacters() {
    String atLimit = "x".repeat(8192);
    Finding accepted = new Finding(Severity.INFO, "BR-1", "loc", "Hinweis", "Note", atLimit);
    assertThat(accepted.aiExplanation()).hasSize(8192);

    String overLimit = "x".repeat(8193);
    assertThatThrownBy(
            () -> new Finding(Severity.INFO, "BR-1", "loc", "Hinweis", "Note", overLimit))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Finding AI explanation exceeds 8192 characters");
  }

  @Test
  void overLongInputIsRejectedWithoutEchoingTheRawValue() {
    String overLongRuleId = "SECRET-" + "x".repeat(200);
    assertThatThrownBy(() -> Finding.of(Severity.ERROR, overLongRuleId, "loc", "Fehler", "Error"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageNotContaining(overLongRuleId)
        .hasMessageNotContaining("SECRET")
        .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(100));
  }
}
