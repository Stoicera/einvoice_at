package com.stoicera.einvoice.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextsTest {

  @Test
  void nullBecomesTheLiteralStringNull() {
    assertThat(Texts.safeEcho(null)).isEqualTo("null");
  }

  @Test
  void controlCharactersBecomeQuestionMarks() {
    assertThat(Texts.safeEcho("AT\n12\t34")).isEqualTo("AT?12?34");
  }

  @Test
  void inputAtSixtyFourCharsIsNotTruncated() {
    String exactly64 = "x".repeat(64);
    assertThat(Texts.safeEcho(exactly64)).isEqualTo(exactly64);
  }

  @Test
  void inputOverSixtyFourCharsIsTruncatedWithEllipsis() {
    String sixtyFive = "x".repeat(65);
    String result = Texts.safeEcho(sixtyFive);
    assertThat(result).isEqualTo("x".repeat(64) + "…");
    assertThat(result).hasSize(65);
  }
}
