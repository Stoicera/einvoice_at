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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The {@code /api/v1/api-keys} endpoints end to end with a real Keycloak JWT: create returns the
 * plaintext once, list shows the key without secrets, delete revokes it. Also proves the
 * OAuth2-only rule (an API key is refused with 403, never allowed to manage keys) and that a JWT
 * login provisions its tenant on first use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyEndpointIT extends AbstractKeycloakIT {

  private static final String API_KEYS = "/api/v1/api-keys";
  private static final String PROTECTED_ROUTE = "/api/v1/invoices";
  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

  @LocalServerPort private int port;
  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void createListAndRevokeRoundTrip() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);

    HttpResponse<String> created =
        send("POST", API_KEYS, bearer(token), null, "{\"name\":\"ci-pipeline\"}");
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode createdBody = json.readTree(created.body());
    String plaintext = createdBody.get("key").asText();
    String id = createdBody.get("id").asText();
    assertThat(plaintext).startsWith("eiv_");
    assertThat(createdBody.get("prefix").asText()).isEqualTo(plaintext.substring(0, 8));
    assertThat(createdBody.get("name").asText()).isEqualTo("ci-pipeline");

    // List shows the key, with its prefix, and never leaks the plaintext or hash.
    HttpResponse<String> listed = send("GET", API_KEYS, bearer(token), null, null);
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(listed.body()).contains(id).contains("ci-pipeline");
    // Neither the plaintext nor the stored SHA-256 hash ever appears in the listing.
    assertThat(listed.body()).doesNotContain(plaintext);
    assertThat(listed.body()).doesNotContain(ApiKeys.sha256Hex(plaintext));

    // Revoke, then the listing marks it revoked.
    HttpResponse<String> revoked = send("DELETE", API_KEYS + "/" + id, bearer(token), null, null);
    assertThat(revoked.statusCode()).isEqualTo(204);
    ApiKeyEntity row = apiKeys.findById(UUID.fromString(id)).orElseThrow();
    assertThat(row.isRevoked()).isTrue();

    // And the revoked key no longer authenticates.
    assertThat(get(PROTECTED_ROUTE, "X-Api-Key", plaintext)).isEqualTo(401);
  }

  @Test
  void aCreatedKeyImmediatelyAuthenticatesOnAProtectedRoute() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    HttpResponse<String> created =
        send("POST", API_KEYS, bearer(token), null, "{\"name\":\"connector\"}");
    String plaintext = json.readTree(created.body()).get("key").asText();

    // The plaintext returned by create works as an X-Api-Key: authenticated (not 401/403).
    assertThat(get(PROTECTED_ROUTE, "X-Api-Key", plaintext)).isNotIn(401, 403);
  }

  @Test
  void aFirstJwtRequestProvisionsTheTenantAndLaterRequestsReuseIt() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String subject = subjectOf(token);

    send("POST", API_KEYS, bearer(token), null, "{\"name\":\"first\"}");
    TenantEntity afterFirst = tenants.findByExternalSubject(subject).orElseThrow();
    assertThat(afterFirst.getDisplayName()).isEqualTo(TEST_USERNAME); // preferred_username

    send("POST", API_KEYS, bearer(token), null, "{\"name\":\"second\"}");
    TenantEntity afterSecond = tenants.findByExternalSubject(subject).orElseThrow();
    // Reused, not recreated.
    assertThat(afterSecond.getId()).isEqualTo(afterFirst.getId());
    assertThat(afterSecond.getCreatedAt()).isEqualTo(afterFirst.getCreatedAt());
  }

  @Test
  void revokingAnUnknownKeyIsAnApiKeyNotFoundProblem() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);

    HttpResponse<String> response =
        send("DELETE", API_KEYS + "/" + UUID.randomUUID(), bearer(token), null, null);

    // Its own slug, like invoice-not-found and report-not-found — not the generic framework
    // not-found ADR-0006's "one slug per condition" promise would otherwise be broken by.
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "api-key-not-found");
    assertThat(problem.get("status").asInt()).isEqualTo(404);
  }

  @Test
  void aTenantCannotRevokeOrSeeAnotherTenantsKey() throws Exception {
    // A key belonging to somebody else entirely, seeded straight into the database.
    TenantEntity otherTenant =
        tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Other Tenant"));
    ApiKeys.GeneratedKey othersKey = ApiKeys.generate();
    ApiKeyEntity othersRow =
        apiKeys.save(
            new ApiKeyEntity(
                otherTenant.getId(), "not-yours", othersKey.keyHash(), othersKey.prefix()));

    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);

    // Revoking it reads as "not found" — no oracle telling the caller the id exists elsewhere.
    HttpResponse<String> revoke =
        send("DELETE", API_KEYS + "/" + othersRow.getId(), bearer(token), null, null);
    assertThat(revoke.statusCode()).isEqualTo(404);

    // And it is genuinely untouched, not merely reported as missing.
    assertThat(apiKeys.findById(othersRow.getId()).orElseThrow().isRevoked()).isFalse();

    // Nor does it appear in this tenant's listing.
    HttpResponse<String> listed = send("GET", API_KEYS, bearer(token), null, null);
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(listed.body()).doesNotContain(othersRow.getId().toString());
    assertThat(listed.body()).doesNotContain("not-yours");
  }

  @Test
  void anApiKeyMayNotManageApiKeys() throws Exception {
    // Seed an active API key for some tenant.
    TenantEntity tenant =
        tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Key Owner"));
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(new ApiKeyEntity(tenant.getId(), "self", generated.keyHash(), generated.prefix()));

    // A valid API key authenticates, but the /api-keys rule requires ROLE_USER (a JWT login), so
    // the
    // key is refused with 403 — enforced in the security layer, not in controller code.
    assertThat(get(API_KEYS, "X-Api-Key", generated.plaintext())).isEqualTo(403);
    assertThat(send("POST", API_KEYS, null, generated.plaintext(), "{\"name\":\"x\"}").statusCode())
        .isEqualTo(403);
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private int get(String path, String headerName, String headerValue) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    builder.header(headerName, headerValue);
    return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private HttpResponse<String> send(
      String method, String path, String authorization, String apiKey, String jsonBody)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    if (apiKey != null) {
      builder.header("X-Api-Key", apiKey);
    }
    HttpRequest.BodyPublisher body =
        jsonBody == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(jsonBody);
    if (jsonBody != null) {
      builder.header("Content-Type", "application/json");
    }
    builder.method(method, body);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
