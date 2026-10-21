package com.stoicera.einvoice.validation.repair;

import java.util.Objects;

/**
 * One change a {@link RepairProposal} makes, or would make once an input is supplied.
 *
 * @param ruleId the finding's rule id this change answers, e.g. {@code PEPPOL-EN16931-R020}
 * @param businessTerm the EN 16931 business term the change fills, e.g. {@code BT-34}
 * @param fieldDe German name of the field, e.g. "Elektronische Adresse des Verkäufers"
 * @param path where in the document the value goes, as a readable element path
 * @param value the value written, or {@code null} for a {@link ValueSource#PLACEHOLDER}
 * @param schemeId the identifier scheme written with the value (Peppol EAS code), or {@code null}
 * @param source where the value came from
 * @param sourceDe German provenance sentence: which element or which input, and why that one
 * @param noteDe what the rule set does <em>not</em> check about this value, or {@code null}
 * @param inputName the form/API parameter that supplies this value, or {@code null} when none
 */
public record ProposedChange(
    String ruleId,
    String businessTerm,
    String fieldDe,
    String path,
    String value,
    String schemeId,
    ValueSource source,
    String sourceDe,
    String noteDe,
    String inputName) {

  public ProposedChange {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(businessTerm, "businessTerm");
    Objects.requireNonNull(fieldDe, "fieldDe");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(sourceDe, "sourceDe");
    if ((source == ValueSource.PLACEHOLDER) != (value == null)) {
      throw new IllegalArgumentException("a placeholder has no value, every other source has one");
    }
  }

  /** Whether this change was written into the corrected document. */
  public boolean applied() {
    return source != ValueSource.PLACEHOLDER;
  }
}
