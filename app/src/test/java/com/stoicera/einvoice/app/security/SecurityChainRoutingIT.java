package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.MultipartBodies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The two-chain split of ADR-0009, asserted rather than trusted.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@code SecurityConfig}'s own Javadoc calls the chain order <strong>load-bearing</strong> and
 * says this class asserts it. Until the M5 hostile review (finding F2) it did not: the sentence
 * naming this class was the only occurrence of the name anywhere in the repository, and the
 * property it described — that a path outside {@code /api/**} falls through to the browser chain
 * and answers a login redirect where a {@code 401} was meant — was covered by nothing. A claim of
 * test coverage that no test backs is worse than an acknowledged gap, because nobody goes looking
 * for it.
 *
 * <h2>What is asserted, and why it needs its own context</h2>
 *
 * <p>{@code SecurityConfig} names exactly three deliberate differences between the chains. Each
 * gets an assertion here, and two of them were previously untested anywhere:
 *
 * <ol>
 *   <li><strong>Entry point.</strong> A missing credential is a {@code 401} on {@code /api/**} and
 *       a redirect into the login on the browser surface. Both halves need to be observable in
 *       <em>one</em> context, which is why an OAuth2 client registration is configured below —
 *       without one the browser chain has no entry point and answers {@code 403}, and the contrast
 *       that is the whole point of the split cannot be seen.
 *   <li><strong>Session.</strong> The API chain is {@code STATELESS} and must set no session
 *       cookie, even on the error path; the browser chain creates one, because {@code oauth2Login}
 *       and the CSRF token have nowhere else to live.
 *   <li><strong>CSRF.</strong> Enforced on the browser chain, off on the API chain. Asserted as a
 *       <em>contrast</em> in one test, because either half alone is just a status code.
 * </ol>
 *
 * <p>The endpoints point at {@code idp.invalid} for the same reason {@code OAuth2ClientWiringIT}
 * does: nothing here completes a login, and an unreachable host proves no discovery call is made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "spring.security.oauth2.client.registration.keycloak.client-id=einvoice-web",
      "spring.security.oauth2.client.registration.keycloak.client-secret=dev-secret-not-a-credential",
      "spring.security.oauth2.client.registration.keycloak.provider=keycloak",
      "spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code",
      "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
      "spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email",
      "spring.security.oauth2.client.provider.keycloak.authorization-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/auth",
      "spring.security.oauth2.client.provider.keycloak.token-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/token",
      "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/certs",
      "spring.security.oauth2.client.provider.keycloak.user-info-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/userinfo",
      "spring.security.oauth2.client.provider.keycloak.user-name-attribute=preferred_username"
    })
class SecurityChainRoutingIT extends AbstractPostgresIT {

  private static final Path SAMPLE =
      Path.of("src/test/resources/fixtures/invoice-b2g-sample.ebinterface.xml");

  @LocalServerPort private int port;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  // ------------------------------------------------------------- 1. the entry point differs

  @Test
  void anApiPathAnswersUnauthorizedAndNeverRedirectsToALogin() throws Exception {
    // The failure this guards: a new API route placed outside /api/** would fall through to the
    // browser chain and hand a machine client an HTML login page with a 302, which every HTTP
    // client in the world would follow and then fail to parse.
    for (String apiPath : List.of("/api/v1/invoices", "/api/v1/reports", "/api/v1/tenant")) {
      HttpResponse<String> response = get(apiPath);

      assertThat(response.statusCode()).as("%s must answer as an API", apiPath).isEqualTo(401);
      assertThat(response.headers().firstValue("Location"))
          .as("%s must not redirect to a login", apiPath)
          .isEmpty();
    }
  }

  @Test
  void aBrowserPathRedirectsIntoTheLoginRatherThanAnsweringUnauthorized() throws Exception {
    HttpResponse<String> response = get("/app/rechnungen");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .get()
        .asString()
        .contains("/oauth2/authorization/keycloak");
  }

  @Test
  void theApiChainKeepsItsPublicAndProbePathsWhenALoginExists() throws Exception {
    // Everything in SecurityConfig.API_PATHS belongs to the stateless chain, and adding a browser
    // login must not quietly move any of it behind that login. Each of these is an Abnahme item
    // from an earlier milestone.
    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    assertThat(get("/v3/api-docs").statusCode()).isEqualTo(200);
    assertThat(get("/").statusCode()).isEqualTo(200);
    assertThat(get("/validator").statusCode()).isEqualTo(200);
  }

  // ----------------------------------------------------------------- 2. the session differs

  @Test
  void theApiChainCreatesNoSessionEvenOnItsErrorPath() throws Exception {
    // SessionCreationPolicy.STATELESS as an observable fact rather than a configuration line. The
    // error path is the one to check: it is where a framework is most likely to create a session
    // nobody asked for, to carry a saved request or an error attribute.
    assertThat(sessionCookies(get("/api/v1/invoices"))).isEmpty();
    assertThat(sessionCookies(get("/api/v1/validate"))).isEmpty();
  }

  @Test
  void theBrowserChainDoesCreateASession() throws Exception {
    // The contrast, and not a nice-to-have: the authorization-code flow and the CSRF token both
    // need somewhere to live, so a browser page that set no cookie would mean the login cannot
    // work.
    assertThat(sessionCookies(get("/validator"))).isNotEmpty();
  }

  // -------------------------------------------------------------------- 3. CSRF differs

  @Test
  void csrfIsEnforcedOnTheBrowserChainAndNotOnTheApiChain() throws Exception {
    // Deliberately one test rather than two: each half alone is just a status code, and it is the
    // DIFFERENCE that ADR-0009 argues for. A regression that switched CSRF on for /api/** would
    // break every API client; one that switched it off for the browser would violate
    // ENGINEERING_STANDARDS §4. Both show up here.
    MultipartBodies.Multipart upload =
        MultipartBodies.singleFilePart("file", "invoice.xml", Files.readAllBytes(SAMPLE));

    HttpResponse<String> api = post("/api/v1/validate", upload);
    assertThat(api.statusCode()).as("the API chain does not enforce CSRF").isEqualTo(200);

    HttpResponse<String> browser = post("/validator/pruefen", upload);
    assertThat(browser.statusCode()).as("the browser chain does enforce CSRF").isEqualTo(403);
  }

  // ------------------------------------------------------------------------ helpers

  private static List<String> sessionCookies(HttpResponse<String> response) {
    return response.headers().allValues("set-cookie").stream()
        .filter(value -> value.startsWith("JSESSIONID"))
        .toList();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, MultipartBodies.Multipart body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", body.contentType())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.body()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
