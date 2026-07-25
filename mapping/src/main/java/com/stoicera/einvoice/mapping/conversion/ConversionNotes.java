package com.stoicera.einvoice.mapping.conversion;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;

/**
 * The vocabulary of a conversion report: what a conversion could not carry, and what it had to
 * reinterpret.
 *
 * <h2>Why these reuse {@code Finding}</h2>
 *
 * <p>A conversion note and a validation finding are different things — one says "this document does
 * not comply", the other says "this value did not survive the trip" — but they have identical
 * structure and identical requirements: a severity, a stable id, a location, and a German-first
 * message pair (CLAUDE.md). Introducing a second record with the same five fields, the same
 * invariants and a second JSON contract for the API to serialise would be duplication for the sake
 * of a name. So notes are {@link Finding}s carrying {@code CONV-nn} ids, and the {@link
 * ConversionReport} that holds them is what makes their meaning unambiguous.
 *
 * <h2>Severity means something specific here</h2>
 *
 * <ul>
 *   <li>{@link Severity#WARN} — data was <strong>lost</strong>. The target format has nowhere to
 *       put it, so a value the source carried is not in the output. The conversion still produced a
 *       usable document.
 *   <li>{@link Severity#INFO} — data was <strong>reinterpreted</strong> but not lost: a national
 *       convention translated, a value moved to a different element than the obvious one.
 *   <li>{@link Severity#ERROR} — the conversion produced a document that cannot be trusted to mean
 *       the same thing, most importantly when the source's own stated totals disagree with what the
 *       canonical model derives from its lines. Never silently accepted, because a converted
 *       invoice whose totals changed is worse than no conversion at all.
 * </ul>
 */
public final class ConversionNotes {

  /** A value the source carried has no representation in the target format and was dropped. */
  public static final String CONV_01 = "CONV-01";

  /** A national or format convention was translated into or out of the canonical model. */
  public static final String CONV_02 = "CONV-02";

  /** A value survived, but in a different element than the target format's obvious one. */
  public static final String CONV_03 = "CONV-03";

  /**
   * The source document's own stated total disagrees with the total the canonical model derives
   * from its lines (ADR-0003, derive-don't-trust). The canonical value wins — that is the whole
   * point of the model — and the disagreement is reported rather than buried.
   */
  public static final String CONV_04 = "CONV-04";

  private ConversionNotes() {}

  /** A note that a value was dropped because the target format has nowhere to carry it. */
  public static Finding lost(String location, String messageDe, String messageEn) {
    return Finding.of(Severity.WARN, CONV_01, location, messageDe, messageEn);
  }

  /** A note that a format or national convention was translated. */
  public static Finding convention(String location, String messageDe, String messageEn) {
    return Finding.of(Severity.INFO, CONV_02, location, messageDe, messageEn);
  }

  /** A note that a value moved to a non-obvious element in the target. */
  public static Finding relocated(String location, String messageDe, String messageEn) {
    return Finding.of(Severity.INFO, CONV_03, location, messageDe, messageEn);
  }

  /** A note that the source's stated totals disagree with the derived ones. */
  public static Finding derivedTotalMismatch(String location, String messageDe, String messageEn) {
    return Finding.of(Severity.ERROR, CONV_04, location, messageDe, messageEn);
  }
}
