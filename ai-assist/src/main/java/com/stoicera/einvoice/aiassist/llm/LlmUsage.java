package com.stoicera.einvoice.aiassist.llm;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * What one completion consumed: the model that served it, the token counts, and the cost the
 * provider charged.
 *
 * <p>ENGINEERING_STANDARDS §8 requires "Kosten-/Token-Logging" for every AI feature. This record is
 * the value that requirement is met with; {@code app} bridges it to Micrometer, so this module
 * needs no metrics library (see {@link LlmUsageListener}).
 *
 * <p><strong>{@code costUsd} is the provider's own figure, not a local calculation.</strong>
 * OpenRouter returns the charge for a call when asked to, and reading it is strictly better than
 * multiplying tokens by a hard-coded price table: a price table in this repository would be a
 * second source of truth that goes stale the day a provider changes its rates, and would then
 * report a confidently wrong number rather than no number. When the provider reports no cost the
 * {@link Optional} is empty — an absent metric is honest, an invented one is not.
 *
 * <p>{@code Optional} as a record component follows this repository's existing convention for
 * genuinely optional values (see {@code core}'s {@code Party.email} and {@code
 * Invoice.deliveryDate}), which keeps "absent" impossible to confuse with "not yet set".
 *
 * @param model the model id that actually served the request, as reported by the provider — not the
 *     one that was asked for, which can differ when a provider routes or substitutes
 * @param promptTokens tokens billed for the input
 * @param completionTokens tokens billed for the output
 * @param costUsd the provider-reported cost in US dollars, empty if it reported none
 */
public record LlmUsage(
    String model, int promptTokens, int completionTokens, Optional<BigDecimal> costUsd) {

  public LlmUsage {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("LLM usage model must not be blank");
    }
    if (promptTokens < 0 || completionTokens < 0) {
      throw new IllegalArgumentException(
          "LLM usage token counts must not be negative, were %d/%d"
              .formatted(promptTokens, completionTokens));
    }
    if (costUsd == null) {
      throw new IllegalArgumentException(
          "LLM usage costUsd must not be null; use Optional.empty() when the provider reported none");
    }
    if (costUsd.isPresent() && costUsd.get().signum() < 0) {
      throw new IllegalArgumentException(
          "LLM usage cost must not be negative, was " + costUsd.get());
    }
  }

  /** Total tokens billed for the call. */
  public int totalTokens() {
    return promptTokens + completionTokens;
  }
}
