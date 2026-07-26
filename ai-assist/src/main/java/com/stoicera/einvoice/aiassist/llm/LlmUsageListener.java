package com.stoicera.einvoice.aiassist.llm;

/**
 * Receives the usage of every completion this module performs, so token and cost metrics can be
 * exported without this module depending on a metrics library.
 *
 * <p><strong>Why a port and not a Micrometer counter.</strong> ENGINEERING_STANDARDS §8 wants
 * token/cost metrics and SPEC §6 wants them as OpenTelemetry metrics — but SPEC §2 keeps every
 * library module free of Spring, and Micrometer is how this platform reaches OTel from {@code app}.
 * Handing the numbers over an interface satisfies both: {@code app} implements this in one small
 * adapter over its existing {@code MeterRegistry}, and {@code ai-assist} stays a plain-Java module
 * that can be unit-tested with a lambda.
 *
 * <p>Implementations must not throw and must not block: this is called on the request thread
 * immediately after a completion, and a metrics failure must never turn a working explanation into
 * a failed one. {@link #NONE} is the do-nothing default for callers that want no metrics.
 */
@FunctionalInterface
public interface LlmUsageListener {

  /** Records one completion's usage. Must not throw. */
  void onUsage(LlmUsage usage);

  /** A listener that discards everything — the default when no metrics are wired. */
  LlmUsageListener NONE = usage -> {};
}
