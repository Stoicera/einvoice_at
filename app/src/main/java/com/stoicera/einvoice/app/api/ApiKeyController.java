package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.security.ApiKeyService;
import com.stoicera.einvoice.app.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant API-key management: create, list and revoke. Mapped under {@code /api/v1/api-keys}, which
 * {@link com.stoicera.einvoice.app.security.SecurityConfig} restricts to OAuth2 (JWT) logins — an
 * API key cannot reach these endpoints, so it can neither mint nor revoke keys.
 *
 * <p>The tenant is resolved from the authenticated principal (provisioned on first sight for a
 * JWT). Creation returns the plaintext key once; listing never does.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

  private final ApiKeyService apiKeys;
  private final CurrentTenant currentTenant;

  public ApiKeyController(ApiKeyService apiKeys, CurrentTenant currentTenant) {
    this.apiKeys = apiKeys;
    this.currentTenant = currentTenant;
  }

  /** Mints a new key for the caller's tenant and returns its plaintext exactly once. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create an API key",
      description =
          "Mints a new key for the caller's tenant and returns its plaintext exactly once.")
  @ApiResponse(
      responseCode = "201",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = CreatedApiKeyResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The request body fails validation (e.g. a blank or overlong name).",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "401",
      description =
          "Missing or invalid credentials (JWT login required — an API key cannot"
              + " manage API keys).",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public CreatedApiKeyResponse create(
      @Valid @RequestBody CreateApiKeyRequest request, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    ApiKeyService.CreatedKey created = apiKeys.create(tenant.getId(), request.name());
    return CreatedApiKeyResponse.of(created.entity(), created.generated());
  }

  /** Lists the caller's tenant's keys (active and revoked), newest first, without secrets. */
  @GetMapping
  @Operation(
      summary = "List API keys",
      description =
          "The caller's tenant's keys (active and revoked), newest first, without secrets.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              array = @ArraySchema(schema = @Schema(implementation = ApiKeyResponse.class))))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials (JWT login required).",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public List<ApiKeyResponse> list(Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    return apiKeys.list(tenant.getId()).stream().map(ApiKeyResponse::of).toList();
  }

  /**
   * Revokes one of the caller's tenant's keys (soft: {@code revoked_at} is stamped, row retained).
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Revoke an API key",
      description = "Soft revoke: revokedAt is stamped, the row is retained.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials (JWT login required).",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No API key with the given id exists for this tenant.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public void revoke(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    apiKeys.revoke(tenant.getId(), id);
  }
}
