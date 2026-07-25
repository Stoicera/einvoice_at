package com.stoicera.einvoice.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base class for integration tests that need a real Keycloak issuing real JWTs. It adds a Keycloak
 * container (on top of {@link AbstractPostgresIT}'s Postgres) that imports {@code
 * keycloak/dev-realm.json}, and points the application's resource server at it via {@link
 * DynamicPropertySource}.
 *
 * <p>The container is a plain {@link GenericContainer} rather than the
 * dasniko/testcontainers-keycloak helper: only starting the image, importing a realm, exposing the
 * base URL and waiting for readiness are needed, and doing that by hand costs a few lines while
 * keeping the heavy Keycloak-admin-client / shrinkwrap transitive stack off the test classpath (see
 * docs/adr/0005). Started once as a static singleton and shared across the auth ITs; the image is
 * pinned by the same digest as {@code docker-compose.yml}.
 *
 * <p>Also raises {@code RateLimitFilter}'s per-IP capacity/refill far above their production
 * defaults for every subclass: the auth ITs collectively make a handful of anonymous {@code POST
 * /api/v1/validate} calls against the one Spring context Boot caches for all of them (identical
 * {@code @SpringBootTest} configuration → one shared context), and the point of that override is
 * only to keep those unrelated tests from ever colliding with the limit — {@code RateLimitIT}
 * proves the limit itself, in its own dedicated low-capacity context. A plain {@link
 * TestPropertySource} is used rather than a second {@code @DynamicPropertySource} method so that a
 * subclass's own {@code @DynamicPropertySource} override (as {@code RateLimitIT} declares) is
 * guaranteed to win: dynamic property sources always take precedence over
 * {@code @TestPropertySource}-loaded ones, regardless of which class in the hierarchy registers
 * them — unlike two {@code @DynamicPropertySource} methods, whose relative precedence instead
 * depends on {@code MethodIntrospector}'s class-then-superclass traversal order (superclass last,
 * so it would win the very override a subclass is trying to make).
 */
@TestPropertySource(
    properties = {
      "app.rate-limit.validate.capacity=1000",
      "app.rate-limit.validate.refill-per-minute=1000"
    })
public abstract class AbstractKeycloakIT extends AbstractPostgresIT {

  static final String REALM = "einvoice";
  static final String CLIENT_ID = "einvoice-api";
  // Dev-only secret, identical to keycloak/dev-realm.json — never a production credential.
  static final String CLIENT_SECRET = "dev-einvoice-api-secret-not-for-production";
  static final String TEST_USERNAME = "testuser";
  static final String TEST_PASSWORD = "testpass";

  // Digest-pinned to the exact image bytes docker-compose.yml runs. DockerImageName's parser
  // accepts
  // the canonical repository@digest form (but not the combined tag@digest form), so the tag is
  // omitted and the content-addressed digest alone pins the image.
  private static final DockerImageName KEYCLOAK_IMAGE =
      DockerImageName.parse(
          "quay.io/keycloak/keycloak@sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13");

  @SuppressWarnings("resource") // singleton for the whole run; the Testcontainers reaper stops it
  protected static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(KEYCLOAK_IMAGE)
          .withExposedPorts(8080)
          // Dev bootstrap admin (Keycloak 26 env names); ephemeral container, thrown away after the
          // run.
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
          // Keycloak's directory import requires the file to be named <realm>-realm.json, so the
          // repo's keycloak/dev-realm.json is placed in the container as einvoice-realm.json.
          .withCopyFileToContainer(
              MountableFile.forHostPath(realmFile()),
              "/opt/keycloak/data/import/einvoice-realm.json")
          .withCommand("start-dev", "--import-realm")
          .waitingFor(
              Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  static {
    KEYCLOAK.start();
  }

  /**
   * The realm issuer URL as seen from the host (matches the {@code iss} of tokens fetched below).
   */
  static String issuerUri() {
    return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
  }

  @DynamicPropertySource
  static void oauth2Properties(DynamicPropertyRegistry registry) {
    // Only the issuer is set; SecurityConfig derives the JWKS URL from it. This overrides the blank
    // main-config default, turning on real issuer validation against this container's tokens.
    registry.add("app.oauth2.issuer-uri", AbstractKeycloakIT::issuerUri);
  }

  /**
   * Fetches a real access token from Keycloak via the Resource Owner Password grant for the given
   * user. Returns the compact JWS string to send as {@code Authorization: Bearer ...}.
   */
  static String fetchAccessToken(String username, String password) throws Exception {
    Map<String, String> form =
        Map.of(
            "grant_type", "password",
            "client_id", CLIENT_ID,
            "client_secret", CLIENT_SECRET,
            "username", username,
            "password", password,
            "scope", "openid");
    String body =
        form.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(issuerUri() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Token request failed: HTTP " + response.statusCode() + " — " + response.body());
    }
    JsonNode json = new ObjectMapper().readTree(response.body());
    return json.get("access_token").asText();
  }

  /** Extracts the {@code sub} claim from a compact JWS without verifying it (test helper only). */
  static String subjectOf(String jwt) throws Exception {
    byte[] payload = java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
    return new ObjectMapper().readTree(payload).get("sub").asText();
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** Locates {@code keycloak/dev-realm.json} by walking up from the test working directory. */
  private static Path realmFile() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = dir; p != null; p = p.getParent()) {
      Path candidate = p.resolve("keycloak").resolve("dev-realm.json");
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("keycloak/dev-realm.json not found upward from " + dir);
  }
}
