package com.stoicera.einvoice.app.ai;

import com.stoicera.einvoice.aiassist.llm.LlmUsage;
import com.stoicera.einvoice.aiassist.llm.LlmUsageListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges {@code ai-assist}'s usage port onto Micrometer, satisfying ENGINEERING_STANDARDS §8's
 * token/cost logging without {@code ai-assist} importing a metrics library (SPEC §2 keeps every
 * library module Spring-free).
 *
 * <p>Three meters, all tagged with the model that actually served the request — not the one that
 * was asked for, since a provider may route or substitute and the bill follows what ran:
 *
 * <ul>
 *   <li>{@code einvoice.ai.calls} — completions performed;
 *   <li>{@code einvoice.ai.tokens} — tokens billed, split by {@code kind=prompt|completion};
 *   <li>{@code einvoice.ai.cost.usd} — the provider's own reported charge, and
 *       <strong>only</strong> when it reported one. An unknown cost is left out of the counter
 *       rather than added as zero: a zero would make the total read as "this feature is free",
 *       which is a different and false claim.
 * </ul>
 *
 * <p><strong>Never throws.</strong> A metrics failure must not turn a working explanation into a
 * failed one — this runs on the request thread immediately after a successful completion, and the
 * explanation is already in hand by then. Anything that goes wrong is logged and swallowed.
 */
public class MicrometerLlmUsageListener implements LlmUsageListener {

  private static final Logger log = LoggerFactory.getLogger(MicrometerLlmUsageListener.class);

  private final MeterRegistry meters;

  public MicrometerLlmUsageListener(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void onUsage(LlmUsage usage) {
    try {
      meters.counter("einvoice.ai.calls", "model", usage.model()).increment();
      meters
          .counter("einvoice.ai.tokens", "model", usage.model(), "kind", "prompt")
          .increment(usage.promptTokens());
      meters
          .counter("einvoice.ai.tokens", "model", usage.model(), "kind", "completion")
          .increment(usage.completionTokens());
      usage
          .costUsd()
          .ifPresent(
              cost ->
                  meters
                      .counter("einvoice.ai.cost.usd", "model", usage.model())
                      .increment(cost.doubleValue()));
    } catch (RuntimeException e) {
      log.warn("Failed to record LLM usage metrics", e);
    }
  }
}
