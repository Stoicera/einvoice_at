package com.stoicera.einvoice.validation.stage;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.helger.diver.api.coord.DVRCoordinate;
import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResult;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.api.validity.IValidityDeterminator;
import com.helger.phive.peppol.PeppolValidation;
import com.helger.phive.peppol.PeppolValidation2026_05;
import com.helger.phive.xml.source.IValidationSourceXML;
import com.helger.phive.xml.source.ValidationSourceXML;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.DocumentFormat;
import com.stoicera.einvoice.validation.RuleIds;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.ValidationStage;
import com.stoicera.einvoice.validation.internal.BoundedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Document;

/**
 * Validates a UBL document against the <strong>official OpenPeppol rule set</strong> for Peppol BIS
 * Billing 3.0, executed unmodified through phive.
 *
 * <p>This is the promise SPEC §7 recorded when M2 had to write its own ebInterface Schematron:
 * ebInterface has no official Schematron to consume, Peppol does, and here it is consumed as
 * published rather than reimplemented. One VES covers XSD <em>and</em> the EN 16931 and Peppol BIS
 * Schematron layers, which is why this single stage replaces what the ebInterface pipeline splits
 * across three.
 *
 * <h2>Why the rule-set version is pinned</h2>
 *
 * <p>{@link PeppolValidation#initStandard} registers every Peppol rule-set version the library
 * ships — at the pinned phive-rules 4.4.1 that is 2025.5, 2025.11, 2026.3 and 2026.5 — so choosing
 * one is the caller's job, not the library's. This stage names {@link #RULE_SET_VERSION} explicitly
 * (SPEC §10: "Schematron rule sets evolve → rule-set versions pinned + documented"). Picking
 * "whatever the library considers current" would silently change which rules an invoice is judged
 * by on a dependency bump, and the same document would start or stop being valid without a single
 * line of this repository changing.
 *
 * <p><strong>{@value #RULE_SET_VERSION} becomes mandatory on 2026-08-17</strong> and was adopted
 * here on 2026-08-07, ahead of that date — read off the artefact itself ({@code
 * PeppolValidation2026_05.VALID_PER}), not off a website. The next deadline is not left to a
 * calendar reminder: {@code PeppolValidationStageTest.noNewerRuleSetIsAlreadyMandatory} enumerates
 * the dated rule sets phive-rules publishes and fails the build on the day one of them supersedes
 * this pin. Upgrading is a deliberate, dated edit of the constants here plus a corpus re-run; the
 * update procedure lives in ADR-0007.
 *
 * <p>{@link #isPinnedRuleSetRegistered()} exists so a test can fail loudly if a future phive-rules
 * version drops the pinned set, rather than the pin quietly resolving to nothing.
 *
 * <h2>Findings</h2>
 *
 * <p>Each Schematron assertion carries its own identifier ({@code BR-01}, {@code PEPPOL-EN16931-
 * R001}, {@code UBL-CR-412}, …), and that identifier is used as the finding's rule id rather than
 * one flat project-local code. A caller looking up why an invoice was rejected can then search the
 * official rule documentation directly. {@link RuleIds#PEPPOL_01} is the fallback for the rare
 * diagnostic phive reports without an assertion id (an XSD violation, for instance).
 */
public final class PeppolValidationStage implements ValidationStage {

  /**
   * The pinned OpenPeppol rule-set version — see the class Javadoc for why this is a constant and
   * not a library default.
   */
  public static final String RULE_SET_VERSION = PeppolValidation2026_05.VERSION_STR;

  private static final String GERMAN_LEAD_IN = "Peppol BIS Billing 3.0: ";

  /** Last-resort detail when the rule set hands back no usable message text. */
  private static final String FALLBACK_DETAIL =
      "Unbekannter Peppol-Prüffehler (unknown Peppol validation error)";

  @Override
  public List<Finding> apply(ValidationContext ctx) {
    Document dom = ctx.dom().orElseThrow(); // the facade runs this stage only with a parsed DOM
    DVRCoordinate ves = vesFor(ctx.format());

    IValidationExecutorSet<IValidationSourceXML> executorSet = RegistryHolder.REGISTRY.getOfID(ves);
    if (executorSet == null) {
      // The pin no longer resolves, i.e. a phive-rules upgrade dropped this rule-set version. That
      // is a packaging defect, not untrusted input, and must not masquerade as "no findings".
      throw new IllegalStateException(
          "Pinned Peppol validation executor set " + ves.getAsSingleID() + " is not registered");
    }

    ValidationSourceXML source = ValidationSourceXML.create("upload.xml", dom);
    ValidationResultList results = new ValidationResultList(source);
    new ValidationExecutionManager<>(
            IValidityDeterminator.createDefault(), executorSet.getAllExecutors())
        .executeValidation(source, results, Locale.ENGLISH);

    List<Finding> findings = new ArrayList<>();
    for (ValidationResult result : results) {
      for (IError error : result.getErrorList()) {
        findings.add(toFinding(error));
      }
    }
    return findings;
  }

