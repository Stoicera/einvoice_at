package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.app.invoice.InvoicePage;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.privacy.TenantErasureService;
import com.stoicera.einvoice.app.report.ReportPage;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.security.ApiKeyService;
import com.stoicera.einvoice.app.security.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The account page: what this platform holds about you, and the button that removes it.
 *
 * <h2>Transparency before the danger zone</h2>
 *
 * <p>The page states the row counts per category <em>above</em> the delete form. GDPR Art. 15
 * (access) and Art. 17 (erasure) are two rights, and a page that offered only the second would be
 * asking someone to destroy data they were never shown. The counts come from the same tenant-scoped
 * services the rest of the dashboard reads, so what is counted here is exactly what is deleted.
 *
 * <h2>A typed confirmation, not a second button</h2>
 *
 * <p>Erasure is irreversible and unlimited in scope, so the form requires the word {@code LÖSCHEN}
 * typed exactly. A "are you sure?" dialog would need JavaScript and would be dismissible by a stray
 * Enter; a typed word cannot be produced by a misclick. The comparison is exact and case-sensitive.
 */
@Controller
@RequestMapping("/app/konto")
public class AccountController {

  /**
   * The word that has to be typed. German, because the UI is; upper case, because that is what the
   * page shows and an exact comparison is the point.
   */
  static final String CONFIRMATION_WORD = "LÖSCHEN";

  private final InvoiceService invoices;
  private final ReportService reports;
  private final ApiKeyService apiKeys;
  private final CurrentTenant currentTenant;
  private final TenantErasureService erasure;

  public AccountController(
      InvoiceService invoices,
      ReportService reports,
      ApiKeyService apiKeys,
      CurrentTenant currentTenant,
      TenantErasureService erasure) {
    this.invoices = invoices;
    this.reports = reports;
    this.apiKeys = apiKeys;
    this.currentTenant = currentTenant;
    this.erasure = erasure;
  }

  @GetMapping
  public String page(Authentication authentication, Model model) {
    return render(authentication, model, null);
  }

  /**
   * Erases the tenant, then ends the session.
   *
   * <p>The logout is part of the operation, not politeness: the session's principal now refers to a
   * tenant that does not exist, and leaving the user on a dashboard that silently re-provisions an
   * empty tenant would make it look as though the deletion had not worked. Redirects to the public
   * landing page, which is the one place that is guaranteed to still be there.
   */
  @PostMapping("/loeschen")
  public String delete(
      @RequestParam(required = false) String confirmation,
      Authentication authentication,
      HttpServletRequest request,
      Model model) {
    TenantEntity tenant = currentTenant.require(authentication);

    if (!CONFIRMATION_WORD.equals(confirmation)) {
      return render(
          authentication,
          model,
          "Zur Bestätigung muss genau das Wort " + CONFIRMATION_WORD + " eingegeben werden.");
    }

    erasure.erase(tenant.getId());

    // Invalidate first, then clear the context: an invalidated session cannot be reused, and the
    // cleared context keeps this very request from touching the erased tenant again on its way out.
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return "redirect:/?geloescht";
  }

  private String render(Authentication authentication, Model model, String error) {
    TenantEntity tenant = currentTenant.require(authentication);
    InvoicePage invoicePage = invoices.list(tenant.getId(), 0, 1);
    ReportPage reportPage = reports.list(tenant.getId(), 0, 1);

    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("invoiceCount", invoicePage.totalElements());
    model.addAttribute("reportCount", reportPage.totalElements());
    model.addAttribute("keyCount", apiKeys.list(tenant.getId()).size());
    model.addAttribute("confirmationWord", CONFIRMATION_WORD);
    model.addAttribute("formError", error);
    return "app/account";
  }
}
