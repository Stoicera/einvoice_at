package com.stoicera.einvoice.validation;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.stage.FormatDetectionStage;
import com.stoicera.einvoice.validation.stage.SchematronStage;
import com.stoicera.einvoice.validation.stage.XsdValidationStage;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates an ebInterface 6.1 document against the Austrian B2G profile and reports the result as
 * a {@link ValidationReport}.
 *
 * <p>This facade never throws on bad input — malformed bytes, an unknown format or a wrong
 * ebInterface version are the domain, and each becomes a finding. The pipeline runs in a fixed
 * order and stops at the first stage that makes later stages meaningless: secure DOM parse ({@code
 * XML-01}) → format detection ({@code FORMAT-01}/{@code FORMAT-02}) → XSD ({@code EBI61-XSD}; a
 * structurally invalid document cannot be meaningfully checked by Schematron or business rules) →
 * our own AT-B2G Schematron ({@code AT-B2G-nn}; evaluated only on a schema-valid tree).
 */
public final class EbInterface61Validator {

  /** The validation profile this validator applies: Austrian business-to-government. */
  public static final String PROFILE_AT_B2G = "at-b2g";

  /** Rule id: the upload is not well-formed XML. */
  public static final String RULE_MALFORMED_XML = "XML-01";

  /** Source-format marker for a document detected as ebInterface 6.1. */
  public static final String SOURCE_EBINTERFACE_61 = "ebinterface-6.1";

  /** Source-format marker for a document whose format could not be determined. */
  public static final String SOURCE_UNKNOWN = "unknown";

  private final FormatDetectionStage formatDetection = new FormatDetectionStage();
  private final XsdValidationStage xsdValidation = new XsdValidationStage();
  private final SchematronStage schematron = new SchematronStage();

  /**
   * Validates {@code xml} and returns the report.
   *
   * @param xml the raw upload bytes; {@code null} is treated as empty input
   * @return the validation report, never {@code null}
   */
  public ValidationReport validate(byte[] xml) {
    ValidationContext ctx = new ValidationContext(xml == null ? new byte[0] : xml);

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

  private static ValidationReport report(String sourceFormat, List<Finding> findings) {
    return new ValidationReport(sourceFormat, PROFILE_AT_B2G, findings);
  }
}
