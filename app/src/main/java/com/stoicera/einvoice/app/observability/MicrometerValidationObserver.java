package com.stoicera.einvoice.app.observability;

import com.stoicera.einvoice.validation.ValidationObserver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;

/**
 * Bridges the {@code validation} module's stage seam to Micrometer, so the XSD, Schematron,
 * business-rule and Peppol stages appear as spans and timers (M6, ADR-0012).
 *
 * <p>This class is the whole reason {@link ValidationObserver} exists as a plain-Java port: SPEC §2
 * keeps {@code validation} free of Spring, and Micrometer is how this platform reaches
 * OpenTelemetry from {@code app}. The same split {@code ai-assist} uses for its token/cost numbers.
 *
 * <h2>The stage name is a tag, and it is bounded</h2>
 *
 * <p>{@code stageName} is used directly as the low-cardinality tag value. That is safe here and
 * would not be safe for an arbitrary string: the values come from the {@code STAGE_*} constants on
 * {@link ValidationObserver} — compile-time constants in a module this repository owns — and never
 * from a document, a caller or a third party's response body. {@code
 * MicrometerValidationObserverTest} enumerates those constants by reflection and asserts each one
 * produces a meter, so adding a stage stays a deliberate act rather than a silent new time series.
 */
public final class MicrometerValidationObserver implements ValidationObserver {

  /** The single observation name every validation stage is recorded under. */
  public static final String OBSERVATION_NAME = "einvoice.validation.stage";

  /** The low-cardinality tag carrying the stage name. */
  public static final String STAGE_TAG = "stage";

  private final ObservationRegistry registry;

  public MicrometerValidationObserver(ObservationRegistry registry) {
    this.registry = registry;
  }

  @Override
  public <T> T observe(String stageName, Supplier<T> stage) {
    return Observation.createNotStarted(OBSERVATION_NAME, registry)
        .lowCardinalityKeyValue(STAGE_TAG, stageName)
        .contextualName(OBSERVATION_NAME + "." + stageName)
        .observe(stage);
  }
}
