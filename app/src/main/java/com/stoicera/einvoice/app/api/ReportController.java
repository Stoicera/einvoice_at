package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.ai.ReportExplanationService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.report.ReportDetail;
import com.stoicera.einvoice.app.report.ReportPage;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
  private final ReportExplanationService explanations;

  public ReportController(
      ReportService reports, CurrentTenant currentTenant, ReportExplanationService explanations) {
    this.reports = reports;
    this.currentTenant = currentTenant;
    this.explanations = explanations;
  }

  /** Lists the caller's tenant's reports, newest first. */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List reports", description = "The caller's tenant's reports, paginated.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ReportPage.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ReportPage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return reports.list(tenant.getId(), page, size);
  }

  /** Returns the full stored report for one of the caller's tenant's reports. */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get a report", description = "The full stored report, findings included.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ReportDetail.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No report with the given id exists for this tenant.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ReportDetail get(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return reports.get(tenant.getId(), id);
  }

  /**
   * Explains this report's findings with the AI assistant and returns the same {@link ReportDetail}
   * shape with {@code aiExplanation} filled in.
   *
   * <p>{@code POST} rather than {@code GET} even though nothing is stored: the call spends money at
   * a third-party provider, which is not something a caller should be able to trigger by prefetch,
   * a crawler, or a browser's address bar completion.
   */
  @PostMapping(value = "/{id}/explain", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Explain a report's findings",
      description =
          "Attaches AI-generated German explanations to the report's findings, errors first."
              + " Bounded by app.ai.max-findings-per-request per call; pass findingIndex to explain"
              + " exactly one. Nothing is persisted — the stored report keeps the validator's own"
              + " verdict. Requires features.ai-explanations to be enabled.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ReportDetail.class)))
  @ApiResponse(
      responseCode = "400",
      description = "findingIndex is not a position in this report's findings list.",
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
      responseCode = "404",
      description = "No report with the given id exists for this tenant.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description =
          "Either the feature is disabled on this deployment (ai-explanations-disabled) or the"
              + " provider produced no explanation (ai-explanation-unavailable).",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ReportDetail explain(
      @PathVariable UUID id,
      @RequestParam(required = false) Integer findingIndex,
      Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return explanations.explain(
        tenant.getId(),
        id,
        findingIndex == null ? OptionalInt.empty() : OptionalInt.of(findingIndex));
  }
}
