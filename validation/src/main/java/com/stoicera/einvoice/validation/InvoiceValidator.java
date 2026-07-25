package com.stoicera.einvoice.validation;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.stage.BusinessRuleStage;
import com.stoicera.einvoice.validation.stage.FormatDetectionStage;
import com.stoicera.einvoice.validation.stage.PeppolValidationStage;
import com.stoicera.einvoice.validation.stage.SchematronStage;
import com.stoicera.einvoice.validation.stage.XsdValidationStage;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates an uploaded invoice document — ebInterface 6.1 or Peppol BIS Billing 3.0 UBL — and
 * reports the result as a {@link ValidationReport}.
 *
 * <p>This facade never throws on bad input: malformed bytes, an oversized upload, an unknown format
 * or a wrong ebInterface version are the domain, and each becomes a finding. Foreign parser/SVRL
 * text (which can echo document content) is bounded before it reaches a finding, so no document
 * value can overflow a {@code Finding} cap and escape as an exception.
 *
 * <h2>One entry point, two pipelines</h2>
 *
 * <p>Everything up to and including format detection is shared; after that the two formats are
 * validated by genuinely different machinery, because the standards are in genuinely different
 * shape:
 *
 * <ul>
 *   <li><strong>ebInterface 6.1</strong> — XSD ({@code XSD-01}) → this project's own AT-B2G
 *       Schematron ({@code AT-B2G-01/03/04/05}) → hand-written business rules ({@code AT-B2G-02}).
 *       Three stages, because AUSTRIAPRO publishes no Schematron and the profile rules had to be
 *       written here (ADR-0004).
 *   <li><strong>UBL</strong> — one stage running the official OpenPeppol rule set unmodified, at a
 *       pinned version. That single VES already contains XSD, the EN 16931 rules and the Peppol BIS
 *       rules, so splitting it up would mean taking the rule set apart, which is exactly what
 *       consuming it as published means not doing.
 * </ul>
 *
 * <p>Gating differs accordingly. The ebInterface pipeline stops after an XSD failure, because its
 * later stages assume a schema-valid tree; the Peppol VES sequences its own stages internally and
 * needs no help.
 *
 * <p>This class replaced M2's {@code EbInterface61Validator}, which was deleted in M4 rather than
 * kept alongside: two facades where one dispatches to the other's pipeline is a fork waiting to
 * happen, and the M2 corpus and unit tests were pointed here instead. (Until the M4 hostile review
 * this paragraph said the class "supersedes {@code InvoiceValidator}, which remains" — naming
 * itself, and promising the continued existence of a class the same milestone had removed.)
 */
public final class InvoiceValidator {

  /** The validation profile applied to an ebInterface document: Austrian business-to-government. */
  public static final String PROFILE_AT_B2G = "at-b2g";

  /** The validation profile applied to a UBL document: Peppol BIS Billing 3.0. */
  public static final String PROFILE_PEPPOL_BIS_3 = "peppol-bis-billing-3.0";

  /** Profile reported when the document's format could not be determined at all. */
  public static final String PROFILE_NONE = "none";

  /**
   * Defensive input-size cap, in bytes (20 MB): a module-level guard so the validator defends
   * itself independently of any caller. Larger uploads are rejected as {@code XML-02} before a byte
   * is parsed, keeping the never-throws contract safe from out-of-memory on hostile input. This is
   * separate from — and looser than — the stricter 2 MB application-layer cap SPEC §4 places in
   * front of the HTTP endpoint.
   */
  static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;

