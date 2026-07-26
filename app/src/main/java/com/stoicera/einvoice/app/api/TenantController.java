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
 * <h2>An API key cannot call this — only a login can</h2>
 *
 * <p>{@code SecurityConfig} requires {@code ROLE_USER} here, so an {@code X-Api-Key} is refused
 * with {@code 403} (ADR-0011 Entscheidung 6, M5 hostile review F1). This endpoint originally
 * accepted any credential, which put the most destructive operation in the platform behind the
 * <em>longest-lived and most widely copied</em> one — while the rule two lines above it in {@code
 * SecurityConfig} already forbade that same credential from so much as listing the tenant's API
 * keys. Invoices make the case rather than weaken it: {@code RetentionService} never expires them
 * because § 132 BAO obliges an Austrian business to keep them seven years, and a leaked integration
 * key must not be able to destroy records the customer is legally required to hold.
 *
 * <h2>No confirmation parameter, deliberately</h2>
 *
 * <p>The dashboard requires the word {@code LÖSCHEN} typed into a form, because a human can
 * misclick a button. An API caller cannot: {@code DELETE} on a resource named {@code /tenant},
 * carrying that tenant's own login, is already an unambiguous statement of intent, and a mandatory
 * {@code ?confirm=yes} would be ceremony that every client would hard-code on its first run. The
 * second protection is that the credential scopes the deletion — there is no tenant id in the
 * request, so this endpoint cannot be pointed at anybody else.
 *
 * <h2>The tenant's other credentials die with the call</h2>
 *
 * <p>Every {@code X-Api-Key} of the erased tenant stops authenticating, because the key rows go
 * with the tenant row. A subsequent request with one is a {@code 401}, not a {@code 404}. Worth
 * stating: it is the kind of thing that is only obvious once someone has tried it.
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
              + " from. Requires an interactive identity (OAuth2/JWT): an API key is refused with"
              + " 403, because a long-lived machine credential must not be able to trigger this."
              + " Every API key of the tenant stops working afterwards, since the key rows go too."
              + " Note that invoices are deleted here — § 132 BAO obliges the business to retain"
              + " them for seven years, so export what is needed before calling this.")
  @ApiResponse(responseCode = "204", description = "Erased. Nothing remains for this tenant.")
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "Authenticated with an API key. Erasure requires an OAuth2/JWT login — see ADR-0011.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  public void erase(Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    erasure.erase(tenant.getId());
  }
}
