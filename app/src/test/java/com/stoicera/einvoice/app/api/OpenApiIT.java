package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * springdoc's generated OpenAPI document and Swagger UI, reachable anonymously — {@link
 * com.stoicera.einvoice.app.security.SecurityConfig} permits {@code /v3/api-docs/**} and {@code
 * /swagger-ui/**} — the M3 Abnahme criterion "OpenAPI-UI nutzbar" (docs/MILESTONES.md).
 *
 * <p>Extends {@link AbstractPostgresIT} rather than {@link
 * com.stoicera.einvoice.app.security.AbstractKeycloakIT}: both assertions are anonymous, so no
 * Keycloak container is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiIT extends AbstractPostgresIT {

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  /**
   * The other direction of {@code OpenApiDisabledIT.thePublicPagesStopAdvertisingTheApiDocs}. The
   * links are conditional now, and the condition fails closed — an absent {@code apiDocsEnabled}
   * attribute hides the link. Without this test, breaking the wiring would silently remove the API
   * link from every page and the disabled-side test would still be green, so the pair has to exist
   * or the fix rots into a permanent omission.
   */
  @Test
  void thePublicPagesLinkToTheApiDocs() throws Exception {
    assertThat(get("/").body())
        .as("with the docs enabled the landing page offers them")
        .contains("/swagger-ui.html");
    assertThat(get("/validator").body())
        .as("with the docs enabled the validator page offers them")
        .contains("/swagger-ui.html");
  }

  @Test
  void apiDocsIsReachableAnonymouslyAndDescribesTheApi() throws Exception {
    HttpResponse<String> response = get("/v3/api-docs");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/json");

    JsonNode doc = json.readTree(response.body());
    assertThat(doc.get("info").get("title").asText()).isEqualTo("einvoice-at API");

    JsonNode paths = doc.get("paths");
    assertThat(paths.has("/api/v1/invoices")).isTrue();
    assertThat(paths.has("/api/v1/validate")).isTrue();
    assertThat(paths.has("/api/v1/reports")).isTrue();
    assertThat(paths.has("/api/v1/api-keys")).isTrue();

    JsonNode schemes = doc.get("components").get("securitySchemes");
    assertThat(schemes.has("bearerAuth")).isTrue();
    assertThat(schemes.has("apiKeyAuth")).isTrue();

    // OpenApiConfig#einvoiceOpenApi pins bearerAuth and apiKeyAuth as two top-level alternative
    // security requirements: every operation inherits "bearer OR apiKey" unless it declares its
    // own override. Without this assertion, deleting both addSecurityItem(...) calls would make
    // the generated doc claim every endpoint is public and this suite would still pass.
    JsonNode globalSecurity = doc.get("security");
    assertThat(globalSecurity).isNotNull();
    assertThat(globalSecurity.isArray()).isTrue();
    assertThat(globalSecurity).hasSize(2);
    Set<String> globalSecuritySchemes = new HashSet<>();
    globalSecurity.forEach(entry -> globalSecuritySchemes.add(entry.fieldNames().next()));
    assertThat(globalSecuritySchemes).containsExactlyInAnyOrder("bearerAuth", "apiKeyAuth");

    // POST /api/v1/validate is the one public endpoint (SecurityConfig permitAll): its operation
    // must explicitly override the global bearerAuth/apiKeyAuth requirement with an empty
    // security array, or the generated doc would wrongly claim it needs a credential.
    JsonNode validatePost = paths.get("/api/v1/validate").get("post");
    assertThat(validatePost.get("security")).isNotNull();
    assertThat(validatePost.get("security").isEmpty()).isTrue();

    // Invoice creation actually answers 201 with a Location header (InvoiceController#create),
    // not the framework's inferred default 200.
    JsonNode invoicesPost = paths.get("/api/v1/invoices").get("post");
    assertThat(invoicesPost.get("responses").has("201")).isTrue();

    // POST /api/v1/invoices is protected: it must have no operation-level security override, so
    // it inherits the global bearerAuth/apiKeyAuth requirement asserted above rather than
    // silently becoming public.
    assertThat(invoicesPost.get("security")).isNull();

    // Declaring error @ApiResponses on a method that has no @ResponseStatus silently drops
    // springdoc's auto-derived 200 unless it is declared explicitly alongside them — every GET
    // and the 200-answering POST /api/v1/validate must still document their success response.
    assertThat(paths.get("/api/v1/validate").get("post").get("responses").has("200")).isTrue();
    assertThat(paths.get("/api/v1/invoices").get("get").get("responses").has("200")).isTrue();
    assertThat(paths.get("/api/v1/reports").get("get").get("responses").has("200")).isTrue();
    assertThat(paths.get("/api/v1/api-keys").get("get").get("responses").has("200")).isTrue();

    // Every problem+json response referenced below shares one Spring ProblemDetail schema rather
    // than a hand-written one per endpoint.
    assertThat(doc.get("components").get("schemas").has("ProblemDetail")).isTrue();
    JsonNode invoiceCreateResponses = paths.get("/api/v1/invoices").get("post").get("responses");
    assertThat(
            invoiceCreateResponses
                .get("409")
                .get("content")
                .get("application/problem+json")
                .get("schema")
                .get("$ref")
                .asText())
        .isEqualTo("#/components/schemas/ProblemDetail");

    JsonNode reportGetResponses = paths.get("/api/v1/reports/{id}").get("get").get("responses");
    assertThat(reportGetResponses.has("404")).isTrue();
  }

  @Test
  void swaggerUiIsReachableAnonymously() throws Exception {
    HttpResponse<String> response = get("/swagger-ui/index.html");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