  private final FormatDetectionStage formatDetection = new FormatDetectionStage();
  private final XsdValidationStage xsdValidation = new XsdValidationStage();
  private final SchematronStage schematron = new SchematronStage();
  private final BusinessRuleStage businessRules = new BusinessRuleStage();
  private final PeppolValidationStage peppol = new PeppolValidationStage();

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
      return report(DocumentFormat.UNKNOWN, PROFILE_NONE, List.of(inputTooLargeFinding()));
    }

    ValidationContext ctx = new ValidationContext(input);

    // Stage 0 — secure DOM parse. Not well-formed XML stops the pipeline with a single XML-01.
    if (ctx.dom().isEmpty()) {
      return report(DocumentFormat.UNKNOWN, PROFILE_NONE, List.of(malformedXmlFinding()));
    }

    // Stage 1 — format detection. Any finding here (FORMAT-01 / FORMAT-02) is terminal: we cannot
    // validate a document whose format we do not recognise or do not support.
    List<Finding> formatFindings = formatDetection.apply(ctx);
    if (!formatFindings.isEmpty()) {
      return report(DocumentFormat.UNKNOWN, PROFILE_NONE, formatFindings);
    }

    return ctx.format().isUbl() ? validateUbl(ctx) : validateEbInterface(ctx);
  }

  /**
   * Identifies {@code xml}'s format without validating it.
   *
   * <p>Conversion needs the answer to "what is this?" separately from "is this valid?": a caller
   * declaring {@code from=ebinterface} while uploading UBL should be told exactly that, rather than
   * getting a confusing parse failure from deep inside a mapper. The two questions share this
   * module's parse and detection stages, so exposing the first one here keeps a second, drifting
   * copy of namespace detection from appearing in the application layer.
   *
   * @param xml the raw upload bytes; {@code null} is treated as empty input
   * @return the detected format, or {@link DocumentFormat#UNKNOWN} for anything unrecognised —
   *     including bytes that are not well-formed XML at all
   */
  public DocumentFormat detectFormat(byte[] xml) {
    byte[] input = xml == null ? new byte[0] : xml;
    if (input.length > MAX_INPUT_BYTES) {
      return DocumentFormat.UNKNOWN;
    }
    ValidationContext ctx = new ValidationContext(input);
    if (ctx.dom().isEmpty()) {
      return DocumentFormat.UNKNOWN;
    }
    formatDetection.apply(ctx);
    return ctx.format();
  }

  /**
   * The official OpenPeppol rule set, run whole. It sequences XSD and its two Schematron layers
   * internally, so there is nothing for this method to gate.
   */
  private ValidationReport validateUbl(ValidationContext ctx) {
    return report(ctx.format(), PROFILE_PEPPOL_BIS_3, peppol.apply(ctx));
  }

  private ValidationReport validateEbInterface(ValidationContext ctx) {
    List<Finding> findings = new ArrayList<>();

    // Stage 2 — XSD. A structurally invalid document cannot be meaningfully checked by the later
    // Schematron and business-rule stages, so XSD errors stop the pipeline.
    findings.addAll(xsdValidation.apply(ctx));
    ValidationReport afterXsd = report(ctx.format(), PROFILE_AT_B2G, findings);
    if (!afterXsd.isValid()) {
      return afterXsd;
    }

    // Stage 3 — our own AT-B2G Schematron, evaluated only on a schema-valid tree.
    findings.addAll(schematron.apply(ctx));

    // Stage 4 — hand-written AT-B2G business rules (e.g. AT-B2G-02, the IBAN mod-97 check). Same
    // gating, and last, so its findings follow the Schematron findings in report order.
    findings.addAll(businessRules.apply(ctx));

    return report(ctx.format(), PROFILE_AT_B2G, findings);
  }

  private static Finding malformedXmlFinding() {
    return Finding.of(
        Severity.ERROR,
        RuleIds.XML_01,
        null,
        "Die Datei ist kein wohlgeformtes XML und konnte nicht gelesen werden.",
        "The file is not well-formed XML and could not be parsed.");
  }

  private static Finding inputTooLargeFinding() {
    return Finding.of(
        Severity.ERROR,
        RuleIds.XML_02,
        null,
        "Dokument überschreitet die maximale Größe von 20 MB.",
        "Document exceeds the maximum size of 20 MB.");
  }

  private static ValidationReport report(
      DocumentFormat format, String profile, List<Finding> findings) {
    return new ValidationReport(format.sourceFormat(), profile, findings);
  }
}
