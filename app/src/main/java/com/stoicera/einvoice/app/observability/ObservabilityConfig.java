package com.stoicera.einvoice.app.observability;

import com.stoicera.einvoice.validation.ValidationObserver;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the observability adapters over the library modules' plain-Java ports (M6, ADR-0012).
 *
 * <p>There is exactly one bean here, and it exists because {@code validation} must not know what a
 * span is: {@link MicrometerValidationObserver} is the {@code app}-side half of that boundary. The
 * step observations {@code app} makes on its own behalf need no adapter — {@link
 * PipelineObservations} is a component in this package.
 *
 * <p>Nothing here is conditional on tracing being enabled. Micrometer's {@link ObservationRegistry}
 * is always present; with {@code OTEL_ENABLED=false} it simply carries no tracing handler, so the
 * adapter is wired identically in every deployment and observability is switched on by
 * configuration rather than by a different object graph. A conditional bean would mean the
 * instrumented and un-instrumented builds were two different programs, which is precisely the thing
 * you do not want to discover in production.
 */
@Configuration
class ObservabilityConfig {

  @Bean
  ValidationObserver validationObserver(ObservationRegistry registry) {
    return new MicrometerValidationObserver(registry);
  }
}
