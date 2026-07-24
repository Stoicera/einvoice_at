package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.invoice.InvoiceCreated;
import com.stoicera.einvoice.app.invoice.InvoicePage;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
  @Operation(
      summary = "Create an invoice",
      description =
          "Canonical JSON in; creates the invoice and returns its id and validation report. An"
              + " invoice that validates with findings is still created (validation is"
              + " informative here, not gating).")
  // The Spring @RequestBody parameter below is typed byte[], which springdoc would otherwise
  // document as a base64 string; this overrides that with the actual shape (arbitrary JSON,
  // parsed by the core/mapping canonical-JSON reader — no fixed DTO backs it).
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(type = "object", description = "The canonical invoice JSON.")))
  @ApiResponse(
      responseCode = "201",
      description = "Invoice created.",
      headers =
          @Header(
              name = HttpHeaders.LOCATION,
              description = "URI of the created invoice.",
              schema = @Schema(type = "string")),
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = InvoiceCreated.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Invalid invoice JSON.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "An invoice with the same invoice number already exists for this tenant.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "422",
      description = "The invoice is well-formed JSON but violates a domain rule.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<InvoiceCreated> create(
      @RequestBody byte[] body, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    InvoiceCreated created = invoices.create(tenant.getId(), body);
    return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
  }

  /** Lists the caller's tenant's invoices, newest first. */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List invoices", description = "The caller's tenant's invoices, paginated.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = InvoicePage.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public InvoicePage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return invoices.list(tenant.getId(), page, size);
  }

  /** Returns the stored canonical JSON for one of the caller's tenant's invoices. */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Get an invoice",
      description = "The stored canonical JSON for one of the caller's tenant's invoices.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No invoice with the given id exists for this tenant.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<String> get(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(invoices.canonicalJson(tenant.getId(), id));
  }

  /** Returns the regenerated ebInterface 6.1 XML for one of the caller's tenant's invoices. */
  @GetMapping(value = "/{id}/ebinterface", produces = MediaType.APPLICATION_XML_VALUE)
  @Operation(
      summary = "Get an invoice as ebInterface 6.1 XML",
      description =
          "The ebInterface 6.1 XML for one of the caller's tenant's invoices, regenerated on demand.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(mediaType = MediaType.APPLICATION_XML_VALUE, schema = @Schema(type = "string")))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No invoice with the given id exists for this tenant.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ResponseEntity<String> ebInterface(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_XML)
        .body(invoices.ebInterfaceXml(tenant.getId(), id));
  }
}
