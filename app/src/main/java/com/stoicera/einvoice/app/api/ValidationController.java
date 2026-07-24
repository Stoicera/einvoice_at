package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.report.ValidateResult;
import com.stoicera.einvoice.app.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The public validator: {@code POST /api/v1/validate}, {@link
 * com.stoicera.einvoice.app.security.SecurityConfig permitAll} for anonymous callers.
 *
 * <p>An uploaded ebInterface 6.1 document (multipart part {@code file}) is read once and run
 * through {@link com.stoicera.einvoice.validation.EbInterface61Validator}; the validator never
 * throws on bad input — malformed XML, an unrecognised format or an oversized document all become
 * findings in a normal 200 response.
 *
 * <p>Persistence depends on who is calling: an anonymous caller gets the report back and nothing is
 * written (GDPR stance, SPEC section 8); an authenticated caller (JWT login or API key)
 * additionally gets the report persisted (as a {@code ReportEntity} with {@code invoiceId} null)
 * and a {@code VALIDATION_RUN} audit event recorded, and the response's {@code id} carries the
 * persisted report's id instead of {@code null}. Upload size is capped at the servlet-container
 * layer (2 MB, {@code spring.servlet.multipart.max-file-size}/{@code max-request-size}); an
 * oversized upload never reaches this method and is answered 413 by {@link ApiExceptionHandler}'s
 * inherited handling of {@code MaxUploadSizeExceededException}. A missing {@code file} part is
 * answered 400 the same way.
 */
@RestController
@RequestMapping("/api/v1/validate")
public class ValidationController {

  private final ReportService reports;
  private final CurrentTenant currentTenant;

  public ValidationController(ReportService reports, CurrentTenant currentTenant) {
    this.reports = reports;
    this.currentTenant = currentTenant;
  }

  @PostMapping(
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Validate an ebInterface 6.1 document",
      description =
          "Public — no credential required. An authenticated caller (JWT or API key)"
              + " additionally gets the report persisted and audited; see the class Javadoc.")
  // Public endpoint: overrides OpenApiConfig's global bearerAuth/apiKeyAuth requirement with an
  // empty security array, or the generated doc would wrongly claim a credential is needed here.
  @SecurityRequirements
  @ApiResponse(
      responseCode = "200",
      description = "Validation ran (a report with findings is still a 200, never an error).",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ValidateResult.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The multipart request has no `file` part.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "413",
      description = "The upload exceeds the 2 MB application-layer cap.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "429",
      description =
          "Anonymous per-IP rate limit exceeded (authenticated callers are never limited); see"
              + " the Retry-After header.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ValidateResult validate(
      @Parameter(description = "The ebInterface 6.1 document to validate.", required = true)
          @RequestPart("file")
          MultipartFile file,
      Authentication authentication)
      throws IOException {
    Optional<UUID> tenantId =
        currentTenant.resolveIfAuthenticated(authentication).map(TenantEntity::getId);
    return reports.validate(file.getBytes(), tenantId);
  }
}
