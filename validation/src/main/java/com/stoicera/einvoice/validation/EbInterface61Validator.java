package com.stoicera.einvoice.validation;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.stage.BusinessRuleStage;
import com.stoicera.einvoice.validation.stage.FormatDetectionStage;
import com.stoicera.einvoice.validation.stage.SchematronStage;
import com.stoicera.einvoice.validation.stage.XsdValidationStage;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates an ebInterface 6.1 document against the Austrian B2G profile and reports the result as
 * a {@link ValidationReport}.
 *
 * <p>This facade never throws on bad input — malformed bytes, an oversized upload, an unknown
 * format or a wrong ebInterface version are the domain, and each becomes a finding. Foreign
 * parser/SVRL text (which can echo document content) is bounded before it reaches a finding, so no
 * document value can overflow a {@code Finding} cap and escape as an exception. The pipeline runs
 * in a fixed order and stops at the first stage that makes later stages meaningless: input-size
 * guard ({@code XML-02}; anything above {@value #MAX_INPUT_BYTES} bytes is rejected up front
 * without parsing) → secure DOM parse ({@code XML-01}) → format detection ({@code FORMAT-01}/{@code
 * FORMAT-02}) → XSD ({@code EBI61-XSD}; a structurally invalid document cannot be meaningfully
 * checked by Schematron or business rules) → our own AT-B2G Schematron ({@code AT-B2G-nn};
 * evaluated only on a schema-valid tree) → our hand-written AT-B2G business rules ({@code
 * AT-B2G-nn}; e.g. the IBAN mod-97 check, also on a schema-valid tree). Findings appear in the
 * report in this pipeline order.
 */
public final class EbInterface61Validator {

  /** The validation profile this validator applies: Austrian business-to-government. */
  public static final String PROFILE_AT_B2G = "at-b2g";

  /** Rule id: the upload is not well-formed XML. */
  public static final String RULE_MALFORMED_XML = "XML-01";

  /** Rule id: the upload exceeds the module's defensive input-size cap. */
  public static final String RULE_INPUT_TOO_LARGE = "XML-02";

  /**
   * Defensive input-size cap, in bytes (20 MB): a module-level guard so the validator defends
   * itself independently of any caller. Larger uploads are rejected as {@code XML-02} before a byte
   * is parsed, keeping the never-throws contract safe from out-of-memory on hostile input. This is
   * separate from — and looser than — the stricter 2 MB application-layer cap SPEC §4 places in
   * front of the HTTP endpoint in M3.
   */
  static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;

  /** Source-format marker for a document detected as ebInterface 6.1. */
  public static final String SOURCE_EBINTERFACE_61 = "ebinterface-6.1";

  /** Source-format marker for a document whose format could not be determined. */
  public static final String SOURCE_UNKNOWN = "unknown";

  private final FormatDetectionStage formatDetection = new FormatDetectionStage();
  private final XsdValidationStage xsdValidation = new XsdValidationStage();
  private final SchematronStage schematron = new SchematronStage();
  private final BusinessRuleStage businessRules = new BusinessRuleStage();

  /**
   * Validates {@code xml} and returns the report.
   *
   * @param xml the raw upload bytes; {@code null} is treated as empty input
   * @return the validation report, never {@code null}
   */
  public ValidationReport validate(byte[] xml) {
    byte[] input = xml == null ? new byte[0] : xml;

    // Stage -1 — defensive input-size guard. Reject an oversized upload before any parse so the
    // validator cannot be OOM'd by hostile input; this stop is terminal with a single XML-02.
    if (input.length > MAX_INPUT_BYTES) {
      return report(SOURCE_UNKNOWN, List.of(inputTooLargeFinding()));
    }

    ValidationContext ctx = new ValidationContext(input);

    // Stage 0 — secure DOM parse. Not well-formed XML stops the pipeline with a single XML-01.
    if (ctx.dom().isEmpty()) {
      return report(SOURCE_UNKNOWN, List.of(malformedXmlFinding()));
    }

    // Stage 1 — format detection. Any finding here (FORMAT-01 / FORMAT-02) is terminal: we cannot
    // validate a document whose format we do not recognise or do not support.
    List<Finding> formatFindings = formatDetection.apply(ctx);
    if (!formatFindings.isEmpty()) {
      return report(SOURCE_UNKNOWN, formatFindings);
    }

    // From here the document is ebInterface 6.1.
    List<Finding> findings = new ArrayList<>();

    // Stage 2 — XSD. A structurally invalid document cannot be meaningfully checked by the later
    // Schematron and business-rule stages, so XSD errors stop the pipeline.
    findings.addAll(xsdValidation.apply(ctx));
    ValidationReport afterXsd = report(SOURCE_EBINTERFACE_61, findings);
    if (!afterXsd.isValid()) {
      return afterXsd;
    }

    // Stage 3 — our own AT-B2G Schematron. It runs only now that the document is structurally
    // schema-valid, so the XPath rules evaluate against a well-formed 6.1 tree; its findings (e.g.
    // AT-B2G-01) are aggregated into the report alongside any (currently none) XSD warnings.
    findings.addAll(schematron.apply(ctx));

    // Stage 4 — hand-written AT-B2G business rules over the parsed 6.1 tree (e.g. AT-B2G-02, the
    // IBAN mod-97 check). Same gating as Schematron (a schema-valid tree), and it runs last, so its
    // findings follow the Schematron findings in report order.
    findings.addAll(businessRules.apply(ctx));

    return report(SOURCE_EBINTERFACE_61, findings);
  }

  private static Finding malformedXmlFinding() {
    return Finding.of(
        Severity.ERROR,
        RULE_MALFORMED_XML,
        null,
        "Die Datei ist kein wohlgeformtes XML und konnte nicht gelesen werden.",
        "The file is not well-formed XML and could not be parsed.");
  }

  private static Finding inputTooLargeFinding() {
    return Finding.of(
        Severity.ERROR,
        RULE_INPUT_TOO_LARGE,
        null,
        "Dokument überschreitet die maximale Größe von 20 MB.",
        "Document exceeds the maximum size of 20 MB.");
  }

  private static ValidationReport report(String sourceFormat, List<Finding> findings) {
    return new ValidationReport(sourceFormat, PROFILE_AT_B2G, findings);
  }
}