  /**
   * The pinned VES for a detected UBL document kind. Invoice and credit note are separate rule sets
   * in Peppol, not one rule set applied to two root elements.
   */
  private static DVRCoordinate vesFor(DocumentFormat format) {
    return switch (format) {
      case UBL_INVOICE -> PeppolValidation2026_05.VID_OPENPEPPOL_INVOICE_UBL_V3;
      case UBL_CREDIT_NOTE -> PeppolValidation2026_05.VID_OPENPEPPOL_CREDIT_NOTE_UBL_V3;
      default ->
          throw new IllegalArgumentException(
              "Peppol validation applies to UBL documents only, not " + format);
    };
  }

  /**
   * Turns one phive diagnostic into a {@link Finding}, keyed on the Schematron assertion id.
   *
   * <p>The message text is bounded before it reaches {@code Finding}: a Schematron message echoes
   * document values verbatim, which can exceed {@code Finding}'s own caps and make its constructor
   * throw — the same reachable-crash class the M2 hostile review closed for the XSD stage.
   *
   * <h3>The German half (M5)</h3>
   *
   * <p>Unlike the XSD stage, which can re-run Xerces under a German locale, the OpenPeppol
   * Schematron ships English message text only. M4 therefore made {@code messageDe} a German
   * lead-in around the official English wording and named translating "the rules that actually
   * matter to Austrian filers" as deliberate later work. That work is {@link PeppolMessagesDe}:
   * when the assertion id is catalogued, the German half is the lead-in plus a translation of the
   * published assertion text; when it is not, the M4 behaviour stands unchanged — a German frame
   * around English, which is a worse message but never a wrong one.
   *
   * <p><strong>{@code messageEn} is always the rule set's own text, translated or not.</strong>
   * This project executes the OpenPeppol rules unmodified (ADR-0007) and that principle extends to
   * their wording: the translation is an addition for the reader, never a replacement of the
   * official record.
   */
  static Finding toFinding(IError error) {
    String assertionId = error.getErrorID();
    String ruleId =
        assertionId == null || assertionId.isBlank()
            ? RuleIds.PEPPOL_01
            : BoundedText.cap(assertionId, BoundedText.MAX_RULE_ID);

    String text = error.getErrorText(Locale.ENGLISH);
    String detail =
        BoundedText.cap(
            text == null || text.isBlank() ? FALLBACK_DETAIL : text,
            BoundedText.MAX_MESSAGE_DETAIL);
    String location =
        BoundedText.cap(error.getErrorLocation().getAsString(), BoundedText.MAX_LOCATION);

    Severity severity =
        error.getErrorLevel().isGE(EErrorLevel.ERROR) ? Severity.ERROR : Severity.WARN;
    // The translation is looked up by the rule set's OWN assertion id, and bounded like every other
    // foreign-text seam even though this text is ours — see boundedGerman.
    String germanDetail =
        PeppolMessagesDe.forRule(ruleId).map(PeppolValidationStage::boundedGerman).orElse(detail);
    return Finding.of(severity, ruleId, location, GERMAN_LEAD_IN + germanDetail, detail);
  }

  /**
   * Caps a catalogued German translation to the same bound every foreign-text seam uses.
   *
   * <p>These strings are this project's own, so the bound is not about untrusted input — it is
   * about {@code validate()}'s never-throws contract. {@code Finding} rejects a message over 4096
   * characters by throwing from its constructor, and this method runs inside {@code toFinding},
   * which runs inside {@code validate}. A future entry that someone extended past the cap would
   * therefore not produce a long message: it would produce a crashed validation, for every document
   * that trips that rule.
   *
   * <p>A named method rather than a lambda so a test can call it with a string no real entry has,
   * and assert the truncation rather than assert that a lookup returned something (M5 hostile
   * review, F9 — the test that claimed to cover this did neither).
   */
  static String boundedGerman(String translation) {
    return BoundedText.cap(translation, BoundedText.MAX_MESSAGE_DETAIL);
  }

  /**
   * Whether the pinned rule set is actually registered by the bundled phive-rules artefact.
   * Exercised by the stage test, so a dependency bump that drops {@value #RULE_SET_VERSION} fails a
   * fast, obvious assertion instead of surfacing as a mysterious runtime failure on the first
   * upload.
   */
  static boolean isPinnedRuleSetRegistered() {
    return RegistryHolder.REGISTRY.getOfID(PeppolValidation2026_05.VID_OPENPEPPOL_INVOICE_UBL_V3)
            != null
        && RegistryHolder.REGISTRY.getOfID(
                PeppolValidation2026_05.VID_OPENPEPPOL_CREDIT_NOTE_UBL_V3)
            != null;
  }

  /**
   * Lazy, thread-safe holder for the phive registry, mirroring the XSD stage's: the JVM initialises
   * it on first access and the class-init lock makes publication safe. Building it eagerly at class
   * load would pay the (substantial — the Peppol XSLTs are large) cost even when no UBL document is
   * ever validated.
   */
  private static final class RegistryHolder {

    private static final ValidationExecutorSetRegistry<IValidationSourceXML> REGISTRY =
        createRegistry();

    private static ValidationExecutorSetRegistry<IValidationSourceXML> createRegistry() {
      ValidationExecutorSetRegistry<IValidationSourceXML> registry =
          new ValidationExecutorSetRegistry<>();
      PeppolValidation.initStandard(registry);
      return registry;
    }
  }
}
