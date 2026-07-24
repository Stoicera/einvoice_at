package com.stoicera.einvoice.core.validation;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.util.List;

/**
 * Aggregate result of running one validation run (across all its stages — XSD, Schematron, and
 * business rules) over one document: the {@code sourceFormat} and {@code profile} it was validated
 * against, and the ordered {@link Finding}s it produced.
 *
 * <p>{@code sourceFormat} names the document format validated, e.g. {@code "ebinterface-6.1"} (the
 * token {@code EbInterface61Validator} emits) or a future {@code "ubl-2.1"}; {@code profile} names
 * the validation profile applied, e.g. an Austrian B2G profile identifier or a Peppol BIS
 * customization id.
 */
public record ValidationReport(String sourceFormat, String profile, List<Finding> findings) {

  /** Defensive DoS bound, not a business rule: the format name must stay bounded. */
  private static final int MAX_SOURCE_FORMAT_LENGTH = 64;

  /**
   * Defensive DoS bound, not a business rule: a profile identifier (e.g. a Peppol customization
   * URN) must stay bounded.
   */
  private static final int MAX_PROFILE_LENGTH = 256;

  public ValidationReport {
    requireNonBlank(sourceFormat, "Validation report source format");
    requireMaxLength(sourceFormat, MAX_SOURCE_FORMAT_LENGTH, "Validation report source format");
    requireNonBlank(profile, "Validation report profile");
    requireMaxLength(profile, MAX_PROFILE_LENGTH, "Validation report profile");
    if (findings == null) {
      throw new InvariantViolationException("Validation report findings must not be null");
    }
    for (Finding finding : findings) {
      if (finding == null) {
        throw new InvariantViolationException(
            "Validation report findings must not contain null entries");
      }
    }
    findings = List.copyOf(findings);
  }

  /** Whether the document is compliant: no {@link Severity#ERROR} finding is present. */
  public boolean isValid() {
    return countOf(Severity.ERROR) == 0;
  }

  /** Findings of the given severity, in the order they appear in {@link #findings()}. */
  public List<Finding> findingsOf(Severity severity) {
    requireSeverity(severity);
    return findings.stream().filter(finding -> finding.severity() == severity).toList();
  }

  /** Number of findings of the given severity. */
  public long countOf(Severity severity) {
    return findingsOf(severity).size();
  }

  private static void requireSeverity(Severity severity) {
    if (severity == null) {
      throw new InvariantViolationException("Severity must not be null");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new InvariantViolationException("%s must not be blank".formatted(field));
    }
  }

  private static void requireMaxLength(String value, int max, String field) {
    if (value != null && value.length() > max) {
      throw new InvariantViolationException("%s exceeds %d characters".formatted(field, max));
    }
  }
}
