package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.invoice.InvoiceCreated;
import com.stoicera.einvoice.app.invoice.InvoicePage;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.security.CurrentTenant;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant invoices API. Every endpoint is tenant-scoped: the tenant is resolved from the
 * authenticated principal (JWT login or API key — both are accepted here), and one tenant can never
 * see another's rows. Errors are RFC 9457 {@code application/problem+json}, produced by {@link
 * ApiExceptionHandler}.
 *
 * <ul>
 *   <li>{@code POST /api/v1/invoices} — canonical JSON in, creates the invoice, returns its id and
 *       validation report (201, {@code Location} header). An invoice that validates with findings
 *       is still created.
 *   <li>{@code GET /api/v1/invoices} — the tenant's invoices, newest first, paginated.
 *   <li>{@code GET /api/v1/invoices/{id}} — the stored canonical JSON.
 *   <li>{@code GET /api/v1/invoices/{id}/ebinterface} — the ebInterface 6.1 XML, regenerated on
 *       demand.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

  private static final String BASE_PATH = "/api/v1/invoices";
  private static final int DEFAULT_PAGE_SIZE = 20;

  private final InvoiceService invoices;
  private final CurrentTenant currentTenant;

  public InvoiceController(InvoiceService invoices, CurrentTenant currentTenant) {
    this.invoices = invoices;
    this.currentTenant = currentTenant;
  }

  /** Creates an invoice from its raw canonical-JSON body. */
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<InvoiceCreated> create(
      @RequestBody byte[] body, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    InvoiceCreated created = invoices.create(tenant.getId(), body);
    return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
  }

  /** Lists the caller's tenant's invoices, newest first. */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public InvoicePage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return invoices.list(tenant.getId(), page, size);
  }

  /** Returns the stored canonical JSON for one of the caller's tenant's invoices. */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> get(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(invoices.canonicalJson(tenant.getId(), id));
  }

  /** Returns the regenerated ebInterface 6.1 XML for one of the caller's tenant's invoices. */
  @GetMapping(value = "/{id}/ebinterface", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> ebInterface(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_XML)
        .body(invoices.ebInterfaceXml(tenant.getId(), id));
  }
}
