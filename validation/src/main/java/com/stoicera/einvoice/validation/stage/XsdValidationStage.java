package com.stoicera.einvoice.validation.stage;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.helger.diagnostics.error.level.IErrorLevel;
import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResult;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.api.validity.IValidityDeterminator;
import com.helger.phive.ebinterface.EbInterfaceValidation;
import com.helger.phive.xml.source.IValidationSourceXML;
import com.helger.phive.xml.source.ValidationSourceXML;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.ValidationStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Document;

/**
 * Validates the DOM against the bundled ebInterface 6.1 XSD via phive.
 *
 * <p>The phive validation-executor-set registry is expensive to build, so it is initialised exactly
 * once in a lazy holder and shared across runs. Each schema violation becomes an {@code EBI61-XSD}
 * finding: the technical detail is kept exactly as the underlying Xerces parser delivers it, behind
 * a German lead-in that is always ours — see ADR-0004.
 *
 * <p>Xerces bakes its diagnostic text into the {@code SAXParseException} message at the moment the
 * document is validated, using whichever {@link Locale} the validation run was asked for; phive
 * then wraps that already-rendered string in a locale-independent {@link IError}, so calling {@link
 * IError#getErrorText(Locale)} with a different locale afterwards does <em>not</em> re-render the
 * text — it returns the same string regardless of the argument. A genuinely bilingual finding
 * therefore requires validating the document twice, once per {@link Locale}, and pairing up the two
 * resulting error lists by position: {@link #apply(ValidationContext)} runs the XSD executor with
 * {@link Locale#GERMAN} for the {@code messageDe} tail and again with {@link Locale#ENGLISH} for
 * {@code messageEn}. The two runs validate the identical DOM against the identical schema, so they
 * report the same violations in the same order — only the message text differs. The location phive
 * attaches to a DOM-sourced error is the source name ({@code upload.xml}) rather than a
 * line/column, because a DOM carries no positional information.
 */
public final class XsdValidationStage implements ValidationStage {

  /** Rule id: the document violates the ebInterface 6.1 XML schema. */
  public static final String RULE_XSD = "EBI61-XSD";

  private static final String GERMAN_LEAD_IN = "Das Dokument verletzt das ebInterface-6.1-Schema: ";

  /** Last-resort detail when the parser hands back no usable message text in either locale. */
  private static final String FALLBACK_DETAIL = "Unbekannter Schemafehler (unknown schema error)";

  @Override
  public List<Finding> apply(ValidationContext ctx) {
    Document dom = ctx.dom().orElseThrow(); // the facade runs this stage only with a parsed DOM

    IValidationExecutorSet<IValidationSourceXML> ves =
        RegistryHolder.REGISTRY.getOfID(EbInterfaceValidation.VID_EBI_61);
    ValidationExecutionManager<IValidationSourceXML> manager =
        new ValidationExecutionManager<>(
            IValidityDeterminator.createDefault(), ves.getAllExecutors());

    // Two full runs are required: see the class Javadoc for why a single fetch cannot yield both
    // locales. Both runs validate the same DOM against the same schema, so their error lists line
    // up position-for-position; only the rendered message text differs between them.
    List<IError> germanErrors = validate(dom, manager, Locale.GERMAN);
    List<IError> englishErrors = validate(dom, manager, Locale.ENGLISH);

    List<Finding> findings = new ArrayList<>();
    for (int i = 0; i < germanErrors.size(); i++) {
      IError englishError = i < englishErrors.size() ? englishErrors.get(i) : null;
      findings.add(toFinding(germanErrors.get(i), englishError));
    }
    return findings;
  }

  private static List<IError> validate(
      Document dom, ValidationExecutionManager<IValidationSourceXML> manager, Locale locale) {
    ValidationSourceXML source = ValidationSourceXML.create("upload.xml", dom);
    ValidationResultList results = new ValidationResultList(source);
    manager.executeValidation(source, results, locale);

    List<IError> errors = new ArrayList<>();
    for (ValidationResult result : results) {
      errors.addAll(result.getErrorList());
    }
    return errors;
  }

  /**
   * Builds one finding from a German/English error pair produced by the two locale runs in {@link
   * #apply(ValidationContext)}.
   *
   * @param germanError the error from the {@link Locale#GERMAN} run; drives severity and location
   * @param englishError the positionally-matching error from the {@link Locale#ENGLISH} run, or
   *     {@code null} if the two runs disagreed on error count — a defensive case that should not
   *     occur in practice (see class Javadoc), handled by falling back to the German detail so the
   *     {@code Finding} invariants still hold rather than throwing
   */
  static Finding toFinding(IError germanError, IError englishError) {
    Severity severity = severityOf(germanError.getErrorLevel());
    String germanDetail = detailText(germanError);
    String englishDetail = englishError == null ? germanDetail : englishDetailText(englishError);
    String location = germanError.getErrorLocation().getAsString();
    return Finding.of(severity, RULE_XSD, location, GERMAN_LEAD_IN + germanDetail, englishDetail);
  }

  /**
   * Maps a phive error level to our {@link Severity}: error-and-above ({@code ERROR}, {@code
   * FATAL_ERROR}) is an {@link Severity#ERROR}, anything less is a {@link Severity#WARN}. The
   * ebInterface 6.1 VES is XSD-only, so in practice every level seen here is {@code ERROR}.
   */
  static Severity severityOf(IErrorLevel level) {
    return level.isGE(EErrorLevel.ERROR) ? Severity.ERROR : Severity.WARN;
  }

  /**
   * The parser's own message from a {@link Locale#GERMAN}-run error, or a fixed fallback when it
   * hands back nothing usable.
   */
  static String detailText(IError error) {
    String text = error.getErrorText(Locale.GERMAN);
    return text == null || text.isBlank() ? FALLBACK_DETAIL : text;
  }

  /**
   * The parser's own message from a {@link Locale#ENGLISH}-run error. Falls back to {@link
   * IError#getAsStringLocaleIndepdent()} (helger's real, misspelled method name) rather than the
   * German text when the English fetch is missing or blank — that keeps the fallback honestly
   * non-German instead of silently reintroducing the bug this method exists to fix — and finally to
   * the fixed bilingual {@link #FALLBACK_DETAIL} if even that is unusable.
   */
  static String englishDetailText(IError error) {
    String text = error.getErrorText(Locale.ENGLISH);
    if (text != null && !text.isBlank()) {
      return text;
    }
    String localeIndependent = error.getAsStringLocaleIndepdent();
    return localeIndependent == null || localeIndependent.isBlank()
        ? FALLBACK_DETAIL
        : localeIndependent;
  }

  /**
   * Lazy, thread-safe holder for the phive registry: the JVM initialises it on first access and the
   * class-init lock makes publication safe. Building it eagerly at class load would pay the cost
   * even when no ebInterface document is ever validated.
   */
  private static final class RegistryHolder {

    private static final ValidationExecutorSetRegistry<IValidationSourceXML> REGISTRY =
        createRegistry();

    private static ValidationExecutorSetRegistry<IValidationSourceXML> createRegistry() {
      ValidationExecutorSetRegistry<IValidationSourceXML> registry =
          new ValidationExecutorSetRegistry<>();
      EbInterfaceValidation.initEbInterface(registry);
      return registry;
    }
  }
}
