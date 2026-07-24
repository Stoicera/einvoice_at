package com.stoicera.einvoice.validation;

import com.stoicera.einvoice.core.validation.Finding;
import java.util.List;

/**
 * One step of the validation pipeline (format detection, XSD, Schematron or business rules).
 *
 * <p>A stage reads the shared {@link ValidationContext} — the raw bytes and everything derived from
 * them so far — and returns the {@link Finding}s it produced, most-significant first. A stage never
 * throws on bad input: malformed or non-compliant input is the domain, and every problem is a
 * finding, not an exception. An empty list means the stage found nothing to report.
 *
 * <p>Each stage is a named class ({@code FormatDetectionStage}, {@code XsdValidationStage}, …) that
 * the facade holds as its own field and drives with bespoke, short-circuiting control flow rather
 * than iterating a {@code List<ValidationStage>}; the interface is the shared shape of a stage, not
 * a lambda target — hence no {@code @FunctionalInterface}.
 */
public interface ValidationStage {

  /**
   * Runs this stage over {@code ctx}.
   *
   * @param ctx the shared, mutable per-run context
   * @return the findings produced, never {@code null} (empty when the stage is satisfied)
   */
  List<Finding> apply(ValidationContext ctx);
}
