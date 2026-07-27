package com.stoicera.einvoice.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.security.AbstractKeycloakIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code /actuator/info} answers "which build is this?" — SPEC §9's version endpoint, an open carry
 * item since M4 and closed in M6.
 *
 * <p>Asserted over real HTTP against the running application, with a real Keycloak token, because
 * every part of this can fail silently:
 *
 * <ul>
 *   <li>The commit id and build time come from files the <em>build</em> writes ({@code
 *       git.properties}, {@code META-INF/build-info.properties}). Boot omits either section without
 *       complaint when its file is missing, so forgetting a plugin binding produces an endpoint
 *       that answers {@code {}} and a SPEC claim that is quietly false.
 *   <li>The endpoint must <strong>not</strong> be anonymous. {@code SecurityConfig} permits {@code
 *       /actuator/health/**} and nothing else under {@code /actuator}; publishing the running
 *       commit of a deployment to the internet hands a reader the exact source tree to search for
 *       known bugs.
 *   <li>It must publish the build's identity and nothing about the people who produced it. The
 *       plugin's default property set includes the branch, the committer's name and e-mail address
 *       and the full commit message.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BuildInfoEndpointIT extends AbstractKeycloakIT {

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  @DisplayName("/actuator/info is refused without a credential")
  void refusesAnonymousCallers() throws Exception {
    assertThat(get(null).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("it names the commit and the build, so a deployment can be identified")
  void carriesTheCommitIdAndTheBuildTime() throws Exception {
    HttpResponse<String> response = get(fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode info = json.readTree(response.body());

    // The FULL 40-character id, not only the abbreviation: a 7-character prefix can collide, and
    // the point of this endpoint is being able to check out exactly the tree that is running.
    // `management.info.git.mode: simple` publishes the ABBREVIATION as `git.commit.id`, which is
    // what this assertion caught when the mode was first set that way.
    //
    // The nesting is Boot's, not ours: git.properties holds both `git.commit.id` and
    // `git.commit.id.abbrev`, so the binder cannot leave the first as a leaf and moves it to
    // `git.commit.id.full`. Asserted as it actually is rather than as it reads in the properties
    // file.
    assertThat(info.path("git").path("commit").path("id").path("full").asText())
        .as("git.properties reached the classpath and Boot's git contributor read it")
        .matches("[0-9a-f]{40}");
    assertThat(info.path("git").path("commit").path("id").path("abbrev").asText())
        .matches("[0-9a-f]{7,}");
    assertThat(info.path("git").path("commit").path("time").asText()).isNotBlank();

    assertThat(info.path("build").path("version").asText()).isNotBlank();
    assertThat(info.path("build").path("time").asText()).isNotBlank();
    assertThat(info.path("build").path("artifact").asText()).isEqualTo("app");
  }

  @Test
  @DisplayName("it publishes the build's identity and nothing about its authors")
  void publishesNoAuthorDetails() throws Exception {
    JsonNode info = json.readTree(get(fetchAccessToken(TEST_USERNAME, TEST_PASSWORD)).body());

    // The exact published surface. `management.info.git.mode: full` republishes whatever
    // git.properties holds, so this is really an assertion about the POM's includeOnlyProperties:
    // no branch, no committer name or e-mail address, no commit message. `env.enabled: false`
    // keeps the environment (database URL, OAuth2 settings) out of it entirely.
    assertThat(fieldNames(info.path("git"))).containsExactly("commit");
    assertThat(fieldNames(info.path("git").path("commit"))).containsExactlyInAnyOrder("id", "time");
    assertThat(fieldNames(info.path("git").path("commit").path("id")))
        .containsExactlyInAnyOrder("full", "abbrev");
    assertThat(info.path("env").isMissingNode())
        .as("the environment must never be published by /actuator/info")
        .isTrue();
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new java.util.ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private HttpResponse<String> get(String bearerToken) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/info")).GET();
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
