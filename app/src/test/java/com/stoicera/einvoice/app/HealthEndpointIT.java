package com.stoicera.einvoice.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * Web/actuator smoke test. It boots the full application context, so once the persistence layer
 * landed it needs a real datasource; rather than excluding the database auto-configuration (and
 * coupling the test to Boot-internal auto-configuration class names), it extends {@link
 * AbstractPostgresIT} and reuses the shared Testcontainers Postgres. The assertion — the health
 * endpoint reports {@code UP} — is unchanged and now covers the database health contributor too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointIT extends AbstractPostgresIT {

  @LocalServerPort private int port;

  @Test
  void healthEndpointReportsUp() {
    String body =
        RestClient.create("http://localhost:" + port)
            .get()
            .uri("/actuator/health")
            .retrieve()
            .body(String.class);

    assertThat(body).contains("\"status\":\"UP\"");
  }
}
