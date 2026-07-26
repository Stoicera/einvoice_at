package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.aiassist.ExplanationContext;
import com.stoicera.einvoice.app.ai.ExplanationService;
import com.stoicera.einvoice.app.invoice.InvoicePage;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.invoice.InvoiceSummary;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.report.ReportDetail;
import com.stoicera.einvoice.app.report.ReportPage;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.security.ApiKeyService;
import com.stoicera.einvoice.app.security.CurrentTenant;
import com.stoicera.einvoice.core.validation.Finding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The authenticated dashboard (SPEC §5): overview, invoices, reports, and the document downloads.
 *
 * <h2>Every read is tenant-scoped by construction</h2>
 *
 * <p>Not one method here takes a tenant id from the request. The tenant comes from {@link
 * CurrentTenant#require}, and every service call passes it — so a hand-typed id in the URL bar
 * resolves against the caller's own tenant and produces the same 404 an unknown id would. That is
 * the same discipline the REST controllers follow, for the same reason, and the dashboard ITs
 * assert it per route rather than trusting it once.
 *
 * <h2>Why the downloads live here and not as links into {@code /api/v1}</h2>
 *
 * <p>The three document formats are already exposed by {@code InvoiceController}. Linking a
 * dashboard button at {@code /api/v1/invoices/{id}/pdf} would be the obvious move and would <em>not
 * work</em>: {@code /api/**} is the stateless chain and never reads the browser's session cookie
 * (ADR-0009), so the click would land on a 401 — or, with an OAuth2 client configured, a login
 * redirect rendered inside a download. These routes call the same {@link InvoiceService} methods
 * behind the session chain instead. The service is the shared part; the transport is not, and
 * cannot be.
 */
@Controller
@RequestMapping("/app")
public class DashboardController {

  /** Rows per page in the dashboard lists — a screenful, not the API's 20. */
  private static final int PAGE_SIZE = 25;

  /** How many recent rows the overview shows before "alle ansehen". */
  private static final int OVERVIEW_ROWS = 5;

  private final InvoiceService invoices;
  private final ReportService reports;
  private final ApiKeyService apiKeys;
  private final CurrentTenant currentTenant;
  private final ExplanationService explanations;

  public DashboardController(
      InvoiceService invoices,
      ReportService reports,
      ApiKeyService apiKeys,
      CurrentTenant currentTenant,
      ExplanationService explanations) {
    this.invoices = invoices;
    this.reports = reports;
    this.apiKeys = apiKeys;
    this.currentTenant = currentTenant;
    this.explanations = explanations;
  }

  // ------------------------------------------------------------------------ overview

  @GetMapping
  public String overview(Authentication authentication, Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    InvoicePage recentInvoices = invoices.list(tenant.getId(), 0, OVERVIEW_ROWS);
    ReportPage recentReports = reports.list(tenant.getId(), 0, OVERVIEW_ROWS);
    long activeKeys = apiKeys.list(tenant.getId()).stream().filter(key -> !key.isRevoked()).count();

    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("invoiceCount", recentInvoices.totalElements());
    model.addAttribute("reportCount", recentReports.totalElements());
    model.addAttribute("activeKeyCount", activeKeys);
    model.addAttribute("invoices", recentInvoices.content());
    model.addAttribute("reports", recentReports.content());
    return "app/overview";
  }

  // ------------------------------------------------------------------------ invoices

  @GetMapping("/rechnungen")
  public String invoiceList(
      @RequestParam(defaultValue = "0") int page, Authentication authentication, Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    InvoicePage result = invoices.list(tenant.getId(), page, PAGE_SIZE);

    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("invoices", result.content());
    model.addAttribute("page", result.page());
    model.addAttribute("totalPages", result.totalPages());
    model.addAttribute("totalElements", result.totalElements());
    return "app/invoices";
  }

  /**
   * One invoice: its stored canonical JSON, the reports it produced, and the three downloads.
   *
   * <p>The canonical JSON is shown because it is the document of record — every other format is
   * regenerated from it (ADR-0005), so it is the one thing a user may need to inspect or copy when
   * a generated document surprises them.
   */
  @GetMapping("/rechnungen/{id}")
  public String invoiceDetail(@PathVariable UUID id, Authentication authentication, Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    // Throws InvoiceNotFoundException for an id this tenant does not own — same 404, no oracle.
    InvoiceSummary invoice = invoices.summary(tenant.getId(), id);

    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("invoiceId", id);
    model.addAttribute("invoice", invoice);
    model.addAttribute("canonical", invoices.canonicalJson(tenant.getId(), id));
    model.addAttribute("reports", reports.listForInvoice(tenant.getId(), id));
    return "app/invoice-detail";
  }

  @GetMapping("/rechnungen/{id}/ebinterface")
  public ResponseEntity<Resource> downloadEbInterface(
      @PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return xml(
        invoices.ebInterfaceXml(tenant.getId(), id), filename(tenant, id, "ebinterface", "xml"));
  }

  @GetMapping("/rechnungen/{id}/ubl")
  public ResponseEntity<Resource> downloadUbl(
      @PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return xml(invoices.ublXml(tenant.getId(), id), filename(tenant, id, "ubl", "xml"));
  }

  @GetMapping("/rechnungen/{id}/pdf")
  public ResponseEntity<Resource> downloadPdf(
      @PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    byte[] pdf = invoices.pdf(tenant.getId(), id);
    return attachment(
        new ByteArrayResource(pdf), MediaType.APPLICATION_PDF, filename(tenant, id, "", "pdf"));
  }

  // ------------------------------------------------------------------------- reports

  @GetMapping("/berichte")
  public String reportList(
      @RequestParam(defaultValue = "0") int page, Authentication authentication, Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    ReportPage result = reports.list(tenant.getId(), page, PAGE_SIZE);

    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("reports", result.content());
    model.addAttribute("page", result.page());
    model.addAttribute("totalPages", result.totalPages());
    model.addAttribute("totalElements", result.totalElements());
    return "app/reports";
  }

  @GetMapping("/berichte/{id}")
  public String reportDetail(@PathVariable UUID id, Authentication authentication, Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    ReportDetail report = reports.get(tenant.getId(), id);

    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("reportId", id);
    model.addAttribute("report", report);
    model.addAttribute("aiEnabled", explanations.isEnabled());
    PublicWebController.addReport(
        model, report.sourceFormat(), report.profile(), report.valid(), report.findings());
    return "app/report-detail";
  }

  /**
   * Explains one finding of a stored report and re-renders just that explanation.
   *
   * <p>Takes the finding's <em>position</em>, not its text: unlike the public page, this report is
   * a row, so the server can read the finding itself and the client cannot choose what gets
   * explained. The position is what identifies it — a rule id is not unique within a report (see
   * {@link FindingView}).
   */
  @PostMapping("/berichte/{id}/erklaeren")
  public String explainFinding(
      @PathVariable UUID id,
      @RequestParam int findingIndex,
      Authentication authentication,
      Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    ReportDetail report = reports.get(tenant.getId(), id);

    Optional<String> explanation = Optional.empty();
    if (explanations.isEnabled()) {
      List<Finding> findings = report.findings();
      if (findingIndex >= 0 && findingIndex < findings.size()) {
        explanation =
            explanations.explain(
                findings.get(findingIndex),
                ExplanationContext.withPartyNames(
                    report.sourceFormat(), report.profile(), partyNames(tenant, report)));
      }
    }
    model.addAttribute("explanation", explanation.orElse(null));
    return "fragments/explanation :: explanation";
  }

  /**
   * The seller and buyer names to redact, for a report tied to a stored invoice; an empty array for
   * an ad-hoc validation, which has no invoice to read them from.
   */
  private String[] partyNames(TenantEntity tenant, ReportDetail report) {
    if (report.invoiceId() == null) {
      return new String[0];
    }
    try {
      var parties = invoices.parties(tenant.getId(), report.invoiceId());
      return new String[] {parties.sellerName(), parties.buyerName()};
    } catch (RuntimeException e) {
      // A report whose invoice has since been deleted must still be explainable.
      return new String[0];
    }
  }

  // ------------------------------------------------------------------------- helpers

  private static ResponseEntity<Resource> xml(String xml, String filename) {
    return attachment(
        new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)),
        MediaType.APPLICATION_XML,
        filename);
  }

  private static ResponseEntity<Resource> attachment(
      Resource body, MediaType contentType, String filename) {
    return ResponseEntity.ok()
        .contentType(contentType)
        // ContentDisposition builds a correctly quoted header (and RFC 5987 filename* when the name
        // is not ASCII), rather than a hand-concatenated string an invoice number could break.
        // .build() before .toString(): filename() returns the BUILDER, and calling toString on that
        // yields its object identity — which is exactly what shipped in the header until a test
        // looked at the value rather than at the status code.
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(body);
  }

  /**
   * A download filename built from the invoice number, so a user's downloads folder is readable.
   *
   * <p><strong>The number is sanitised rather than trusted.</strong> It reaches this method from a
   * stored invoice, but it originally came from a caller's JSON, and here it ends up in a {@code
   * Content-Disposition} header and then in a filesystem path — the two places where a {@code "}, a
   * newline or a {@code ../} stops being a harmless string. Anything outside {@code [A-Za-z0-9._-]}
   * becomes {@code _}, which also removes any need to reason about header quoting separately.
   */
  private String filename(TenantEntity tenant, UUID id, String variant, String extension) {
    String safe =
        invoices.summary(tenant.getId(), id).invoiceNumber().replaceAll("[^A-Za-z0-9._-]", "_");
    return variant.isBlank() ? safe + "." + extension : safe + "-" + variant + "." + extension;
  }
}
