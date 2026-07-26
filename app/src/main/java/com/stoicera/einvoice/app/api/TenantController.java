package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.privacy.TenantErasureService;
import com.stoicera.einvoice.app.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code DELETE /api/v1/tenant} — erasure on request (GDPR Art. 17), for callers who are not a
 * browser.
 *
 * <p>{@code docs/privacy.md} §4 named this endpoint by name while it did not exist. It does now,
 * and the document no longer has to warn that the platform is unfit for real customer data.
 *
 * <h2>No confirmation parameter, deliberately</h2>
 *
 * <p>The dashboard requires the word {@code LÖSCHEN} typed into a form, because a human can
 * misclick. An API caller cannot: {@code DELETE} on a resource named {@code /tenant}, carrying that
 * tenant's own credential, is already an unambiguous statement of intent, and a mandatory {@code
 * ?confirm=yes} would be ceremony that every client would hard-code on its first run. The
 * protection that matters here is that the credential scopes the deletion — there is no tenant id
 * in the request, so this endpoint cannot be pointed at anybody else.
 *
 * <h2>The caller's own credential dies with the call</h2>
 *
 * <p>An {@code X-Api-Key} used to make this request is erased by it. That is correct and worth
 * stating: the response is the last thing that credential will ever do, and a subsequent request
 * with it is a {@code 401}, not a {@code 404}.
 */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {

  private final CurrentTenant currentTenant;
  private final TenantErasureService erasure;

  public TenantController(CurrentTenant currentTenant, TenantErasureService erasure) {
    this.currentTenant = currentTenant;
    this.erasure = erasure;
  }

  /** Erases the calling tenant and every row belonging to it. Idempotent. */
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Erase this tenant",
      description =
          "Deletes every invoice, report, API key and audit event belonging to the calling tenant,"
              + " and the tenant itself (GDPR Art. 17). Irreversible; there is no backup to restore"
              + " from. The credential used for this call is erased by it. Note that invoices are"
              + " deleted here — § 132 BAO obliges the business to retain them for seven years, so"
              + " export what is needed before calling this.")
  @ApiResponse(responseCode = "204", description = "Erased. Nothing remains for this tenant.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public void erase(Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    erasure.erase(tenant.getId());
  }
}
