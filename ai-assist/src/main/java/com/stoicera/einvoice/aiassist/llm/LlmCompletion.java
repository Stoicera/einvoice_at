package com.stoicera.einvoice.aiassist.llm;

/**
 * A successful completion: the model's answer, and what it cost.
 *
 * <p>{@code text} is the raw provider text, trimmed and nothing more — no truncation, no
 * post-processing. Bounding the value to what a {@code Finding} can hold is the consumer's job
 * ({@link com.stoicera.einvoice.aiassist.FindingExplainer}), because the bound belongs to the
 * domain type being filled and not to the transport.
 *
 * @param text the model's answer
 * @param usage tokens and cost for the call that produced it
 */
public record LlmCompletion(String text, LlmUsage usage) {

  public LlmCompletion {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("LLM completion text must not be blank");
    }
    if (usage == null) {
      throw new IllegalArgumentException("LLM completion usage must not be null");
    }
  }
}
