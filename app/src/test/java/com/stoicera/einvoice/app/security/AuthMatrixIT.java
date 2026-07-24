package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    // POST /api/v1/validate is permitAll, so an anonymous caller reaches the handler and gets a
    // normal 200 with a ValidationReport for a valid upload — not the 401/403 a protected route
    // would answer with, and (per ValidateApiIT) no database row is written for this call.
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", ValidateApiIT.validFileBytes());
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PROTECTED_VALIDATE))
                .header("Content-Type", multipart.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode payload = new ObjectMapper().readTree(response.body());
    assertThat(payload.get("id").isNull()).isTrue();
    assertThat(payload.get("report").get("valid").asBoolean()).isTrue();
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
}
