package com.stoicera.einvoice.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.aiassist.llm.LlmUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The cost meters, and the one thing about them that is a defect rather than a detail: their {@code
 * model} tag comes out of the <em>provider's</em> response body.
 *
 * <p>{@code AI_BASE_URL} is configurable to any OpenAI-compatible gateway, so that string is not
 * this platform's to trust. Micrometer mints one meter per distinct tag value and never collects
 * them, so a gateway that echoed a request id into {@code model} would grow the registry until the
 * process died — a slow, quiet memory leak reachable from outside. The M5 hostile review found it
 * (F5); {@code boundedModel} is the fix and these tests are its boundary.
 */
class MicrometerLlmUsageListenerTest {

  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final MicrometerLlmUsageListener listener = new MicrometerLlmUsageListener(meters);

  @Test
  void recordsCallsTokensAndCostTaggedWithTheServingModel() {
    listener.onUsage(
        new LlmUsage("anthropic/claude-sonnet-5", 120, 45, Optional.of(new BigDecimal("0.00042"))));

    assertThat(meters.counter("einvoice.ai.calls", "model", "anthropic/claude-sonnet-5").count())
        .isEqualTo(1);
    assertThat(
            meters
                .counter(
                    "einvoice.ai.tokens", "model", "anthropic/claude-sonnet-5", "kind", "prompt")
                .count())
        .isEqualTo(120);
    assertThat(
            meters
                .counter(
                    "einvoice.ai.tokens",
                    "model",
                    "anthropic/claude-sonnet-5",
                    "kind",
                    "completion")
                .count())
        .isEqualTo(45);
    assertThat(meters.counter("einvoice.ai.cost.usd", "model", "anthropic/claude-sonnet-5").count())
        .isEqualTo(0.00042);
  }

  @Test
  void leavesAnUnknownCostOutOfTheCounterRatherThanAddingZero() {
    // "cost unknown" and "cost nothing" are different facts; a zero would make the total read as
    // "this feature is free".
    listener.onUsage(new LlmUsage("anthropic/claude-sonnet-5", 10, 5, Optional.empty()));

    assertThat(meters.find("einvoice.ai.cost.usd").counter()).isNull();
  }

  @Test
  void replacesAnOverlongModelNameWithASingleUnknownSeries() {
    listener.onUsage(new LlmUsage("x".repeat(500), 1, 1, Optional.empty()));

    assertThat(meters.counter("einvoice.ai.calls", "model", "unknown").count()).isEqualTo(1);
    assertThat(meters.find("einvoice.ai.calls").tag("model", "x".repeat(500)).counter()).isNull();
  }

  @Test
  void replacesAModelNameThatIsNotSlugShapedWithUnknown() {
    // The realistic hostile shapes: a request id, a JSON fragment, an error sentence. Blank is not
    // among them because LlmUsage's own invariant already rejects it — the value cannot get this
    // far, and asserting it here would be asserting the wrong class's contract.
    for (String reported :
        new String[] {
          "req 8f21c0", "{\"error\":\"boom\"}", "model, with, commas", "modell\nzeile"
        }) {
      MeterRegistry registry = new SimpleMeterRegistry();
      new MicrometerLlmUsageListener(registry)
          .onUsage(new LlmUsage(reported, 1, 1, Optional.empty()));

      assertThat(registry.counter("einvoice.ai.calls", "model", "unknown").count())
          .as("%s", reported)
          .isEqualTo(1);
    }
  }

  @Test
  void keepsEveryShapeARealModelSlugHas() {
    // The bound must not be so tight that it erases the information it exists to carry.
    for (String slug :
        new String[] {
          "anthropic/claude-sonnet-5",
          "anthropic/claude-opus-5",
          "openai/gpt-4.1-mini",
          "meta-llama/llama-3.3-70b-instruct:free",
          "local-model"
        }) {
      assertThat(MicrometerLlmUsageListener.boundedModel(slug)).isEqualTo(slug);
    }
  }

  @Test
  void neverThrowsSoAMetricsFailureCannotFailAWorkingExplanation() {
    MeterRegistry broken =
        new SimpleMeterRegistry() {
          @Override
          public io.micrometer.core.instrument.Counter counter(String name, String... tags) {
            throw new IllegalStateException("registry is unhappy");
          }
        };

    // No exception: the explanation is already in the caller's hand by the time this runs.
    new MicrometerLlmUsageListener(broken)
        .onUsage(new LlmUsage("anthropic/claude-sonnet-5", 1, 1, Optional.empty()));
  }
}
