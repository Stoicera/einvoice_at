package com.stoicera.einvoice.app.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Turns the application-orchestrated steps of the invoice pipeline into Micrometer observations —
 * which Boot's OpenTelemetry auto-configuration renders as both a timer and a span (M6, ADR-0012).
 *
 * <h2>One observation name, one tag</h2>
 *
 * <p>Every step shares the observation name {@value #OBSERVATION_NAME} and is distinguished by the
 * low-cardinality tag {@code step}. That is the Micrometer idiom and it matters in both directions:
 * the metric side gets <em>one</em> timer an operator can group, sum and alert on, while the span
 * side gets a readable per-step name through {@link Observation#contextualName}. Naming the
 * observations individually ({@code einvoice.map-ubl}, {@code einvoice.render-pdf}, …) would give a
 * dashboard ten unrelated timers with no way to ask "how long does the pipeline take".
 *
 * <p>The tag values come from {@link PipelineStep} and nowhere else, so the series count is bounded
 * by an enum rather than by what a caller happens to pass.
 *
 * <h2>What happens when tracing is off</h2>
 *
 * <p>Nothing. With {@code OTEL_ENABLED=false} — the default — Boot still supplies an {@link
 * ObservationRegistry}, it simply has no tracing handler registered, so an observation is a few
 * field writes and no span is created or exported. Instrumentation therefore has one shape in every
 * deployment, and switching observability on is a configuration change rather than a code path.
 */
@Component
public class PipelineObservations {

  /** The single observation name every application-level pipeline step is recorded under. */
  public static final String OBSERVATION_NAME = "einvoice.pipeline";

  /** The low-cardinality tag carrying the {@link PipelineStep}. */
  public static final String STEP_TAG = "step";

  private final ObservationRegistry registry;

  public PipelineObservations(ObservationRegistry registry) {
    this.registry = registry;
  }

  /**
   * Runs {@code work} as one observed pipeline step and returns its value.
   *
   * <p>The step's own exceptions propagate unchanged; the observation records them and closes,
   * because a failed mapping is exactly the case an operator opens a trace to look at.
   *
   * @param step which step this is — the sole source of the {@code step} tag
   * @param work the step's work; invoked exactly once
   * @return whatever {@code work} returned
   * @param <T> the step's result type
   */
  public <T> T observe(PipelineStep step, Supplier<T> work) {
    return Observation.createNotStarted(OBSERVATION_NAME, registry)
        .lowCardinalityKeyValue(STEP_TAG, step.tag())
        .contextualName(OBSERVATION_NAME + "." + step.tag())
        .observe(work);
  }

  /** {@link #observe(PipelineStep, Supplier)} for a step that produces no value. */
  public void observe(PipelineStep step, Runnable work) {
    observe(
        step,
        () -> {
          work.run();
          return null;
        });
  }
}
