package com.stoicera.einvoice.app.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The application-orchestrated half of the pipeline instrumentation (M6, ADR-0012). */
class PipelineObservationsTest {

  private final TestObservationRegistry registry = TestObservationRegistry.create();
  private final PipelineObservations observations = new PipelineObservations(registry);

  @Test
  @DisplayName("one observation name for the whole pipeline, distinguished by the step tag")
  void recordsOneNameAndAStepTag() {
    observations.observe(PipelineStep.RENDER_PDF, () -> new byte[0]);

    TestObservationRegistryAssert.assertThat(registry)
        .hasNumberOfObservationsEqualTo(1)
        .hasSingleObservationThat()
        .hasNameEqualTo(PipelineObservations.OBSERVATION_NAME)
        .hasContextualNameEqualTo("einvoice.pipeline.render-pdf")
        .hasLowCardinalityKeyValue(PipelineObservations.STEP_TAG, "render-pdf")
        .doesNotHaveError();
  }

  @Test
  @DisplayName("the step's work runs exactly once and its value comes back unchanged")
  void isTransparent() {
    AtomicInteger invocations = new AtomicInteger();

    String result =
        observations.observe(
            PipelineStep.MAP_UBL,
            () -> {
              invocations.incrementAndGet();
              return "mapped";
            });

    assertThat(result).isEqualTo("mapped");
    assertThat(invocations).hasValue(1);
  }

  @Test
  @DisplayName("the void overload runs its work and still records the step")
  void observesWorkThatReturnsNothing() {
    AtomicInteger invocations = new AtomicInteger();

    observations.observe(PipelineStep.PERSIST_INVOICE, (Runnable) invocations::incrementAndGet);

    assertThat(invocations).hasValue(1);
    TestObservationRegistryAssert.assertThat(registry)
        .hasNumberOfObservationsEqualTo(1)
        .hasSingleObservationThat()
        .hasLowCardinalityKeyValue(PipelineObservations.STEP_TAG, "persist-invoice");
  }

  @Test
  @DisplayName("a failing step is recorded as an error and the exception propagates unchanged")
  void doesNotSwallowAFailure() {
    RuntimeException boom = new IllegalStateException("mapping failed");

    assertThatThrownBy(
            () ->
                observations.observe(
                    PipelineStep.MAP_EBINTERFACE,
                    () -> {
                      throw boom;
                    }))
        .isSameAs(boom);

    TestObservationRegistryAssert.assertThat(registry).hasSingleObservationThat().hasError(boom);
  }

  /**
   * The tag vocabulary is an enum, so it is finite by construction — but a duplicate {@code tag()}
   * would silently merge two different steps into one time series, which the enum alone does not
   * prevent.
   */
  @Test
  @DisplayName("every step contributes a distinct, kebab-case tag value")
  void tagsAreDistinctAndWellFormed() {
    assertThat(Arrays.stream(PipelineStep.values()).map(PipelineStep::tag).toList())
        .doesNotHaveDuplicates()
        .allSatisfy(tag -> assertThat(tag).matches("[a-z]+(-[a-z]+)*"));
  }
}
