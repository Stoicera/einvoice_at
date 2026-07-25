package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.convert.ConversionService;
import com.stoicera.einvoice.app.convert.ConvertResult;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
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
 * Format conversion: {@code POST /api/v1/convert?from=…&to=…} (SPEC §4).
 *
 * <p>Authenticated, unlike the public validator: a conversion is a tenant action, it is audited
 * against the payload hash, and it is materially more expensive than a validation run.
 *
 * <p>The response carries three things — the converted XML, the conversion report (what the trip
 * cost), and a validation report of the <em>result</em>. See {@link ConvertResult} for why the
 * third one is what makes the endpoint worth calling.
 */
@RestController
@RequestMapping("/api/v1/convert")
public class ConvertController {

  private final ConversionService conversions;
  private final CurrentTenant currentTenant;

  public ConvertController(ConversionService conversions, CurrentTenant currentTenant) {
    this.conversions = conversions;
    this.currentTenant = currentTenant;
  }

  @PostMapping(
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Convert an invoice between ebInterface 6.1 and Peppol BIS Billing 3.0 UBL",
      description =
          "Converts through the canonical model, so no amount can change in transit. Answers with"
              + " the converted XML, a conversion report listing everything the target format could"
              + " not carry, and a validation report of the converted document against the target"
              + " format's own profile.")
  @ApiResponse(
      responseCode = "200",
      description = "The conversion ran. A lossy conversion is still a 200 — see the report.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ConvertResult.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Source and target format are the same, or the upload is not in the declared format.",
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
      responseCode = "413",
      description = "The upload exceeds the 2 MB application-layer cap.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "422",
      description = "The document parses but describes an invoice that violates a domain rule.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public ConvertResult convert(
      @Parameter(description = "The document to convert.", required = true) @RequestPart("file")
          MultipartFile file,
      @Parameter(description = "The format the upload is in.", required = true) @RequestParam
          ConversionService.ConversionFormat from,
      @Parameter(description = "The format to produce.", required = true) @RequestParam
          ConversionService.ConversionFormat to,
      Authentication authentication)
      throws IOException {
    TenantEntity tenant = currentTenant.require(authentication);
    return conversions.convert(tenant.getId(), file.getBytes(), from, to);
  }
}
