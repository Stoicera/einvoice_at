package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.report.ReportDetail;
import com.stoicera.einvoice.app.report.ReportPage;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.security.CurrentTenant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant reports API: every stored validation report, whichever route produced it — an ad-hoc
 * {@code POST /api/v1/validate} run ({@code invoiceId} null) or a {@code POST /api/v1/invoices}
 * creation ({@code invoiceId} set) — appears here, tenant-scoped like every other endpoint in this
 * package. Errors are RFC 9457 {@code application/problem+json}, produced by {@link
 * ApiExceptionHandler}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/reports} — the tenant's reports, newest first, paginated.
 *   <li>{@code GET /api/v1/reports/{id}} — the full stored report, findings included.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final ReportService reports;
  private final CurrentTenant currentTenant;

  public ReportController(ReportService reports, CurrentTenant currentTenant) {
    this.reports = reports;
    this.currentTenant = currentTenant;
  }

  /** Lists the caller's tenant's reports, newest first. */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ReportPage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return reports.list(tenant.getId(), page, size);
  }

  /** Returns the full stored report for one of the caller's tenant's reports. */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ReportDetail get(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return reports.get(tenant.getId(), id);
  }
}
