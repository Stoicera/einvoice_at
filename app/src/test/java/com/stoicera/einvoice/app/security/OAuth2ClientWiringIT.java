package com.stoicera.einvoice.app.security;

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
 * The browser-login wiring, in the shape the compose stack actually uses: an OAuth2 client whose
 * endpoints are listed <strong>explicitly</strong>, with no provider {@code issuer-uri} and
 * therefore no OIDC discovery.
 *
 * <h2>The bug this exists to prevent</h2>
 *
 * <p>M5 shipped a {@code docker-compose.yml} that set {@code
 * spring.security.oauth2.client.provider.keycloak.issuer-uri} <em>alongside</em> the four explicit
 * endpoint URLs, which reads like completeness and is not. Spring Boot treats a provider {@code
 * issuer-uri} as a request to perform discovery at startup ({@code
 * ClientRegistrations.fromIssuerLocation}), so the application fetched {@code
 * <issuer>/.well-known/openid-configuration} while building the bean. The issuer had to be the
 * browser-facing {@code localhost:8081}, and inside the container that is the container's own
 * loopback — connection refused, {@code clientRegistrationRepository} failed, and <strong>the whole
 * application refused to start</strong>. Not the login: everything, public validator included. The
 * stack could not boot at all, and nothing in the test suite noticed, because every other context
 * in this module deliberately configures no OAuth2 client.
 *
 * <p>So this test configures one. It asserts the two things that were broken:
 *
 * <ul>
 *   <li><strong>the context starts</strong> — which it cannot do if a discovery call is attempted,
 *       because the endpoints below point at a host that does not resolve;
 *   <li><strong>the login entry point works</strong> — {@code /app} redirects into {@code
 *       /oauth2/authorization/keycloak}, and that redirects to the browser-facing authorization
 *       URL.
 * </ul>
 *
 * <p>The endpoints are deliberately unreachable. That is the whole mechanism: a build that starts
 * is proof that no network call was made, and a build that regresses to discovery fails here
 * instead of in someone's {@code docker compose up}. Nothing in this test completes a login, so the
 * token, JWKS and userinfo URLs are never contacted.
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
      // The four explicit endpoints, and NO provider issuer-uri. Host chosen so that any attempt to
      // resolve it fails: .invalid is reserved by RFC 2606 for exactly this.
      "spring.security.oauth2.client.provider.keycloak.authorization-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/auth",
      "spring.security.oauth2.client.provider.keycloak.token-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/token",
      "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/certs",
      "spring.security.oauth2.client.provider.keycloak.user-info-uri=http://idp.invalid/realms/einvoice/protocol/openid-connect/userinfo",
      "spring.security.oauth2.client.provider.keycloak.user-name-attribute=preferred_username"
    })
class OAuth2ClientWiringIT extends AbstractPostgresIT {

  @LocalServerPort private int port;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @Test
  void theContextStartsWithAnOAuth2ClientConfiguredWithoutDiscovery() {
    // Reaching this line at all is the assertion: a discovery attempt against idp.invalid would
    // have
    // failed bean creation and no test in this class would run.
    assertThat(port).isPositive();
  }

  @Test
  void thePublicPagesStayPublicWhenALoginExists() throws Exception {
    // Adding a login must not put one in front of the lead magnet.
    assertThat(get("/").statusCode()).isEqualTo(200);
    assertThat(get("/validator").statusCode()).isEqualTo(200);
  }

  @Test
  void theDashboardRedirectsIntoTheLoginRatherThanRefusing() throws Exception {
    // With a client registration present the web chain has an entry point, so the answer changes
    // from
    // 403 (every other context in this module) to a redirect. Both are "not readable"; this one is
    // the
    // shape a real deployment has.
    HttpResponse<String> response = get("/app");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .get()
        .asString()
        .contains("/oauth2/authorization/keycloak");
  }

  @Test
  void theAuthorizationRedirectPointsAtTheBrowserFacingAuthorizationUrl() throws Exception {
    // The second hop, and the reason the endpoints are listed explicitly at all: the BROWSER has to
    // be
    // sent somewhere it can reach, which is a different host from the one the application uses for
    // the
    // back channel. PKCE is expected too — Spring Security sends it for a confidential client since
    // 6.x
    // and it costs nothing to pin.
    HttpResponse<String> response = get("/oauth2/authorization/keycloak");

    assertThat(response.statusCode()).isEqualTo(302);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location)
        .startsWith("http://idp.invalid/realms/einvoice/protocol/openid-connect/auth")
        .contains("client_id=einvoice-web")
        .contains("response_type=code")
        .contains("code_challenge");
  }

  @Test
  void theApiChainStillAnswersAsAnApi() throws Exception {
    // ADR-0009's load-bearing detail, re-asserted in the one context that has a login: /api/** must
    // still answer 401 rather than redirecting a machine client to a login page.
    assertThat(get("/api/v1/invoices").statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
