package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The flag-off half of {@code POST /api/v1/reports/{id}/explain}: with {@code
 * features.ai-explanations} at its shipped default of {@code false}, the endpoint answers {@code
 * 503} with a problem document rather than {@code 404}, {@code 500}, or a {@code 200} whose
 * explanations are all null.
 *
 * <p>This is the API's share of the M5 Abnahme "KI abschaltbar ohne Funktionsverlust", and it is a
 * claim {@code AiConfig}'s own Javadoc makes in prose — so it is worth an assertion. {@code 503}
 * and not {@code 404}: the route exists and is correct, the capability is absent, and an operator
 * reading the response should be able to tell those apart.
 *
 * <p>Deliberately carries <strong>no</strong> property overrides, so it shares the cached Spring
 * context of the other default-configuration web ITs instead of paying for a fifth one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExplainApiDisabledIT extends AbstractPostgresIT {

  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

  @LocalServerPort private int port;

  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void withAiDisabledExplainingAnsweredAsServiceUnavailable() throws Exception {
    String key = seedTenantWithKey();
    String reportId = validateAndStore(key);

    HttpResponse<String> response = post("/api/v1/reports/" + reportId + "/explain", key);

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "ai-explanations-disabled");
    assertThat(problem.get("status").asInt()).isEqualTo(503);
  }

  @Test
  void withAiDisabledTheRestOfTheReportsApiIsUnaffected() throws Exception {
    // "Ohne Funktionsverlust", asserted rather than assumed: the reports the explain route would
    // have decorated are still readable in full.
    String key = seedTenantWithKey();
    String reportId = validateAndStore(key);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/reports/" + reportId)))
                .header("X-Api-Key", key)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).get("findings")).isNotEmpty();
  }

  /**
   * With the feature off, <strong>every</strong> id answers 503 — including one that does not
   * exist.
   *
   * <p>Written expecting 404 first, on the reasoning that the feature switch should not mask the
   * tenant boundary. Changed deliberately after seeing it fail: the capability is a precondition of
   * the route, and a precondition is checked before its arguments. Two consequences make that the
   * better order — a disabled deployment does no database work for a route it cannot serve, and the
   * answer does not vary with whether the id happens to exist, so the response cannot be used to
   * probe for ids. Tenant isolation for this route is asserted where it can actually be exercised,
   * in {@code ExplainApiIT.aTenantCannotExplainAnotherTenantsReport}.
   */
  @Test
  void withAiDisabledEvenAnUnknownReportIdIsAnsweredAsUnavailableNotAsNotFound() throws Exception {
    String key = seedTenantWithKey();

    HttpResponse<String> response = post("/api/v1/reports/" + UUID.randomUUID() + "/explain", key);

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(json.readTree(response.body()).get("type").asText())
        .isEqualTo(PROBLEM_BASE + "ai-explanations-disabled");
  }

  // ------------------------------------------------------------------------ helpers

  private String seedTenantWithKey() {
    TenantEntity tenant =
        tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "AI Off Tenant"));
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "off-key", generated.keyHash(), generated.prefix()));
    return generated.plaintext();
  }

  private String validateAndStore(String key) throws Exception {
    byte[] fileBytes;
    try (var in =
        ExplainApiDisabledIT.class.getResourceAsStream(
            "/fixtures/at-b2g-01-missing-order-reference.xml")) {
      fileBytes = in.readAllBytes();
    }
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fileBytes);
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/validate")))
                .header("Content-Type", multipart.contentType())
                .header("X-Api-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body()).get("id").asText();
  }

  private HttpResponse<String> post(String path, String key) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("X-Api-Key", key)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
