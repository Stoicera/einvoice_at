package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.aiassist.ExplanationContext;
import com.stoicera.einvoice.app.ai.ExplanationService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.report.ValidateResult;
import com.stoicera.einvoice.app.security.CurrentTenant;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 */
@Controller
public class PublicWebController {

  private final ReportService reports;
  private final CurrentTenant currentTenant;
  private final ExplanationService explanations;

  public PublicWebController(
      ReportService reports, CurrentTenant currentTenant, ExplanationService explanations) {
    this.reports = reports;
    this.currentTenant = currentTenant;
    this.explanations = explanations;
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
    ValidateResult result = reports.validate(file.getBytes(), tenantId);

    model.addAttribute("filename", file.getOriginalFilename());
    model.addAttribute("aiEnabled", explanations.isEnabled());
    addReport(
        model,
        result.report().sourceFormat(),
        result.report().profile(),
        result.report().isValid(),
        result.report().findings());
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
        explanation = Optional.empty();
      }
    }
    model.addAttribute("explanation", explanation.orElse(null));
    return "fragments/explanation :: explanation";
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
