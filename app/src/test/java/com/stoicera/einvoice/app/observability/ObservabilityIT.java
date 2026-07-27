package com.stoicera.einvoice.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.validation.ValidationObserver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The instrumentation is actually wired into the running application — not merely implemented.
 *
 * <p>This is the test that would catch the most likely way for M6's observability claim to become
 * false: {@code InvoicePipelineConfig} calling {@code new InvoiceValidator()} instead of the
 * observer-taking constructor. Every unit test in this package would still pass, the traces would
 * simply be empty, and nothing short of a Grafana screenshot would say so.
 *
 * <p>Assertions run against the real {@link ObservationRegistry} Boot builds for this context, read
 * through a recording {@link ObservationHandler} <em>registered as a bean</em> — which is exactly
 * how the production OpenTelemetry handler arrives, so this exercises the real registration path.
 * Nothing here needs a collector, an exporter or a network.
 */
@SpringBootTest
@Import(ObservabilityIT.RecordingHandlerConfiguration.class)
class ObservabilityIT extends AbstractPostgresIT {

  @Autowired private ReportService reports;
  @Autowired private RecordingObservationHandler handler;
  @Autowired private ObservationRegistry registry;

  @BeforeEach
  void clearRecordedObservations() {
    handler.recorded.clear();
  }

  @Test
  @DisplayName("an ebInterface validation produces one span per pipeline stage, in pipeline order")
  void tracesEveryEbInterfaceStage() {
    reports.validate(fixture("invoice-b2g-sample.ebinterface.xml"), Optional.empty());

    assertThat(stageTags())
        .containsExactly(
            ValidationObserver.STAGE_PARSE,
            ValidationObserver.STAGE_FORMAT_DETECTION,
            ValidationObserver.STAGE_XSD,
            ValidationObserver.STAGE_SCHEMATRON,
            ValidationObserver.STAGE_BUSINESS_RULES);
  }

  @Test
  @DisplayName("the UBL pipeline's single Peppol stage is traced too, so both routes are visible")
  void tracesThePeppolPipeline() {
    reports.validate(fixture("invoice-b2g-sample.ubl.xml"), Optional.empty());

    assertThat(stageTags())
        .containsExactly(
            ValidationObserver.STAGE_PARSE,
            ValidationObserver.STAGE_FORMAT_DETECTION,
            ValidationObserver.STAGE_PEPPOL);
  }

  /**
   * The stage observations nest under the surrounding work rather than standing as unrelated roots
   * — which is what makes them a <em>trace across the pipeline</em> (MILESTONES M6: "OTel
   * vollständig (Traces über Pipeline-Stufen)") rather than a handful of timers that happen to
   * share a name prefix.
   */
  @Test
  @DisplayName("a stage observed inside a pipeline step is nested under that step")
  void nestsStagesUnderTheirCaller() {
    new PipelineObservations(registry)
        .observe(
            PipelineStep.READ_CANONICAL_JSON,
            () ->
                reports.validate(fixture("invoice-b2g-sample.ebinterface.xml"), Optional.empty()));

    List<Observation.Context> stages =
        handler.recorded.stream()
            .filter(c -> MicrometerValidationObserver.OBSERVATION_NAME.equals(c.getName()))
            .toList();

    assertThat(stages).isNotEmpty();
    assertThat(stages)
        .allSatisfy(
            context ->
                assertThat(context.getParentObservation())
                    .as("stage %s must nest under its caller", context.getContextualName())
                    .isNotNull());
  }

  private List<String> stageTags() {
    return handler.recorded.stream()
        .filter(c -> MicrometerValidationObserver.OBSERVATION_NAME.equals(c.getName()))
        .map(c -> c.getLowCardinalityKeyValue(MicrometerValidationObserver.STAGE_TAG).getValue())
        .toList();
  }

  private static byte[] fixture(String name) {
    try (var in = ObservabilityIT.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Fixture not found on classpath: " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Records every observation this context completes, newest last. */
  static final class RecordingObservationHandler
      implements ObservationHandler<Observation.Context> {

    private final List<Observation.Context> recorded = new CopyOnWriteArrayList<>();

    @Override
    public void onStop(Observation.Context context) {
      recorded.add(context);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }

  @TestConfiguration
  static class RecordingHandlerConfiguration {
    @Bean
    RecordingObservationHandler recordingObservationHandler() {
      return new RecordingObservationHandler();
    }
  }
}
