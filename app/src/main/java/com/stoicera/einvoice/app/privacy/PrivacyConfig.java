package com.stoicera.einvoice.app.privacy;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the privacy features: the retention job's schedule and its notion of "now".
 *
 * <p>{@link EnableScheduling} lives here rather than on the application class so that the one
 * scheduled thing this platform has is switched on next to the code that uses it — and so that
 * adding a second scheduled job is a deliberate act rather than something that silently starts
 * working.
 *
 * <p>The {@link Clock} is a bean for one reason: a retention window is untestable if "now" is a
 * static call. {@link ConditionalOnMissingBean} lets a test replace it with a fixed clock without
 * excluding this configuration and losing the schedule with it.
 */
@Configuration
@EnableScheduling
public class PrivacyConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }
}
