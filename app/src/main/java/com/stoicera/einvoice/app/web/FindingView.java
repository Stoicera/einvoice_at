package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.util.List;

/**
 * One finding, prepared for rendering: the finding itself plus its index in the report.
 *
 * <p>The index exists for one reason and it is worth stating, because "just send the rule id" looks
 * simpler: the "Erklären" button has to name <em>which</em> finding to explain, and a rule id is
 * not unique within a report — a document missing both parties' electronic addresses reports {@code
 * PEPPOL-EN16931-R020} and {@code R010} separately, and a document with three malformed lines
 * reports the same rule three times. Keying on the position is what keeps the second and third
 * buttons from explaining the first finding.
 *
 * @param index zero-based position in the report's finding list
 * @param finding the finding
 */
public record FindingView(int index, Finding finding) {

  /**
   * The CSS class suffix for this finding's severity — {@code error}, {@code warn} or {@code info}.
   */
  public String severityClass() {
    return finding.severity().name().toLowerCase(java.util.Locale.ROOT);
  }

  /** German severity label, so the template carries no vocabulary of its own. */
  public String severityLabel() {
    return switch (finding.severity()) {
      case ERROR -> "Fehler";
      case WARN -> "Warnung";
      case INFO -> "Hinweis";
    };
  }

  /** Whether an AI explanation has already been attached to this finding. */
  public boolean hasExplanation() {
    return finding.aiExplanation() != null && !finding.aiExplanation().isBlank();
  }

  /** Wraps a report's findings, preserving order and assigning each its index. */
  public static List<FindingView> of(List<Finding> findings) {
    return java.util.stream.IntStream.range(0, findings.size())
        .mapToObj(i -> new FindingView(i, findings.get(i)))
        .toList();
  }

  /** The findings of one severity, indices preserved from the full list. */
  public static List<FindingView> ofSeverity(List<FindingView> all, Severity severity) {
    return all.stream().filter(view -> view.finding().severity() == severity).toList();
  }
}
