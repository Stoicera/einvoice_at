package com.stoicera.einvoice.aiassist;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What {@link FindingExplainer} needs to know about the document a finding came from, beyond the
 * finding itself.
 *
 * <p>Deliberately not a {@code ValidationReport}: both callers already have this much and neither
 * has a whole report to hand at the moment it explains one finding — the live validator has the
 * report but the dashboard has a stored {@code ReportDetail} row, and asking it to rebuild a {@code
 * ValidationReport} to explain one finding would be ceremony.
 *
 * @param sourceFormat the detected format, e.g. {@code ebinterface-6.1} — the same token the
 *     validator reports. Gives the model the vocabulary the answer should use.
 * @param profile the validation profile applied, e.g. {@code at-b2g} or a Peppol customization id
 * @param sensitiveLiterals values the caller knows to be personal data and wants redacted literally
 *     before anything is sent — in practice the seller and buyer names, when the report is tied to
 *     a stored invoice. Empty for the anonymous public validator, which retains nothing to read
 *     them from; see {@link com.stoicera.einvoice.aiassist.internal.PiiScrubber} for what that
 *     costs and what still protects the caller.
 */
public record ExplanationContext(
    String sourceFormat, String profile, Set<String> sensitiveLiterals) {

  public ExplanationContext {
    requireNonBlank(sourceFormat, "Explanation context source format");
    requireNonBlank(profile, "Explanation context profile");
    if (sensitiveLiterals == null) {
      throw new IllegalArgumentException(
          "Explanation context sensitiveLiterals must not be null; use Set.of() when there are none");
    }
    sensitiveLiterals = Set.copyOf(sensitiveLiterals);
  }

  /** Context with nothing to redact literally — the public validator's case. */
  public static ExplanationContext of(String sourceFormat, String profile) {
    return new ExplanationContext(sourceFormat, profile, Set.of());
  }

  /**
   * Context redacting the given party names, skipping any that are null or blank.
   *
   * <p>{@code Arrays.stream}, not {@code List.of(names)}: {@code List.of} rejects a null element
   * outright, and a null name is exactly what this factory exists to tolerate — a stored invoice's
   * buyer name is optional in the canonical model.
   */
  public static ExplanationContext withPartyNames(
      String sourceFormat, String profile, String... names) {
    return new ExplanationContext(
        sourceFormat,
        profile,
        Arrays.stream(names == null ? new String[0] : names)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.toUnmodifiableSet()));
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
