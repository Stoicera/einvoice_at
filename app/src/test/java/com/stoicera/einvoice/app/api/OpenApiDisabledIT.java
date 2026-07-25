package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The other side of {@link OpenApiIT}: with {@code API_DOCS_ENABLED=false} the OpenAPI document and
 * Swagger UI are genuinely gone, not merely unlinked.
 *
 * <p>M3 shipped both permanently exposed to anonymous callers with no way to turn them off, while
 * springdoc warned about exactly that on every context boot in the build log (M3 hostile review,
 * F8). Publishing a full API description of a B2G invoicing system deserves to be a decision; a
 * switch nobody has proven works is not one, hence this test rather than a line of documentation.
 *
 * <p>The application still starts and serves the API — turning the docs off must not turn anything
 * else off, which the health-probe assertion pins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class OpenApiDisabledIT extends AbstractPostgresIT {

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void theOpenApiDocumentIsNotServed() throws Exception {
    // 404, not 401: SecurityConfig still permits the path, so this proves the endpoint is absent
    // rather than merely protected.
    assertThat(get("/v3/api-docs").statusCode()).isEqualTo(404);
  }

  @Test
  void swaggerUiIsNotServed() throws Exception {
    assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(404);
  }

  @Test
  void theApplicationItselfKeepsWorking() throws Exception {
    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    // And a protected API route still authenticates rather than 404ing along with the docs.
    assertThat(get("/api/v1/invoices").statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
