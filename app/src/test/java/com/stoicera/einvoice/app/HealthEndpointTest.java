package com.stoicera.einvoice.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

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
