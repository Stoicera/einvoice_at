package com.stoicera.einvoice.aiassist.llm;

/**
 * One completion request, in provider-independent terms: a system instruction and a user message.
 *
 * <p><strong>Two fields, and the two obvious extra ones are missing on purpose.</strong>
 *
 * <p>No sampling parameter. The current Anthropic models reject a non-default {@code temperature},
 * {@code top_p} or {@code top_k} with HTTP 400 (all three were removed with Opus 4.7 / Sonnet 5),
 * and OpenRouter forwards the body it is handed — so a port offering a {@code temperature} would
 * offer a knob whose only effect against this platform's default model is to break every request.
 * Tone and determinism come from the prompt template instead, where this project wants them anyway:
 * versioned under {@code src/main/resources/prompts} and reviewable in a diff.
 *
 * <p>No output-token budget. That is provider configuration, not part of the question — it belongs
 * to the adapter's settings, which are wired from the environment, and having it here as well would
 * mean two places to change one number and a caller having to guess which one wins.
 *
 * @param systemMessage the standing instruction — who the model is and what shape the answer takes
 * @param userMessage the concrete question; for this platform, an already-PII-scrubbed finding
 */
public record LlmPrompt(String systemMessage, String userMessage) {

  public LlmPrompt {
    requireNonBlank(systemMessage, "LLM prompt system message");
    requireNonBlank(userMessage, "LLM prompt user message");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
