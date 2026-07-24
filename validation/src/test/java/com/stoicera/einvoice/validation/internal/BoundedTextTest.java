package com.stoicera.einvoice.validation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoundedTextTest {

  @Test
  void nullPassesThrough() {
    assertThat(BoundedText.cap(null, 10)).isNull();
  }

  @Test
  void textAtOrBelowTheLimitIsReturnedUnchanged() {
    assertThat(BoundedText.cap("short", 10)).isEqualTo("short");
    // Exactly at the limit: no truncation, no marker.
    assertThat(BoundedText.cap("1234567890", 10)).isEqualTo("1234567890");
  }

  @Test
  void overlongTextIsTruncatedToExactlyTheLimitWithTheEllipsisMarker() {
    String result = BoundedText.cap("x".repeat(5000), 100);

    assertThat(result).hasSize(100);
    assertThat(result).endsWith("…");
    // The marker replaces the final character of the budget, so 99 originals + 1 marker.
    assertThat(result).isEqualTo("x".repeat(99) + "…");
  }
}
