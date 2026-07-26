package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.MultipartBodies;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import com.stoicera.einvoice.app.security.AbstractKeycloakIT;
import com.stoicera.einvoice.app.security.ApiKeys;
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
 * {@code DELETE /api/v1/tenant} over real HTTP — the API half of GDPR Art. 17, the endpoint {@code
 * docs/privacy.md} §4 named by name while it did not exist.
 *
 * <h2>An API key cannot do this, and that is the load-bearing assertion here</h2>
 *
 * <p>The M5 hostile review (finding F1) found this endpoint reachable with an {@code X-Api-Key},
 * because it matched no explicit rule and fell through to {@code .anyRequest().authenticated()}.
 * Two lines above it in {@code SecurityConfig}, merely <em>listing</em> API keys is {@code
 * hasRole("USER")} — OAuth2-only — on the stated grounds that key management must be denied to keys
 * "in the security layer itself rather than trusting controller code to re-check". An integration
 * secret that lives in an ERP config file and a CI variable could therefore not read the key list,
 * but could irreversibly destroy every invoice, report, key and audit event this tenant owns.
 *
 * <p>Invoices make it worse rather than better: {@code RetentionService} deliberately never expires
 * them because § 132 BAO obliges an Austrian business to keep them for seven years. A leaked
 * machine credential must not be able to delete records the law requires the customer to hold.
 *
 * <p>So the matrix below is the point of this class: <strong>anonymous 401, API key 403,
 * interactive login 204.</strong> The typed-{@code LÖSCHEN} dashboard form ({@code
 * TenantDeletionIT}) and a real OAuth2/JWT identity are the two ways to erase a tenant; a machine
 * credential is not one of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantErasureApiIT extends AbstractKeycloakIT {

  private static final String TENANT = "/api/v1/tenant";

  @LocalServerPort private int port;

  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();

  // ------------------------------------------------------- who may erase, and who may not

  @Test
  void anApiKeyIsRefusedWithForbiddenAndErasesNothing() throws Exception {
    TenantEntity tenant = tenant("api-erase-key-refused");
    String key = apiKey(tenant);
    validateWithKey(key); // give the tenant a report row and an audit event to lose

    HttpResponse<String> response = deleteWithKey(key);

    // 403, not 401: the credential is genuine and the request is authenticated — it is the
    // AUTHORITY that is missing, which is exactly what the security layer is being asked to decide.
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(tenants.findById(tenant.getId()))
        .as("an API key must not be able to erase its tenant")
        .isPresent();
    // The key itself also survives, so an integration that made this call by mistake keeps working.
    assertThat(apiKeys.findByTenantIdOrderByCreatedAtDesc(tenant.getId())).isNotEmpty();
  }

  @Test
  void anAnonymousCallerCannotEraseAnything() throws Exception {
    TenantEntity tenant = tenant("api-erase-anon");
    apiKey(tenant);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url(TENANT))).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(tenants.findById(tenant.getId())).isPresent();
  }

  @Test
  void anInteractiveLoginErasesTheTenantAndAnswersNoContent() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    UUID tenantId = provisionedTenantId(token);
    validateWithToken(token); // a report row and an audit event that must go with it

    HttpResponse<String> response = deleteWithToken(token);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(tenants.findById(tenantId)).isEmpty();
  }

  // ------------------------------------------------------------------- what erasure does

  @Test
  void anApiKeyOfTheErasedTenantStopsAuthenticating() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    UUID tenantId = provisionedTenantId(token);
    String key = apiKey(tenants.findById(tenantId).orElseThrow());

    assertThat(deleteWithToken(token).statusCode()).isEqualTo(204);

    // 401, not 404: the key row went with the tenant, so the request never reaches a route at all.
    // Worth pinning — it is the kind of thing that is only obvious once someone has tried it.
    HttpResponse<String> after =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/invoices")))
                .header("X-Api-Key", key)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(after.statusCode()).isEqualTo(401);
  }

  @Test
  void erasingIsIdempotent() throws Exception {
    // The second call presents a login whose tenant no longer exists, so CurrentTenant provisions a
    // fresh, empty one and erases that — 204 again, never an error page. "The tenant may come back
    // and that is correct" (TenantErasureService's Javadoc) is the same property seen from the API.
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);

    assertThat(deleteWithToken(token).statusCode()).isEqualTo(204);
    assertThat(deleteWithToken(token).statusCode()).isEqualTo(204);
  }

  @Test
  void erasingOneTenantLeavesAnothersCredentialWorking() throws Exception {
    TenantEntity theirs = tenant("api-erase-theirs");
    String theirKey = apiKey(theirs);
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    provisionedTenantId(token);

    assertThat(deleteWithToken(token).statusCode()).isEqualTo(204);

    HttpResponse<String> unaffected =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/invoices")))
                .header("X-Api-Key", theirKey)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(unaffected.statusCode()).isEqualTo(200);
    assertThat(tenants.findById(theirs.getId())).isPresent();
  }

  // ------------------------------------------------------------------- helpers

  private HttpResponse<String> deleteWithKey(String key) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(TENANT))).header("X-Api-Key", key).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> deleteWithToken(String token) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(TENANT)))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** Makes one authenticated call so the tenant is provisioned, and returns its id. */
  private UUID provisionedTenantId(String token) throws Exception {
    http.send(
        HttpRequest.newBuilder(URI.create(url("/api/v1/invoices")))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
    return tenants.findByExternalSubject(subjectOf(token)).orElseThrow().getId();
  }

  private void validateWithKey(String key) throws Exception {
    validate(builder -> builder.header("X-Api-Key", key));
  }

  private void validateWithToken(String token) throws Exception {
    validate(builder -> builder.header("Authorization", "Bearer " + token));
  }

  private void validate(java.util.function.Consumer<HttpRequest.Builder> credential)
      throws Exception {
    byte[] fixture;
    try (var in =
        TenantErasureApiIT.class.getResourceAsStream(
            "/fixtures/at-b2g-01-missing-order-reference.xml")) {
      fixture = in.readAllBytes();
    }
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fixture);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url("/api/v1/validate")))
            .header("Content-Type", multipart.contentType())
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()));
    credential.accept(builder);
    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private TenantEntity tenant(String name) {
    return tenants.save(new TenantEntity("erasure-" + name + "-" + UUID.randomUUID(), name));
  }

  private String apiKey(TenantEntity tenant) {
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "erase-key", generated.keyHash(), generated.prefix()));
    return generated.plaintext();
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
