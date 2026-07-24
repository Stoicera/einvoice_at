package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The M3 authentication matrix (milestone Abnahme item): anonymous, bad key, revoked key, valid key
 * and valid JWT against a protected route, plus the public-validator carve-out. It exercises the
 * whole chain end to end over HTTP — the {@link ApiKeyAuthFilter}, the OAuth2 resource server with
 * real Keycloak tokens, and the authorization rules — not mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthMatrixIT extends AbstractKeycloakIT {

  // A route under /api/** that requires authentication. It has no handler yet (invoices arrive in
  // T6), so an authenticated caller gets 404 while an unauthenticated one is stopped at 401 — which
  // is exactly what the matrix checks: did authentication happen? We assert "not 401/403" for the
  // positive cases so this stays green once T6 gives the path a handler.
  private static final String PROTECTED_ROUTE = "/api/v1/invoices";

  @LocalServerPort private int port;
  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void anonymousIsRejectedWithUnauthorizedOnAProtectedRoute() throws Exception {
    assertThat(get(PROTECTED_ROUTE, null, null)).isEqualTo(401);
  }

  @Test
  void anUnknownApiKeyIsUnauthorized() throws Exception {
    assertThat(get(PROTECTED_ROUTE, "X-Api-Key", "eiv_this-key-was-never-issued")).isEqualTo(401);
  }

  @Test
  void aRevokedApiKeyIsUnauthorized() throws Exception {
    TenantEntity tenant = newTenant();
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    ApiKeyEntity key =
        apiKeys.save(
            new ApiKeyEntity(tenant.getId(), "revoked", generated.keyHash(), generated.prefix()));
    key.revoke(Instant.now());
    apiKeys.save(key);

    // The active-key lookup excludes revoked rows, so presenting the (correct) plaintext of a
    // revoked key authenticates nobody.
    assertThat(get(PROTECTED_ROUTE, "X-Api-Key", generated.plaintext())).isEqualTo(401);
  }

  @Test
  void aValidApiKeyAuthenticates() throws Exception {
    TenantEntity tenant = newTenant();
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "active", generated.keyHash(), generated.prefix()));

    // Authenticated: past the 401/403 gate (404 only because the handler lands in T6).
    assertThat(get(PROTECTED_ROUTE, "X-Api-Key", generated.plaintext())).isNotIn(401, 403);
  }

  @Test
  void aValidJwtAuthenticates() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    assertThat(get(PROTECTED_ROUTE, "Authorization", "Bearer " + token)).isNotIn(401, 403);
  }

  @Test
  void theValidatorEndpointIsPublicToAnonymousCallers() throws Exception {
    // POST /api/v1/validate is permitAll, so security does not block an anonymous caller. The
    // endpoint itself arrives in T7; until then there is no handler, so the request routes to 404 —
    // crucially NOT 401/403. T7 replaces this with a 200 + ValidationReport assertion.
    assertThat(post(PROTECTED_VALIDATE)).isEqualTo(404);
  }

  private static final String PROTECTED_VALIDATE = "/api/v1/validate";

  private TenantEntity newTenant() {
    return tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Matrix Tenant"));
  }

  private int get(String path, String headerName, String headerValue) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (headerName != null) {
      builder.header(headerName, headerValue);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private int post(String path) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }
}
