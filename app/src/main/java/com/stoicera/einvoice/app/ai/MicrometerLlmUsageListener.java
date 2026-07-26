package com.stoicera.einvoice.app.ai;

import com.stoicera.einvoice.aiassist.llm.LlmUsage;
import com.stoicera.einvoice.aiassist.llm.LlmUsageListener;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.regex.Pattern;
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
 * <h2>The model tag is bounded, because it is not ours</h2>
 *
 * <p>{@link LlmUsage#model()} is read from the provider's response body, and {@code AI_BASE_URL}
 * may point at any OpenAI-compatible gateway. A tag value taken verbatim from a third party is an
 * unbounded-cardinality tag: every distinct string mints a new meter, and meters are never
 * collected, so a gateway that echoed a request id there would grow the registry until the process
 * died. The M5 hostile review found this (F5). {@link #boundedModel} therefore keeps the value only
 * while it looks like a model slug and is short enough to be one, and reports anything else as
 * {@value #UNKNOWN_MODEL} — one extra series at worst, instead of unbounded many.
 *
 * <p><strong>Never throws.</strong> A metrics failure must not turn a working explanation into a
 * failed one — this runs on the request thread immediately after a successful completion, and the
 * explanation is already in hand by then. Anything that goes wrong is logged and swallowed.
 */
public class MicrometerLlmUsageListener implements LlmUsageListener {

  private static final Logger log = LoggerFactory.getLogger(MicrometerLlmUsageListener.class);

  /** Stands in for a model name that cannot be trusted as a tag value. */
  static final String UNKNOWN_MODEL = "unknown";

  /**
   * Longest model slug accepted as a tag. Generous against real ones ({@code
   * anthropic/claude-sonnet-5} is 25) and far below anything a response id or an error string would
   * be.
   */
  private static final int MAX_MODEL_LENGTH = 64;

  /** The shape a model slug has: {@code vendor/model-name}, optionally with a version suffix. */
  private static final Pattern MODEL_SLUG = Pattern.compile("[A-Za-z0-9._/:-]+");

  private final MeterRegistry meters;

  public MicrometerLlmUsageListener(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void onUsage(LlmUsage usage) {
    try {
      String model = boundedModel(usage.model());
      meters.counter("einvoice.ai.calls", "model", model).increment();
      meters
          .counter("einvoice.ai.tokens", "model", model, "kind", "prompt")
          .increment(usage.promptTokens());
      meters
          .counter("einvoice.ai.tokens", "model", model, "kind", "completion")
          .increment(usage.completionTokens());
      usage
          .costUsd()
          .ifPresent(
              cost ->
                  meters
                      .counter("einvoice.ai.cost.usd", "model", model)
                      .increment(cost.doubleValue()));
    } catch (RuntimeException e) {
      log.warn("Failed to record LLM usage metrics", e);
    }
  }

  /** The reported model if it is usable as a tag value, {@value #UNKNOWN_MODEL} otherwise. */
  static String boundedModel(String reported) {
    return reported != null
            && !reported.isBlank()
            && reported.length() <= MAX_MODEL_LENGTH
            && MODEL_SLUG.matcher(reported).matches()
        ? reported
        : UNKNOWN_MODEL;
  }
}
