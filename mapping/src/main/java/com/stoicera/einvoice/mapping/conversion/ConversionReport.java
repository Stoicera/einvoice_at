package com.stoicera.einvoice.mapping.conversion;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.util.List;

/**
 * What a format conversion could not carry across, and what it had to reinterpret.
 *
 * <p>MILESTONES M4 asks for "Konvertierung mit Verlust-Report", and this is it. Every conversion
 * between two invoice syntaxes is lossy in edge cases (SPEC §10), and the honest response is not to
 * minimise the losses in documentation but to enumerate them per document, in German first, at the
 * moment they happen.
 *
 * <p>A conversion runs through the canonical model rather than syntax-to-syntax, so losses arrive
 * from two directions and both are collected here: what the <em>source</em> carried that the
 * canonical model does not represent, and what the canonical model carries that the <em>target</em>
 * cannot express.
 *
 * <h2>What the two format strings are, and are not</h2>
 *
 * <p>They are the <strong>caller's own request vocabulary</strong> — the values accepted by {@code
 * POST /convert?from=&to=}, i.e. {@code ebinterface} and {@code ubl}. A conversion report answers
 * "what did the trip you asked for cost", so it names the trip the way the caller named it.
 *
 * <p>They are deliberately <em>not</em> the same vocabulary as a {@code ValidationReport}'s {@code
 * sourceFormat}, which is a {@code DocumentFormat} identifier ({@code ubl-invoice-2.1}) naming what
 * a document was <em>detected</em> to be. A single {@code /convert} response therefore carries both
 * spellings, under fields that happen to share a name, because they answer two different questions:
 * "which conversion did you request" and "what did the validator decide the output is". Stated here
 * because the M4 hostile review (finding F7) found this class's own documentation claiming the
 * second vocabulary while production supplied the first, and a unit test asserting values that
 * never ship.
 *
 * @param sourceFormat the format converted from, as the caller named it — {@code ebinterface} or
 *     {@code ubl}
 * @param targetFormat the format converted to, as the caller named it — {@code ebinterface} or
 *     {@code ubl}
 * @param notes every loss, convention translation and deviation, in the order they were produced
 */
public record ConversionReport(String sourceFormat, String targetFormat, List<Finding> notes) {

  public ConversionReport {
    notes = List.copyOf(notes);
  }

  /**
   * Whether the conversion carried everything across without loss or reinterpretation.
   *
   * <p>Deliberately distinct from {@link #isTrustworthy()}: a conversion can be lossy and still
   * perfectly usable — dropping a field the target has no concept of is normal, not a failure.
   */
  public boolean isLossless() {
    return notes.isEmpty();
  }

  /**
   * Whether the converted document can be trusted to mean what the source meant.
   *
   * <p>{@code false} only when a note is {@link Severity#ERROR} — in practice, when the source's
   * own stated totals disagreed with what the canonical model derives from its lines. Losses
   * ({@code WARN}) and convention translations ({@code INFO}) do not make a conversion
   * untrustworthy; a changed amount does.
   */
  public boolean isTrustworthy() {
    return notes.stream().noneMatch(note -> note.severity() == Severity.ERROR);
  }

  // No `lossless(...)` factory and no `plus(...)` combinator here. Both existed, both were called
  // only by this record's own unit test, and DoD §1 forbids dead paths — a convenience nobody uses
  // is a maintenance cost and a reader's false lead, not an affordance. ConversionService
  // assembles its note list before constructing the report and has nothing to append afterwards;
  // if a caller ever genuinely needs to combine two reports, the method can come back then, with a
  // caller. Removed in the M4 hostile review (finding F7).
}
