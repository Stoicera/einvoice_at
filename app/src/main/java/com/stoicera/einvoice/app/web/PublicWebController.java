package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.aiassist.ExplanationContext;
import com.stoicera.einvoice.app.ai.ExplanationService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.report.ValidateResult;
import com.stoicera.einvoice.app.security.CurrentTenant;
import com.stoicera.einvoice.core.text.Texts;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.repair.RepairInput;
import com.stoicera.einvoice.validation.repair.RepairProposer;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * The public pages: the landing page and the "Österreichischer E-Rechnungs-Prüfer" (PRD §2's lead
 * magnet).
 *
 * <h2>The upload goes through the same service as the API</h2>
 *
 * <p>{@link #pruefen} calls {@code ReportService.validate(bytes, Optional.empty())} for an
 * anonymous visitor — the <em>same</em> method and the <em>same</em> empty {@code Optional} the
 * anonymous REST endpoint uses, where empty means "write nothing" (ADR-0006). A UI-specific
 * validation path would have been easy and would have created a second place for the "an upload is
 * never stored" promise to break later. An authenticated visitor's report is persisted and audited,
 * exactly as through the API.
 *
 * <p>The upload is bounded by the same 2 MB multipart cap, and rate-limited by the same filter and
 * the same bucket as the API endpoint — otherwise this page would be an unlimited detour around a
 * limited one ({@code RateLimitFilter}).
 *
 * <h2>Why "Erklären" posts the finding back</h2>
 *
 * <p>{@link #erklaeren} takes the finding's fields as form parameters instead of a report id,
 * because for an anonymous visitor <strong>there is no report id</strong>: nothing was stored. The
 * alternative would be keeping the report in the session, which is retention by another name on the
 * one page whose promise is that nothing is retained. The consequence is honest and worth stating:
 * the explained text comes from the client, so a visitor can have any text explained. That costs an
 * LLM call, which is why this route is rate-limited too, and it cannot reach any other visitor's
 * data — there is none to reach.
 *
 * <h2>"Korrekturvorschlag mit Nachweis"</h2>
 *
 * <p>Every report also gets a correction proposal when a finding has a known fix ({@link
 * RepairProposer}): what changes, where each value comes from, the diff, and the re-validation of
 * the corrected document by the same validator. No AI is involved, so it works with the AI switch
 * off. When a value can only come from the user (the Auftragsreferenz), {@link #korrigieren} takes
 * it together with the document, which comes back from the browser for the reason {@link
 * RepairView} gives; it runs under the validate rate limit and persists nothing for any caller.
 */
@Controller
public class PublicWebController {

  private static final Logger log = LoggerFactory.getLogger(PublicWebController.class);

  /** Longest file name echoed back into the report; anything longer is cut, never rejected. */
  private static final int MAX_FILENAME_LENGTH = 255;

  private final ReportService reports;
  private final CurrentTenant currentTenant;
  private final ExplanationService explanations;
  private final RepairProposer repairs;

  public PublicWebController(
      ReportService reports,
      CurrentTenant currentTenant,
      ExplanationService explanations,
      RepairProposer repairs) {
    this.reports = reports;
    this.currentTenant = currentTenant;
    this.explanations = explanations;
    this.repairs = repairs;
  }

  @GetMapping("/")
  public String landing(Model model) {
    model.addAttribute("aiEnabled", explanations.isEnabled());
    return "index";
  }

  @GetMapping("/validator")
  public String validator(Model model) {
    model.addAttribute("aiEnabled", explanations.isEnabled());
    return "validator";
  }

  /**
   * Validates an uploaded document and renders the report fragment.
   *
   * <p>Returns a <em>fragment</em>, so the first-party progressive-enhancement script can swap it
   * into the page; without JavaScript the browser posts normally and gets the same fragment as a
   * full response, which is why the fragment template carries its own heading and is readable
   * standalone.
   */
  @PostMapping("/validator/pruefen")
  public String pruefen(
      @RequestParam(name = "file", required = false) MultipartFile file,
      Authentication authentication,
      Model model)
      throws IOException {
    if (file == null || file.isEmpty()) {
      model.addAttribute("uploadError", "Bitte wählen Sie eine XML-Datei aus.");
      model.addAttribute("aiEnabled", explanations.isEnabled());
      return "fragments/report :: report";
    }

    Optional<UUID> tenantId =
        currentTenant.resolveIfAuthenticated(authentication).map(TenantEntity::getId);
    byte[] bytes = file.getBytes();
    ValidateResult result = reports.validate(bytes, tenantId);

    model.addAttribute("filename", shortened(file.getOriginalFilename()));
    model.addAttribute("aiEnabled", explanations.isEnabled());
    addReport(model, result.report());
    addRepair(model, bytes, result.report(), RepairInput.none());
    return "fragments/report :: report";
  }

  /**
   * Renders the report again with the user's input applied to the correction proposal.
   *
   * <p>The document arrives Base64-encoded from the report's own form ({@link RepairView}). It is
   * validated through {@code ReportService} with an empty tenant — the same "write nothing" path as
   * an anonymous upload — <em>for every caller</em>: an authenticated visitor's upload was already
   * persisted by {@link #pruefen}, and recording the same document again because they typed a
   * reference would be a duplicate, not a record. The correction itself is never stored.
   */
  @PostMapping("/validator/korrigieren")
  public String korrigieren(
      @RequestParam(name = "document", required = false) String document,
      @RequestParam(name = RepairProposer.INPUT_ORDER_REFERENCE, required = false)
          String orderReference,
      @RequestParam(name = "filename", required = false) String filename,
      Model model) {
    model.addAttribute("aiEnabled", explanations.isEnabled());
    byte[] bytes;
    try {
      bytes = document == null ? new byte[0] : Base64.getDecoder().decode(document);
    } catch (IllegalArgumentException e) {
      bytes = new byte[0];
    }
    if (bytes.length == 0) {
      model.addAttribute(
          "uploadError",
          "Die Datei konnte nicht übernommen werden. Bitte laden Sie sie erneut hoch.");
      return "fragments/report :: report";
    }

    RepairInput input;
    try {
      input = new RepairInput(orderReference);
    } catch (IllegalArgumentException e) {
      model.addAttribute("repairError", e.getMessage());
      // Keep what was typed in the field (cut to the field's own limit), so it can be corrected
      // rather than retyped.
      model.addAttribute(
          "typedOrderReference",
          orderReference.length() > RepairInput.MAX_ORDER_REFERENCE_LENGTH
              ? orderReference.substring(0, RepairInput.MAX_ORDER_REFERENCE_LENGTH)
              : orderReference);
      input = RepairInput.none();
    }

    ValidationReport report = reports.validate(bytes, Optional.empty()).report();
    model.addAttribute("filename", shortened(filename));
    addReport(model, report);
    addRepair(model, bytes, report, input);
    return "fragments/report :: report";
  }

  /**
   * Explains one finding on the public page and renders just that explanation.
   *
   * <p>The finding is rebuilt from the posted fields rather than looked up — see the class Javadoc
   * for why there is nothing to look up. {@code Finding}'s own invariants validate the input, and
   * an invalid combination surfaces as the same friendly notice a provider outage does: this route
   * must not be a way to produce a 500.
   */
  @PostMapping("/validator/erklaeren")
  public String erklaeren(
      @RequestParam String ruleId,
      @RequestParam String messageDe,
      @RequestParam String messageEn,
      @RequestParam(required = false) String location,
      @RequestParam String severity,
      @RequestParam String sourceFormat,
      @RequestParam String profile,
      Model model) {
    Optional<String> explanation = Optional.empty();
    if (explanations.isEnabled()) {
      try {
        Finding finding =
            Finding.of(
                Severity.valueOf(severity), ruleId, blankToNull(location), messageDe, messageEn);
        explanation = explanations.explain(finding, ExplanationContext.of(sourceFormat, profile));
      } catch (RuntimeException e) {
        // A malformed submission (an unknown severity, an over-long field) is the caller's problem,
        // not a server error: answer with the same "keine Erklärung verfügbar" notice.
        //
        // Logged, though — the M5 hostile review (F14) pointed out that this catch also covers a
        // genuine defect in Finding or ExplanationService, on a public endpoint, and swallowing it
        // silently means nobody would ever find out. WARN and the rule id only, never the message
        // text: that is submitted document content, and FindingExplainer applies the same rule to
        // its own failures for the same reason.
        log.warn(
            "Public explanation request rejected for rule {}: {}",
            Texts.safeEcho(ruleId),
            e.getClass().getSimpleName());
        explanation = Optional.empty();
      }
    }
    model.addAttribute("explanation", explanation.orElse(null));
    return "fragments/explanation :: explanation";
  }

  private static void addReport(Model model, ValidationReport report) {
    addReport(model, report.sourceFormat(), report.profile(), report.isValid(), report.findings());
  }

  /**
   * Adds the correction proposal, if any. A failure here must never cost the visitor the report
   * itself, so it is logged (class name only — the message could carry document content) and the
   * report renders without a proposal.
   */
  private void addRepair(Model model, byte[] bytes, ValidationReport report, RepairInput input) {
    try {
      repairs
          .propose(bytes, report, input)
          .ifPresent(
              proposal -> model.addAttribute("repair", RepairView.of(proposal, bytes, input)));
    } catch (RuntimeException e) {
      log.warn("Correction proposal failed: {}", e.getClass().getSimpleName());
    }
  }

  private static String shortened(String filename) {
    if (filename == null || filename.length() <= MAX_FILENAME_LENGTH) {
      return filename;
    }
    return filename.substring(0, MAX_FILENAME_LENGTH);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * Populates the model a report fragment renders from; shared with the dashboard's report view.
   */
  static void addReport(
      Model model, String sourceFormat, String profile, boolean valid, List<Finding> findings) {
    List<FindingView> views = FindingView.of(findings);
    model.addAttribute("sourceFormat", sourceFormat);
    model.addAttribute("profile", profile);
    model.addAttribute("valid", valid);
    model.addAttribute("findings", views);
    model.addAttribute("errors", FindingView.ofSeverity(views, Severity.ERROR));
    model.addAttribute("warnings", FindingView.ofSeverity(views, Severity.WARN));
    model.addAttribute("infos", FindingView.ofSeverity(views, Severity.INFO));
  }
}
