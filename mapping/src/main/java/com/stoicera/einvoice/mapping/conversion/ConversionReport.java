package com.stoicera.einvoice.mapping.conversion;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.util.ArrayList;
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
 * @param sourceFormat the format converted from, e.g. {@code ebinterface-6.1}
 * @param targetFormat the format converted to, e.g. {@code ubl-invoice-2.1}
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

  /** A report with no notes at all — a conversion that carried everything. */
  public static ConversionReport lossless(String sourceFormat, String targetFormat) {
    return new ConversionReport(sourceFormat, targetFormat, List.of());
  }

  /** This report's notes followed by {@code more}, as one report over the same formats. */
  public ConversionReport plus(List<Finding> more) {
    List<Finding> combined = new ArrayList<>(notes);
    combined.addAll(more);
    return new ConversionReport(sourceFormat, targetFormat, combined);
  }
}
