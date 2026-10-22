package com.stoicera.einvoice.validation.repair;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import java.util.List;
import java.util.Objects;

/**
 * A correction proposal and its evidence: what changes, where each value comes from, the corrected
 * document, the diff against the upload, and what the validator says about the corrected document.
 *
 * <p>When no change could be applied — every change is a {@link ValueSource#PLACEHOLDER} waiting
 * for input — {@code correctedXml}, {@code diff} and {@code revalidation} are {@code null}: there
 * is no corrected document to show or to judge, and the proposal says which inputs are missing.
 *
 * @param changes every change, applied or waiting for input, in the order of the findings
 * @param correctedXml the corrected document as text, or {@code null} when nothing was applied
 * @param diff unified diff from the upload to the corrected document, or {@code null}
 * @param revalidation the validator's report on the corrected document, or {@code null}
 */
public record RepairProposal(
    List<ProposedChange> changes, String correctedXml, String diff, ValidationReport revalidation) {

  public RepairProposal {
    changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    if ((correctedXml == null) != (revalidation == null)
        || (correctedXml == null) != (diff == null)) {
      throw new IllegalArgumentException("corrected document, diff and re-validation go together");
    }
  }

  /** Whether at least one change was written, i.e. there is a corrected document. */
  public boolean hasCorrectedDocument() {
    return correctedXml != null;
  }

  /** Whether the corrected document passed the re-validation with no error at all. */
  public boolean revalidatedValid() {
    return revalidation != null && revalidation.isValid();
  }

  /**
   * Whether the re-validation no longer reports the rule {@code change} answers. Only an applied
   * change can be resolved; a placeholder never is.
   */
  public boolean resolves(ProposedChange change) {
    return change.applied()
        && revalidation != null
        && revalidation.findings().stream().noneMatch(f -> f.ruleId().equals(change.ruleId()));
  }

  /** Changes still waiting for a value. */
  public List<ProposedChange> pendingInputs() {
    return changes.stream().filter(c -> !c.applied()).toList();
  }

  /** Errors the re-validation still reports — the ones this proposal did not or could not fix. */
  public List<Finding> remainingErrors() {
    return revalidation == null ? List.of() : revalidation.findingsOf(Severity.ERROR);
  }
}
