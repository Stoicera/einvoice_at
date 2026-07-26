package com.stoicera.einvoice.app.privacy;

import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import com.stoicera.einvoice.app.persistence.InvoiceRepository;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR Art. 17 — erases one tenant and everything belonging to it.
 *
 * <p>Closes the gap {@code docs/privacy.md} §4 carried from M3 to M5 by name. Until this existed,
 * that document said full deletion was "noch nicht implementiert" and that the platform was
 * therefore not ready for real customer data — which was the honest thing to write and is now no
 * longer true.
 *
 * <h2>Everything, in one transaction, in FK order</h2>
 *
 * <p>Five tables reference the tenant, and two of them reference each other, so the order is not a
 * style choice: {@code report.invoice_id} points at {@code invoice(id)}, so reports go before
 * invoices; everything goes before the tenant row itself. One transaction, because a partial
 * erasure is the worst outcome available — a caller who was told "deleted" and whose audit trail
 * survived.
 *
 * <h2>Why the audit trail goes too</h2>
 *
 * <p>Every other table's contents are obviously the tenant's data. The audit trail is the
 * interesting case, because keeping it is defensible in the abstract: it is the record of what
 * happened, and accountability (Art. 5(2)) argues for retaining evidence. It still goes, for a
 * concrete reason — {@code audit_event.tenant_id} is a foreign key to the row carrying the person's
 * Keycloak subject and display name, so "keep the audit trail" in practice means "keep the tenant
 * row", which means not honouring the request at all. What survives is a log line: the tenant's
 * UUID (a surrogate this platform minted, not an identifier of a person) and the row counts. That
 * is enough to demonstrate that an erasure happened without keeping what was erased.
 *
 * <h2>The tenant may come back, and that is correct</h2>
 *
 * <p>The Keycloak account is not this platform's to delete. A user who erases their data and then
 * loads the dashboard again is provisioned a <em>new, empty</em> tenant with the same subject
 * ({@code CurrentTenant}). Nothing is resurrected — same person, no data — and a caller cannot tell
 * this apart from having signed up for the first time, which is the point.
 */
@Service
public class TenantErasureService {

  private static final Logger log = LoggerFactory.getLogger(TenantErasureService.class);

  /** What an erasure removed, per table. Returned so a caller can report it and log it. */
  public record Erased(
      long reports, long invoices, long apiKeys, long auditEvents, boolean tenantRow) {

    /** Whether anything at all was found to erase. */
    public boolean isEmpty() {
      return reports == 0 && invoices == 0 && apiKeys == 0 && auditEvents == 0 && !tenantRow;
    }
  }

  private final TenantRepository tenants;
  private final InvoiceRepository invoices;
  private final ReportRepository reports;
  private final ApiKeyRepository apiKeys;
  private final AuditEventRepository auditEvents;

  public TenantErasureService(
      TenantRepository tenants,
      InvoiceRepository invoices,
      ReportRepository reports,
      ApiKeyRepository apiKeys,
      AuditEventRepository auditEvents) {
    this.tenants = tenants;
    this.invoices = invoices;
    this.reports = reports;
    this.apiKeys = apiKeys;
    this.auditEvents = auditEvents;
  }

  /**
   * Erases {@code tenantId} and all its rows.
   *
   * <p>Idempotent: erasing an already-erased (or never-existing) tenant deletes nothing and returns
   * an {@link Erased#isEmpty()} result rather than failing. A double-submitted confirmation form,
   * or a retried request, must not produce an error page after the data is already gone.
   */
  @Transactional
  public Erased erase(UUID tenantId) {
    // Order is load-bearing: report.invoice_id references invoice(id), and every table references
    // tenant(id). Reversing any of these fails on a foreign key rather than deleting less.
    long erasedReports = reports.deleteByTenantId(tenantId);
    long erasedApiKeys = apiKeys.deleteByTenantId(tenantId);
    long erasedAuditEvents = auditEvents.deleteByTenantId(tenantId);
    long erasedInvoices = invoices.deleteByTenantId(tenantId);

    boolean tenantRow = tenants.findById(tenantId).isPresent();
    if (tenantRow) {
      tenants.deleteById(tenantId);
    }

    Erased erased =
        new Erased(erasedReports, erasedInvoices, erasedApiKeys, erasedAuditEvents, tenantRow);
    // The only thing that outlives the erasure. The tenant id is a surrogate UUID this platform
    // minted; the person's subject and name are in the row that just went away.
    log.info(
        "Tenant erasure completed for {}: {} reports, {} invoices, {} API keys, {} audit events,"
            + " tenant row {}",
        tenantId,
        erased.reports(),
        erased.invoices(),
        erased.apiKeys(),
        erased.auditEvents(),
        tenantRow ? "removed" : "already absent");
    return erased;
  }
}
