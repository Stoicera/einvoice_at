package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.MultipartBodies;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The M3 authentication matrix (milestone Abnahme item), end to end over real HTTP through the
 * whole chain — {@link ApiKeyAuthFilter}, the OAuth2 resource server against real Keycloak tokens,
 * and the authorization rules — never mocks.
 *
 * <p>Both directions of every mechanism: anonymous, an unknown key, a revoked key, a valid key, a
 * valid JWT, and (added in the M3 hostile-review fix wave, finding F2) the OAuth2 negatives that
 * were missing — a bearer header that is not a JWT, an {@code alg=none} token, and a genuine
 * Keycloak token whose payload has been rewritten. Also the public-validator carve-out, and the
 * refusal of a request presenting two competing credentials (F7).
 *
 * <p>{@code JwtDecoderTest} covers the token-validation cases that need a token this Keycloak would
 * never mint (wrong issuer, expired, foreign signing key); this class covers what the deployed
 * chain does with them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthMatrixIT extends AbstractKeycloakIT {

  // A route under /api/** that requires authentication. The positive cases assert "not 401/403"
  // rather than a specific success status: this test is about whether authentication happened, not
  // about what the invoices handler then answers.
  private static final String PROTECTED_ROUTE = "/api/v1/invoices";

  // The tenant-scoped reports API — the same /api/** → .authenticated() catch-all as
  // PROTECTED_ROUTE, so the anonymous side of the matrix applies here too (both the listing and the
  // detail route).
  private static final String REPORTS = "/api/v1/reports";

  // The milestone's one PUBLIC route: permitAll, reachable without any credential.
  private static final String PUBLIC_VALIDATE = "/api/v1/validate";

  @LocalServerPort private int port;
  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void anonymousIsRejectedWithUnauthorizedOnAProtectedRoute() throws Exception {
    assertThat(get(PROTECTED_ROUTE, null, null)).isEqualTo(401);
  }

  @Test
  void anonymousIsRejectedWithUnauthorizedOnTheReportsListingRoute() throws Exception {
    assertThat(get(REPORTS, null, null)).isEqualTo(401);
  }

  @Test
  void anonymousIsRejectedWithUnauthorizedOnTheReportDetailRoute() throws Exception {
    assertThat(get(REPORTS + "/" + UUID.randomUUID(), null, null)).isEqualTo(401);
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

    // Authenticated: past the 401/403 gate.
    assertThat(get(PROTECTED_ROUTE, "X-Api-Key", generated.plaintext())).isNotIn(401, 403);
  }

  @Test
  void aValidJwtAuthenticates() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    assertThat(get(PROTECTED_ROUTE, "Authorization", "Bearer " + token)).isNotIn(401, 403);
  }

  @Test
  void aBearerHeaderThatIsNotAJwtIsUnauthorized() throws Exception {
    assertThat(get(PROTECTED_ROUTE, "Authorization", "Bearer not-a-jwt-at-all")).isEqualTo(401);
  }

  @Test
  void aStructurallyValidButUnsignedTokenIsUnauthorized() throws Exception {
    // Three base64url segments with an empty signature — the shape of a JWT, none of the proof.
    String unsigned =
        base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}")
            + "."
            + base64Url("{\"sub\":\"attacker\",\"iss\":\"" + issuerUri() + "\"}")
            + ".";

    assertThat(get(PROTECTED_ROUTE, "Authorization", "Bearer " + unsigned)).isEqualTo(401);
  }

  @Test
  void aRealTokenWithATamperedPayloadIsUnauthorized() throws Exception {
    // Take a genuine Keycloak token and rewrite one claim while leaving the signature untouched.
    // This is the attack the signature exists to stop, and the whole chain — not just the decoder
    // unit test — has to refuse it.
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String[] parts = token.split("\\.");
    String payload =
        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            .replace("\"testuser\"", "\"someone-else\"");
    String tampered = parts[0] + "." + base64Url(payload) + "." + parts[2];

    assertThat(tampered).isNotEqualTo(token); // the rewrite actually happened
    assertThat(get(PROTECTED_ROUTE, "Authorization", "Bearer " + tampered)).isEqualTo(401);
  }

  @Test
  void presentingBothAnApiKeyAndABearerTokenIsRejectedRatherThanSilentlyPickingOne()
      throws Exception {
    // Both credentials are individually valid, and they belong to DIFFERENT tenants. Filter order
    // alone used to decide the winner (the bearer filter runs second and overwrote the API key's
    // authentication), so this request would silently have executed as the JWT's tenant. In a
    // multi-tenant billing API, "which tenant owns this write" must not be an accident of ordering.
    // RFC 6750 section 3.1 already names the answer: more than one authentication method is
    // invalid_request.
    TenantEntity keyTenant = newTenant();
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(keyTenant.getId(), "both", generated.keyHash(), generated.prefix()));
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PROTECTED_ROUTE))
                .header("Authorization", "Bearer " + token)
                .header("X-Api-Key", generated.plaintext())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode problem = new ObjectMapper().readTree(response.body());
    assertThat(problem.get("type").asText())
        .isEqualTo("https://einvoice-at.stoicera.com/problems/multiple-credentials");
  }

  @Test
  void presentingBothCredentialsIsRejectedOnThePublicValidatorToo() throws Exception {
    // The public route is not exempt: an ambiguous request is refused before it can be attributed
    // to a tenant at all, which is precisely the case where "who is this?" decides whether a report
    // gets persisted.
    TenantEntity keyTenant = newTenant();
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(
            keyTenant.getId(), "both-public", generated.keyHash(), generated.prefix()));
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", Fixtures.validFileBytes());

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PUBLIC_VALIDATE))
                .header("Content-Type", multipart.contentType())
                .header("Authorization", "Bearer " + token)
                .header("X-Api-Key", generated.plaintext())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void anAuthorizationHeaderThatIsNotBearerDoesNotCountAsASecondCredential() throws Exception {
    // Only a Bearer token competes with an API key. A Basic header is not a credential this API
    // accepts at all, so it must not turn a perfectly ordinary API-key request into a 400.
    TenantEntity keyTenant = newTenant();
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(keyTenant.getId(), "basic-too", generated.keyHash(), generated.prefix()));

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PROTECTED_ROUTE))
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .header("X-Api-Key", generated.plaintext())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isNotIn(400, 401, 403);
  }

  private static String base64Url(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void theValidatorEndpointIsPublicToAnonymousCallers() throws Exception {
    // POST /api/v1/validate is permitAll, so an anonymous caller reaches the handler and gets a
    // normal 200 with a ValidationReport for a valid upload — not the 401/403 a protected route
    // would answer with, and (per ValidateApiIT) no database row is written for this call.
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", Fixtures.validFileBytes());
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PUBLIC_VALIDATE))
                .header("Content-Type", multipart.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode payload = new ObjectMapper().readTree(response.body());
    assertThat(payload.get("id").isNull()).isTrue();
    assertThat(payload.get("report").get("valid").asBoolean()).isTrue();
  }

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
