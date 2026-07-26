package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.MultipartBodies;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
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
 * <p>Two claims here that the dashboard's {@code TenantDeletionIT} cannot make, because they are
 * about a credential rather than a session:
 *
 * <ul>
 *   <li>the erasure is scoped by the credential — there is no tenant id in the request, so this
 *       endpoint has no way to be aimed at anyone else;
 *   <li><strong>the API key used for the call is erased by the call.</strong> The next request with
 *       it is a {@code 401}. That is the correct behaviour and the kind of thing that is only
 *       obvious once someone has tried it.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantErasureApiIT extends AbstractPostgresIT {

  private static final String TENANT = "/api/v1/tenant";

  @LocalServerPort private int port;

  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void erasingAnsweredWithNoContentAndRemovesTheTenant() throws Exception {
    TenantEntity tenant = tenant("api-erase");
    String key = apiKey(tenant);
    validate(key); // gives the tenant a report row and an audit event to erase

    HttpResponse<String> response = delete(key);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(tenants.findById(tenant.getId())).isEmpty();
  }

  @Test
  void theKeyUsedForTheCallNoLongerAuthenticates() throws Exception {
    TenantEntity tenant = tenant("api-erase-key");
    String key = apiKey(tenant);

    assertThat(delete(key).statusCode()).isEqualTo(204);

    // 401, not 404: the credential is gone, so the request never reaches a route that could 404.
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
    // The second call presents an erased key, so it is refused by authentication rather than by the
    // controller — which is the honest outcome, and it is not a 500.
    TenantEntity tenant = tenant("api-erase-twice");
    String key = apiKey(tenant);

    assertThat(delete(key).statusCode()).isEqualTo(204);
    assertThat(delete(key).statusCode()).isEqualTo(401);
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
  void erasingOneTenantLeavesAnothersCredentialWorking() throws Exception {
    TenantEntity mine = tenant("api-erase-mine");
    String myKey = apiKey(mine);
    TenantEntity theirs = tenant("api-erase-theirs");
    String theirKey = apiKey(theirs);

    assertThat(delete(myKey).statusCode()).isEqualTo(204);

    HttpResponse<String> theirs2 =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/invoices")))
                .header("X-Api-Key", theirKey)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(theirs2.statusCode()).isEqualTo(200);
    assertThat(tenants.findById(theirs.getId())).isPresent();
  }

  // ------------------------------------------------------------------- helpers

  private HttpResponse<String> delete(String key) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(TENANT))).header("X-Api-Key", key).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void validate(String key) throws Exception {
    byte[] fixture;
    try (var in =
        TenantErasureApiIT.class.getResourceAsStream(
            "/fixtures/at-b2g-01-missing-order-reference.xml")) {
      fixture = in.readAllBytes();
    }
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fixture);
    http.send(
        HttpRequest.newBuilder(URI.create(url("/api/v1/validate")))
            .header("Content-Type", multipart.contentType())
            .header("X-Api-Key", key)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
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
