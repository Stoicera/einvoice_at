package com.stoicera.einvoice.app.ai;

/**
 * The AI explanation feature is not configured on this deployment ({@code
 * features.ai-explanations=false}, the shipped default), so there is nothing to explain
 * <em>with</em> — mapped to {@code 503} + {@code ai-explanations-disabled}.
 *
 * <p>Deliberately distinct from {@link AiExplanationUnavailableException}: this one says "this
 * platform has no AI configured", that one says "it does, and the provider did not answer". An
 * operator can act on the first (set a key, or accept the default) and on the second (check the
 * provider); collapsing both into one status would leave them guessing which they had.
 */
public class AiExplanationsDisabledException extends RuntimeException {

  public AiExplanationsDisabledException() {
    super("features.ai-explanations is disabled on this deployment");
  }
}
