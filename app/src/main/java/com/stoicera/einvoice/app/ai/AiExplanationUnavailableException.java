package com.stoicera.einvoice.app.ai;

/**
 * Explanations were asked for and the provider produced none — mapped to {@code 503} + {@code
 * ai-explanation-unavailable}.
 *
 * <p><strong>Why this is not a 200 with null explanations.</strong> {@code aiExplanation} being
 * {@code null} already means "not explained", and a report whose findings are all null is exactly
 * what a caller sees when there was nothing to explain. Answering a total provider outage with that
 * same body would make a failure indistinguishable from a success — the caller retries nothing,
 * logs nothing, and quietly shows no explanations forever. The browser route degrades differently
 * and correctly: it renders a friendly notice, because a human reading the page can see it.
 *
 * <p>Raised only when <em>every</em> requested explanation failed. A partial result is a {@code
 * 200}: some findings carry an explanation, the rest carry {@code null}, and that body is honest.
 */
public class AiExplanationUnavailableException extends RuntimeException {

  public AiExplanationUnavailableException(int requested) {
    super(
        "The AI provider produced no explanation for any of the "
            + requested
            + " requested findings");
  }
}
