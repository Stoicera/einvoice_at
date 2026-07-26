package com.stoicera.einvoice.app.ai;

import com.stoicera.einvoice.aiassist.FindingExplainer;
import com.stoicera.einvoice.aiassist.llm.LlmClient;
import com.stoicera.einvoice.aiassist.llm.LlmUsageListener;
import com.stoicera.einvoice.aiassist.openrouter.OpenRouterLlmClient;
import com.stoicera.einvoice.aiassist.openrouter.OpenRouterSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Wires the AI explanation feature — and only when it is switched on.
 *
 * <p><strong>The flag is the whole point.</strong> SPEC §2 names {@code features.ai-explanations}
 * and the M5 Abnahme requires "KI abschaltbar ohne Funktionsverlust". With the flag off this class
 * contributes no beans at all: no {@link FindingExplainer} exists, {@link ExplanationService}
 * reports the feature as unavailable, the "Erklären" buttons are not rendered, and {@code POST
 * /api/v1/reports/{id}/explain} answers {@code 503} with a problem document. Nothing else about the
 * platform changes — which is a stronger property than "the calls fail gracefully", because there
 * is no provider client in the context to fail.
 *
 * <p>The flag defaults to <strong>off</strong> ({@code matchIfMissing = false}). A feature that
 * costs money per click and sends text to a third party should be something an operator turns on
 * deliberately, not something they discover in a bill.
 *
 * <p><strong>A missing API key is a startup failure, not a silent degradation.</strong> If the flag
 * is on and {@code AI_API_KEY} is blank, the context fails to start with a message naming both. The
 * alternative — booting with a client that cannot possibly work — would turn a configuration
 * mistake into "the AI feature is mysteriously always unavailable", diagnosable only by reading
 * logs nobody is watching.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "features",
    name = "ai-explanations",
    havingValue = "true",
    matchIfMissing = false)
public class AiConfig {

  private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

  @Bean
  LlmUsageListener llmUsageListener(MeterRegistry meters) {
    return new MicrometerLlmUsageListener(meters);
  }

  @Bean
  LlmClient llmClient(
      @Value("${app.ai.base-url}") String baseUrl,
      @Value("${app.ai.api-key:}") String apiKey,
      @Value("${app.ai.model}") String model,
      @Value("${app.ai.timeout}") Duration timeout,
      @Value("${app.ai.max-retries}") int maxRetries,
      @Value("${app.ai.max-output-tokens}") int maxOutputTokens,
      LlmUsageListener usageListener) {
    if (!StringUtils.hasText(apiKey)) {
      throw new IllegalStateException(
          "features.ai-explanations is enabled but app.ai.api-key (AI_API_KEY) is not set."
              + " Either provide a key or set FEATURES_AI_EXPLANATIONS=false.");
    }
    OpenRouterSettings settings =
        new OpenRouterSettings(
            URI.create(baseUrl), apiKey, model, timeout, maxRetries, maxOutputTokens);
    // toString redacts the key (OpenRouterSettings overrides it for exactly this reason), so
    // logging
    // the effective configuration is safe and worth doing: "which model am I actually paying for"
    // is the first question an operator asks.
    log.info("AI explanations enabled: {}", settings);
    return new OpenRouterLlmClient(settings, usageListener);
  }

  @Bean
  FindingExplainer findingExplainer(LlmClient llmClient) {
    return new FindingExplainer(llmClient);
  }
}
