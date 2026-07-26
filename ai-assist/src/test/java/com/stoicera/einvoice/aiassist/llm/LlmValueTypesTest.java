package com.stoicera.einvoice.aiassist.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The port's value types. Their invariants exist so a malformed provider answer becomes a failure
 * at the boundary rather than a blank explanation or a nonsense metric further downstream — which
 * means the invariants, not just the accessors, are what is worth asserting.
 */
class LlmValueTypesTest {

  @Test
  void promptRejectsBlankMessages() {
    assertThatThrownBy(() -> new LlmPrompt(null, "user"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("system message");
    assertThatThrownBy(() -> new LlmPrompt("  ", "user"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmPrompt("system", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user message");
    assertThatThrownBy(() -> new LlmPrompt("system", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void promptKeepsBothMessages() {
    LlmPrompt prompt = new LlmPrompt("system", "user");

    assertThat(prompt.systemMessage()).isEqualTo("system");
    assertThat(prompt.userMessage()).isEqualTo("user");
  }

  @Test
  void completionRejectsBlankTextOrMissingUsage() {
    LlmUsage usage = usage(Optional.empty());

    assertThatThrownBy(() -> new LlmCompletion("   ", usage))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmCompletion(null, usage))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmCompletion("text", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void usageSumsItsTokenCounts() {
    assertThat(usage(Optional.empty()).totalTokens()).isEqualTo(13);
  }

  @Test
  void usageCarriesTheProvidersCostWhenThereIsOne() {
    assertThat(usage(Optional.of(new BigDecimal("0.0042"))).costUsd())
        .contains(new BigDecimal("0.0042"));
  }

  @Test
  void usageRejectsImpossibleValues() {
    assertThatThrownBy(() -> new LlmUsage("  ", 1, 1, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
    assertThatThrownBy(() -> new LlmUsage(null, 1, 1, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmUsage("m", -1, 1, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
    assertThatThrownBy(() -> new LlmUsage("m", 1, -1, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmUsage("m", 1, 1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Optional.empty()");
    assertThatThrownBy(() -> new LlmUsage("m", 1, 1, Optional.of(new BigDecimal("-0.01"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  void exceptionCarriesItsRetryabilityAndCause() {
    Throwable cause = new IllegalStateException("socket closed");

    LlmException retryable = new LlmException("timeout", true, cause);
    assertThat(retryable.isRetryable()).isTrue();
    assertThat(retryable).hasMessage("timeout").hasCause(cause);

    assertThat(new LlmException("bad key", false).isRetryable()).isFalse();
  }

  @Test
  void theNoOpUsageListenerAcceptsAnythingWithoutFailing() {
    // app wires a real listener; every other caller (and every test) relies on this one being
    // inert.
    LlmUsageListener.NONE.onUsage(usage(Optional.of(BigDecimal.ONE)));
  }

  private static LlmUsage usage(Optional<BigDecimal> cost) {
    return new LlmUsage("anthropic/claude-sonnet-5", 10, 3, cost);
  }
}
