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
 * finding: the technical detail is kept exactly as the underlying Xerces parser delivers it (which,
 * asked in {@link Locale#GERMAN}, is German for the built-in messages but may fall back to English
 * for anything not in the parser's message bundle) behind a German lead-in that is always ours —
 * see ADR-0004. The location phive attaches to a DOM-sourced error is the source name ({@code
 * upload.xml}) rather than a line/column, because a DOM carries no positional information.
 */
public final class XsdValidationStage implements ValidationStage {

  /** Rule id: the document violates the ebInterface 6.1 XML schema. */
  public static final String RULE_XSD = "EBI61-XSD";

  private static final String GERMAN_LEAD_IN = "Das Dokument verletzt das ebInterface-6.1-Schema: ";

  /** Last-resort detail when the parser hands back no usable message text. */
  private static final String FALLBACK_DETAIL = "Unbekannter Schemafehler (unknown schema error)";

  @Override
  public List<Finding> apply(ValidationContext ctx) {
    Document dom = ctx.dom().orElseThrow(); // the facade runs this stage only with a parsed DOM

    IValidationExecutorSet<IValidationSourceXML> ves =
        RegistryHolder.REGISTRY.getOfID(EbInterfaceValidation.VID_EBI_61);
    ValidationSourceXML source = ValidationSourceXML.create("upload.xml", dom);
    ValidationExecutionManager<IValidationSourceXML> manager =
        new ValidationExecutionManager<>(
            IValidityDeterminator.createDefault(), ves.getAllExecutors());

    ValidationResultList results = new ValidationResultList(source);
    manager.executeValidation(source, results, Locale.GERMAN);

    List<Finding> findings = new ArrayList<>();
    for (ValidationResult result : results) {
      for (IError error : result.getErrorList()) {
        findings.add(toFinding(error));
      }
    }
    return findings;
  }

  static Finding toFinding(IError error) {
    Severity severity = severityOf(error.getErrorLevel());
    String detail = detailText(error);
    String location = error.getErrorLocation().getAsString();
    return Finding.of(severity, RULE_XSD, location, GERMAN_LEAD_IN + detail, detail);
  }

  /**
   * Maps a phive error level to our {@link Severity}: error-and-above ({@code ERROR}, {@code
   * FATAL_ERROR}) is an {@link Severity#ERROR}, anything less is a {@link Severity#WARN}. The
   * ebInterface 6.1 VES is XSD-only, so in practice every level seen here is {@code ERROR}.
   */
  static Severity severityOf(IErrorLevel level) {
    return level.isGE(EErrorLevel.ERROR) ? Severity.ERROR : Severity.WARN;
  }

  /** The parser's own message, or a fixed fallback when it hands back nothing usable. */
  static String detailText(IError error) {
    String text = error.getErrorText(Locale.GERMAN);
    return text == null || text.isBlank() ? FALLBACK_DETAIL : text;
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
