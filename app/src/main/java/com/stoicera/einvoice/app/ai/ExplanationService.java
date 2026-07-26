package com.stoicera.einvoice.app.ai;

import com.stoicera.einvoice.aiassist.ExplanationContext;
import com.stoicera.einvoice.aiassist.FindingExplainer;
import com.stoicera.einvoice.core.validation.Finding;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The one seam the rest of {@code app} asks for an explanation through — and the one place that
 * knows the feature might not be there at all.
 *
 * <p>{@link AiConfig} contributes a {@link FindingExplainer} only when {@code
 * features.ai-explanations} is on, so this service holds an {@link ObjectProvider} rather than the
 * explainer itself. Every caller then asks the same two questions ({@link #isEnabled()}, {@link
 * #explain}) regardless of configuration, instead of each controller and template re-deriving "is
 * AI available" from a property it happens to have injected.
 *
 * <p>Both answers degrade the same way: with the feature off, {@link #isEnabled()} is {@code false}
 * and {@link #explain} is empty; with it on but the provider unreachable, {@link #isEnabled()} is
 * {@code true} and {@link #explain} is empty. The distinction matters to the UI — the first case
 * hides the button, the second shows a friendly notice — and to nothing else.
 */
@Service
public class ExplanationService {

  private final ObjectProvider<FindingExplainer> explainer;

  public ExplanationService(ObjectProvider<FindingExplainer> explainer) {
    this.explainer = explainer;
  }

  /**
   * Whether the feature is configured at all — i.e. whether to offer "Erklären" in the first place.
   */
  public boolean isEnabled() {
    return explainer.getIfAvailable() != null;
  }

  /**
   * Explains one finding, or returns empty when the feature is off or the provider could not
   * answer.
   *
   * <p>Never throws: {@link FindingExplainer#explain} guarantees that, and the disabled case short-
   * circuits before reaching it.
   */
  public Optional<String> explain(Finding finding, ExplanationContext context) {
    FindingExplainer available = explainer.getIfAvailable();
    return available == null ? Optional.empty() : available.explain(finding, context);
  }
}
