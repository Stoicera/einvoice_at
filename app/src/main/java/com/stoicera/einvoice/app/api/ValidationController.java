package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.report.ValidateResult;
import com.stoicera.einvoice.app.security.CurrentTenant;
import com.stoicera.einvoice.validation.repair.RepairInput;
import com.stoicera.einvoice.validation.repair.RepairProposer;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The public validator: {@code POST /api/v1/validate}, {@link
 * com.stoicera.einvoice.app.security.SecurityConfig permitAll} for anonymous callers.
 *
 * <p>An uploaded document (multipart part {@code file}) is read once and run through {@link
 * com.stoicera.einvoice.validation.InvoiceValidator}, which detects the format from the root
 * namespace and picks the matching pipeline: ebInterface 6.1 against the Austrian B2G profile, or
 * UBL 2.1 against the official OpenPeppol BIS Billing 3.0 rule set. The caller says nothing about
 * the format. The validator never throws on bad input — malformed XML, an unrecognised format or an
 * oversized document all become findings in a normal 200 response.
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
 *
 * <p>With {@code repair=true} the response also carries a correction proposal for the findings that
 * have a known fix ({@link RepairProposer}): the corrected document, a unified diff and its
 * re-validation. {@code orderReference} supplies the Auftragsreferenz ({@code AT-B2G-01}), the one
 * value that cannot come from the document. The proposal is computed in memory and never persisted,
 * for any caller; the rate limit covers it as part of this endpoint.
 */
@RestController
@RequestMapping("/api/v1/validate")
public class ValidationController {

  private final ReportService reports;
  private final CurrentTenant currentTenant;
  private final RepairProposer repairs;

  public ValidationController(
      ReportService reports, CurrentTenant currentTenant, RepairProposer repairs) {
    this.reports = reports;
    this.currentTenant = currentTenant;
    this.repairs = repairs;
  }

  @PostMapping(
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Validate an ebInterface 6.1 or Peppol BIS Billing 3.0 (UBL) document",
      description =
          "The format is detected from the document's root namespace — do not declare it. An"
              + " ebInterface 6.1 document is judged against the Austrian B2G profile; a UBL 2.1"
              + " invoice or credit note against the official OpenPeppol rule set, executed"
              + " unmodified at a pinned version. Public — no credential required. An"
              + " authenticated caller (JWT or API key) additionally gets the report persisted"
              + " and audited; see the class Javadoc. With repair=true the response adds a"
              + " correction proposal for findings with a known fix (AT-B2G-01,"
              + " PEPPOL-EN16931-R010, PEPPOL-EN16931-R020): every change with its rule id,"
              + " business term and the source of its value, the corrected document, a unified"
              + " diff and the re-validation of the corrected document. Nothing is invented: a"
              + " value the document does not contain and the caller did not supply is reported"
              + " as a placeholder and not written.")
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
      description =
          "The multipart request has no `file` part, or `orderReference` is longer than 255"
              + " characters or contains a character XML cannot carry.",
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
      @Parameter(
              description =
                  "The document to validate — ebInterface 6.1 or UBL 2.1, detected automatically.",
              required = true)
          @RequestPart("file")
          MultipartFile file,
      @Parameter(
              description =
                  "Also return a correction proposal for findings with a known fix. Default"
                      + " false.")
          @RequestParam(name = "repair", defaultValue = "false")
          boolean repair,
      @Parameter(
              description =
                  "The Auftragsreferenz to use when the proposal fixes AT-B2G-01. Only read with"
                      + " repair=true. At most 255 characters.")
          @RequestParam(name = RepairProposer.INPUT_ORDER_REFERENCE, required = false)
          String orderReference,
      Authentication authentication)
      throws IOException {
    RepairInput input = repair ? repairInput(orderReference) : RepairInput.none();
    Optional<UUID> tenantId =
        currentTenant.resolveIfAuthenticated(authentication).map(TenantEntity::getId);
    byte[] bytes = file.getBytes();
    ValidateResult result = reports.validate(bytes, tenantId);
    if (!repair) {
      return result;
    }
    return repairs.propose(bytes, result.report(), input).map(result::withRepair).orElse(result);
  }

  /** Checked before validating, so an unusable input costs no validation run. */
  private static RepairInput repairInput(String orderReference) {
    try {
      return new RepairInput(orderReference);
    } catch (IllegalArgumentException e) {
      throw new InvalidRepairInputException(e.getMessage());
    }
  }
}
