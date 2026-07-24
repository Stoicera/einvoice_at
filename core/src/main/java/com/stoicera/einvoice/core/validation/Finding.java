package com.stoicera.einvoice.core.validation;

import com.stoicera.einvoice.core.InvariantViolationException;

/**
 * One validator finding: a single rule violation or informational note produced by a validation
 * stage (XSD, Schematron, or a hand-written business rule).
 *
 * <p>{@code messageDe} is the primary text — project policy requires every finding to carry a
 * German message; {@code messageEn} is the secondary, English translation. {@code location} is
 * nullable: an XPath into the source document, a {@code line:col} reference, or {@code null} when
 * the finding has no localizable position (e.g. a document-level rule). {@code aiExplanation} is
 * nullable: a caller-attached, AI-generated plain-language explanation added after the fact via
 * {@link #withAiExplanation(String)}; it is {@code null} until then.
 */
public record Finding(
    Severity severity,
    String ruleId,
    String location,
    String messageDe,
    String messageEn,
    String aiExplanation) {

  /** Defensive DoS bound, not a business rule: a rule identifier must stay bounded. */
  private static final int MAX_RULE_ID_LENGTH = 128;

  /** Defensive DoS bound, not a business rule: an XPath or line:col location must stay bounded. */
  private static final int MAX_LOCATION_LENGTH = 1024;

  /** Defensive DoS bound, not a business rule: a free-text finding message must stay bounded. */
  private static final int MAX_MESSAGE_LENGTH = 4096;

  /**
   * Defensive DoS bound, not a business rule: an AI-generated explanation is longer-form prose than
   * the other fields, but it must still stay bounded.
   */
  private static final int MAX_AI_EXPLANATION_LENGTH = 8192;

  public Finding {
    if (severity == null) {
      throw new InvariantViolationException("Finding severity must not be null");
    }
    requireNonBlank(ruleId, "Finding rule id");
    requireMaxLength(ruleId, MAX_RULE_ID_LENGTH, "Finding rule id");
    requireMaxLength(location, MAX_LOCATION_LENGTH, "Finding location");
    requireNonBlank(messageDe, "Finding German message");
    requireMaxLength(messageDe, MAX_MESSAGE_LENGTH, "Finding German message");
    requireNonBlank(messageEn, "Finding English message");
    requireMaxLength(messageEn, MAX_MESSAGE_LENGTH, "Finding English message");
    requireMaxLength(aiExplanation, MAX_AI_EXPLANATION_LENGTH, "Finding AI explanation");
  }

  /**
   * Creates a finding without an AI explanation — the common case; see {@link #aiExplanation()}.
   */
  public static Finding of(
      Severity severity, String ruleId, String location, String messageDe, String messageEn) {
    return new Finding(severity, ruleId, location, messageDe, messageEn, null);
  }

  /** Returns a copy of this finding with {@code aiExplanation} attached. */
  public Finding withAiExplanation(String aiExplanation) {
    return new Finding(severity, ruleId, location, messageDe, messageEn, aiExplanation);
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
