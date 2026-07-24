package com.stoicera.einvoice.validation.stage;

import com.helger.schematron.svrl.SVRLFailedAssert;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.util.Map;

/**
 * The German/English finding texts for our own AT-B2G Schematron rules, keyed by the assert id the
 * {@code .sch} carries.
 *
 * <p>The Schematron file is the single source of the rule <em>logic</em>; this catalog is the
 * single source of the rule <em>messages</em>. Keeping them apart lets the assert text stay terse
 * German while the report speaks the project's bilingual finding contract — {@code messageDe} first
 * (project policy: every finding has a German message), then a genuine English translation. The
 * rule ids and these texts are a fixed contract other modules (corpus, CLI) depend on.
 *
 * <p>A failed assert whose id is <em>not</em> in the catalog is a programming error — a rule added
 * to the {@code .sch} without a matching entry here — never bad input. Such a finding must never be
 * dropped silently: {@link #toFinding(SVRLFailedAssert)} maps it to an {@link Severity#ERROR} with
 * the raw SVRL assert text in both languages and the id kept exactly as reported, so the gap shows
 * up in the report instead of vanishing.
 */
public final class SchematronRuleCatalog {

  /** Rule id: a B2G invoice must carry an Auftragsreferenz ({@code OrderReference/OrderID}). */
  public static final String RULE_AUFTRAGSREFERENZ = "AT-B2G-01";

  /** A finding's bilingual message pair: German (primary) and English (secondary). */
  private record BilingualMessage(String messageDe, String messageEn) {}

  private static final Map<String, BilingualMessage> CATALOG =
      Map.of(
          RULE_AUFTRAGSREFERENZ,
          new BilingualMessage(
              "Auftragsreferenz fehlt: Rechnungen an Bundesdienststellen müssen eine"
                  + " Auftragsreferenz (OrderReference/OrderID) enthalten.",
              "Order reference missing: invoices to Austrian federal bodies must carry an order"
                  + " reference (OrderReference/OrderID)."));

  private SchematronRuleCatalog() {}

  /**
   * Maps one failed Schematron assert to a {@link Finding}.
   *
   * <p>For a catalogued id the finding carries the catalog's own bilingual text (the raw SVRL text
   * is ignored); for an uncatalogued id it falls back to the raw SVRL assert text in both
   * languages, keeping the id as-is. Either way the SVRL location becomes the finding location and
   * the severity is {@link Severity#ERROR}: a failed {@code assert} is a hard rule violation.
   *
   * @param failedAssert the failed assert from the SVRL result
   * @return the mapped finding, never {@code null}
   */
  static Finding toFinding(SVRLFailedAssert failedAssert) {
    String ruleId = failedAssert.getID();
    String location = failedAssert.getLocation();
    BilingualMessage message = CATALOG.get(ruleId);
    if (message != null) {
      return Finding.of(Severity.ERROR, ruleId, location, message.messageDe(), message.messageEn());
    }
    // Uncatalogued id — never drop it; surface the raw assert text in both languages.
    String rawText = failedAssert.getText();
    return Finding.of(Severity.ERROR, ruleId, location, rawText, rawText);
  }
}
