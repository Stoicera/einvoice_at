package com.stoicera.einvoice.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.EinvoiceApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A <strong>real OAuth2 authorization-code login</strong>, driven through Keycloak's own form in a
 * real browser — the gap M5 recorded by name and handed to M6:
 *
 * <blockquote>
 *
 * "No automated test completes a real authorization-code login. The dashboard ITs inject the
 * authentication a finished flow would have produced … What is missing is a browser driving
 * Keycloak's login form."
 *
 * </blockquote>
 *
 * <p>The dashboard ITs use {@code oauth2Login()} to inject the finished authentication, which is
 * the right tool for asserting what a page renders and cannot see any of this: that the {@code
 * /app} redirect really lands on Keycloak, that PKCE and the code exchange work against a real
 * issuer, that the id_token's claims map to a tenant, and that the session cookie the browser is
 * left holding actually opens the dashboard.
 *
 * <h2>The dual-URL problem, which is the whole reason this was deferred</h2>
 *
 * <p>Three parties have to agree, and two of them see different networks:
 *
 * <ul>
 *   <li>The <strong>browser</strong> runs in a container. It must be sent to a Keycloak URL it can
 *       reach — {@code host.testcontainers.internal:18081}, the Testcontainers tunnel back to the
 *       host, where Keycloak's port is published.
 *   <li>The <strong>application</strong> runs in this JVM, on the host. It exchanges the code and
 *       fetches keys over {@code localhost:18081} — the same container, a name the browser cannot
 *       use.
 *   <li>The <strong>issuer</strong> stamped into the id_token must be ONE value on both channels,
 *       or the token minted over the back channel does not match what the front channel promised.
 * </ul>
 *
 * <p>{@code KC_HOSTNAME} pinned to the browser-facing URL plus {@code
 * KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true} is what makes that true — exactly the fix {@code
 * docker-compose.yml} carries for the same reason, so this test also guards that configuration from
 * regressing.
 *
 * <p>The realm is the committed {@code keycloak/dev-realm.json} with <em>one</em> field rewritten
 * in a temporary copy: the {@code einvoice-web} client's redirect URIs, which name {@code
 * localhost:8080} for the compose stack and cannot name a port this test only learns about here.
 * The committed realm stays clean; a test-only redirect URI in it would be a permanent oddity, and
 * rewriting the whole file would hide what actually differs.
 */
@SpringBootTest(
    classes = EinvoiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class KeycloakLoginFlowIT extends AbstractPostgresIT {

  /**
   * A fixed port, for {@link PublicValidatorFlowIT}'s reason — {@link
   * Testcontainers#exposeHostPorts} has to be told the port before the browser container starts.
   *
   * <p>Deliberately NOT 18080. Both classes run in one Failsafe JVM and Spring caches both contexts
   * for its whole life, so the first application never releases its port; sharing one would make
   * the second context fail to bind, in a way that reads like a flaky container rather than two
   * tests asking for the same socket.
   */
  private static final int APP_PORT = 18082;

  /** Keycloak's published host port, fixed for the same reason and distinct for the same reason. */
  private static final int KEYCLOAK_PORT = 18081;

  private static final String REALM = "einvoice";
  private static final String CLIENT_ID = "einvoice-web";
  // Dev-only, identical to keycloak/dev-realm.json — never a production credential.
  private static final String CLIENT_SECRET = "dev-einvoice-web-secret-not-for-production";
  private static final String TEST_USERNAME = "testuser";
  private static final String TEST_PASSWORD = "testpass";

  /** What the BROWSER must be sent to, and therefore what the issuer has to be. */
  private static final String BROWSER_FACING_KEYCLOAK =
      "http://host.testcontainers.internal:" + KEYCLOAK_PORT;

  /** What the APPLICATION talks to over the back channel. */
  private static final String APP_FACING_KEYCLOAK = "http://localhost:" + KEYCLOAK_PORT;

  private static final DockerImageName KEYCLOAK_IMAGE =
      DockerImageName.parse(
          "quay.io/keycloak/keycloak@sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13");

  private static final DockerImageName CHROME_IMAGE =
      DockerImageName.parse(
              "selenium/standalone-chrome@sha256:4763757c927315586d6e9093e87d250d92e640f12d009e4f947c9bc5dabacc14")
          .asCompatibleSubstituteFor("selenium/standalone-chrome");

  @SuppressWarnings("resource") // stopped by the Testcontainers reaper at JVM exit
  private static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(KEYCLOAK_IMAGE)
          .withExposedPorts(8080)
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
          // The two settings that make `iss` one value on both channels. Without KC_HOSTNAME,
          // start-dev derives every URL — the issuer claim included — from the incoming request's
          // Host header, so this realm would call itself two different things to the two parties.
          .withEnv("KC_HOSTNAME", BROWSER_FACING_KEYCLOAK)
          .withEnv("KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "true")
          // MODE 0644, EXPLICITLY. `Files.createTempFile` creates a file readable only by its owner
          // (0600), and `withCopyFileToContainer` preserves the mode — so Keycloak, which runs as a
          // different uid inside the container, could not read its own realm and died with
          // "einvoice-realm.json (Permission denied)" reported only as "container exited with code
          // 1", three minutes into the run.
          //
          // This passed locally and failed on the CI runner, because whether the uids happen to
          // line up is a property of the machine. AbstractKeycloakIT gets away with the one-arg
          // form: it mounts a file from the repository, which is already world-readable. A temp
          // file is not, and that difference is invisible at the call site unless it is stated.
          .withCopyFileToContainer(
              MountableFile.forHostPath(realmWithE2eRedirectUri(), 0644),
              "/opt/keycloak/data/import/einvoice-realm.json")
          .withCommand("start-dev", "--import-realm")
          .waitingFor(
              Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  @SuppressWarnings("resource")
  private static final BrowserWebDriverContainer<?> BROWSER =
      new BrowserWebDriverContainer<>(CHROME_IMAGE).withCapabilities(chromeOptions());

  private RemoteWebDriver driver;
  private WebDriverWait wait;

  @BeforeAll
  static void startContainers() {
    // A FIXED host port, not a mapped one: KC_HOSTNAME has to name the port before Keycloak starts,
    // and a mapped port is only known afterwards. This is the same chicken-and-egg the application
    // port has, solved the same way.
    KEYCLOAK.setPortBindings(List.of(KEYCLOAK_PORT + ":8080"));
    KEYCLOAK.start();

    // Both host ports must exist as tunnels before the browser starts: the application's, so the
    // browser can load the dashboard, and Keycloak's published port, so the browser can load the
    // login form.
    Testcontainers.exposeHostPorts(APP_PORT, KEYCLOAK_PORT);
    BROWSER.start();
  }

  @DynamicPropertySource
  static void oauth2Client(DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> APP_PORT);

    String base = "spring.security.oauth2.client.";
    registry.add(base + "registration.keycloak.client-id", () -> CLIENT_ID);
    registry.add(base + "registration.keycloak.client-secret", () -> CLIENT_SECRET);
    registry.add(base + "registration.keycloak.provider", () -> "keycloak");
    registry.add(
        base + "registration.keycloak.authorization-grant-type", () -> "authorization_code");
    registry.add(
        base + "registration.keycloak.redirect-uri",
        () -> "{baseUrl}/login/oauth2/code/{registrationId}");
    registry.add(base + "registration.keycloak.scope", () -> "openid,profile,email");

    // THE SPLIT. Only the authorization endpoint is browser-facing — it is the one URL the
    // application merely REDIRECTS to. Everything else it fetches itself, over the back channel.
    //
    // And no `provider.keycloak.issuer-uri`, for the reason docker-compose.yml spells out at
    // length: Boot reads it as a request to perform OIDC discovery at startup, and a discovery
    // failure fails bean creation and therefore the entire application.
    registry.add(
        base + "provider.keycloak.authorization-uri",
        () -> BROWSER_FACING_KEYCLOAK + "/realms/" + REALM + "/protocol/openid-connect/auth");
    registry.add(
        base + "provider.keycloak.token-uri",
        () -> APP_FACING_KEYCLOAK + "/realms/" + REALM + "/protocol/openid-connect/token");
    registry.add(
        base + "provider.keycloak.jwk-set-uri",
        () -> APP_FACING_KEYCLOAK + "/realms/" + REALM + "/protocol/openid-connect/certs");
    registry.add(
        base + "provider.keycloak.user-info-uri",
        () -> APP_FACING_KEYCLOAK + "/realms/" + REALM + "/protocol/openid-connect/userinfo");
    registry.add(base + "provider.keycloak.user-name-attribute", () -> "preferred_username");
  }

  @BeforeEach
  void openBrowser() {
    driver = new RemoteWebDriver(BROWSER.getSeleniumAddress(), chromeOptions());
    wait = new WebDriverWait(driver, Duration.ofSeconds(30));
  }

  /** One session at a time in this image; see {@link PublicValidatorFlowIT}. */
  @AfterEach
  void closeBrowser() {
    if (driver != null) {
      driver.quit();
      driver = null;
    }
  }

  @Test
  @DisplayName("a visitor logs in through Keycloak's own form and lands on the dashboard")
  void completesTheAuthorizationCodeFlow() {
    driver.get(appUrl("/app"));

    // 1 — The application redirected the browser to Keycloak, not to a login page of its own.
    wait.until(
        ExpectedConditions.urlContains("/realms/" + REALM + "/protocol/openid-connect/auth"));
    assertThat(driver.getCurrentUrl())
        .as("the authorization endpoint must be the BROWSER-facing URL")
        .startsWith(BROWSER_FACING_KEYCLOAK);
    // PKCE, asserted from the browser's own address bar: Spring Security sends a code challenge for
    // a confidential client too, and its absence would be a silent downgrade.
    assertThat(driver.getCurrentUrl())
        .contains("code_challenge=")
        .contains("code_challenge_method=S256");

    // 2 — Keycloak's real login form, filled in and submitted.
    wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("username")))
        .sendKeys(TEST_USERNAME);
    driver.findElement(By.id("password")).sendKeys(TEST_PASSWORD);
    driver.findElement(By.cssSelector("#kc-login, input[type=submit]")).click();

    // 3 — Back on the application, authenticated. The code exchange, the id_token validation and
    // the tenant mapping all happened in between, over the back channel, and none of them is
    // separately mockable here — which is the point.
    wait.until(ExpectedConditions.urlContains("/app"));
    assertThat(driver.getCurrentUrl()).startsWith(appUrl("/app"));
    assertThat(driver.getPageSource()).contains("Übersicht");

    // 4 — The session cookie is real: a second, independent navigation is served without another
    // trip to Keycloak.
    driver.get(appUrl("/app/rechnungen"));
    assertThat(driver.getCurrentUrl()).isEqualTo(appUrl("/app/rechnungen"));
    assertThat(driver.getPageSource())
        .as("the invoice list must render for the logged-in tenant")
        .contains("Rechnungen");
  }

  /**
   * Logging out ends <strong>this application's</strong> session — and, asserted as such,
   * <em>not</em> the Keycloak SSO session.
   *
   * <p>The first version of this test expected {@code /app} to land on Keycloak's login form
   * afterwards. It does not, and that turned out to be correct OIDC behaviour rather than a defect:
   * {@code SecurityConfig} performs a <em>local</em> logout ({@code logoutSuccessUrl("/")}), so the
   * browser still holds Keycloak's own SSO cookie. The next {@code /app} does make the round trip
   * to the authorization endpoint — Keycloak simply recognises the session and redirects straight
   * back with a code, far too fast for a URL poll to observe. The old assertion was asserting a
   * race.
   *
   * <p>Two weaker assertions were tried first and both were wrong about the mechanism rather than
   * about the application, which is worth recording because each looks obviously right:
   *
   * <ul>
   *   <li><em>"the JSESSIONID cookie is gone"</em> — it is not. Spring invalidates the session on
   *       the server; it only sends an expiring {@code Set-Cookie} if {@code deleteCookies} is
   *       configured, which it is not, so the browser keeps holding a now-meaningless identifier.
   *   <li><em>"the JSESSIONID value changed"</em> — it does not, for the same reason plus the fact
   *       that the landing page it redirects to needs no session of its own to render.
   * </ul>
   *
   * <p>What is left is the direct proof: <strong>clear the identity provider's cookies, then ask
   * for the dashboard.</strong> If the application session had survived, {@code /app} would render
   * without involving Keycloak at all, whatever the IdP's cookies say. Getting Keycloak's login
   * form instead can only mean the application no longer has an authenticated session — and it
   * isolates the application's own state from the SSO session that was silently carrying the
   * re-login.
   *
   * <p><strong>A named limit, not a hidden one.</strong> RP-initiated logout — sending the browser
   * to Keycloak's {@code end_session_endpoint} so the SSO session ends too — is what a shared
   * computer wants, and this platform does not do it. It needs that endpoint configured explicitly,
   * because this application deliberately performs no OIDC discovery, plus a post-logout redirect
   * URI in the realm. Recorded in {@code docs/worklog.md} as an open item rather than bolted on in
   * the last hour of a milestone.
   */
  @Test
  @DisplayName("logging out clears this application's session cookie")
  void logsOut() {
    logIn();
    assertThat(driver.manage().getCookieNamed("JSESSIONID"))
        .as("a logged-in browser must hold an application session")
        .isNotNull();

    driver.get(appUrl("/logout"));
    // Spring Security's logout page asks for a confirmation POST while CSRF is on, which it is on
    // the browser chain. Submitting it is the flow a user actually performs.
    driver.findElement(By.cssSelector("button[type=submit], input[type=submit]")).click();
    wait.until(ExpectedConditions.urlToBe(appUrl("/")));

    // Drop the identity provider's cookies, so nothing but this application's own session could
    // authenticate the next request. The stale JSESSIONID is deliberately left in place — it is
    // precisely the credential under test.
    driver.get(BROWSER_FACING_KEYCLOAK + "/realms/" + REALM + "/account");
    driver.manage().deleteAllCookies();

    driver.get(appUrl("/app"));
    wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("username")));
    assertThat(driver.getCurrentUrl())
        .as("with no SSO session, the invalidated application session must reach the login form")
        .startsWith(BROWSER_FACING_KEYCLOAK);
  }

  private void logIn() {
    driver.get(appUrl("/app"));
    wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("username")))
        .sendKeys(TEST_USERNAME);
    driver.findElement(By.id("password")).sendKeys(TEST_PASSWORD);
    driver.findElement(By.cssSelector("#kc-login, input[type=submit]")).click();
    wait.until(ExpectedConditions.urlContains("/app"));
  }

  // ------------------------------------------------------------------ helpers

  /**
   * The anchor for the redirect-URI rewrite below: the first entry of {@code einvoice-web}'s {@code
   * redirectUris} array, which appears exactly once in the file.
   *
   * <p>Anchoring on this line rather than on {@code "http://localhost:8080/*"} alone is not
   * fussiness. That shorter string also appears as the <em>value</em> of {@code
   * post.logout.redirect.uris}, and a blind replace inserted an array separator into the middle of
   * a plain string — producing a realm file that is not JSON at all, which Keycloak reports as
   * "container exited with code 1" three minutes into the run. That is exactly what happened here.
   */
  private static final String REDIRECT_URI_ANCHOR =
      "\"http://localhost:8080/login/oauth2/code/keycloak\",";

  /**
   * A copy of the committed dev realm whose {@code einvoice-web} client also accepts this test's
   * redirect URI, next to the compose stack's {@code localhost:8080}.
   *
   * <p>One array, extended in a temporary file. Adding a test-only redirect URI to the committed
   * realm would leave a permanent oddity in a file whose whole purpose is to describe the local
   * stack — and it cannot simply be skipped, because Keycloak refuses an authorization request
   * whose {@code redirect_uri} is not listed, with an error page rather than a login form.
   *
   * <p>{@code webOrigins} is deliberately left alone: it governs CORS, and this flow is form posts
   * and redirects, with no cross-origin fetch anywhere in it.
   */
  private static Path realmWithE2eRedirectUri() {
    try {
      Path source = Path.of("../keycloak/dev-realm.json");
      if (!Files.exists(source)) {
        throw new IllegalStateException(
            "dev realm not found at "
                + source.toAbsolutePath()
                + "; this module must be built from the reactor root");
      }
      String realm = Files.readString(source, StandardCharsets.UTF_8);
      if (!realm.contains(REDIRECT_URI_ANCHOR)) {
        throw new IllegalStateException(
            "keycloak/dev-realm.json no longer contains "
                + REDIRECT_URI_ANCHOR
                + "; update KeycloakLoginFlowIT rather than letting the login fail with an opaque"
                + " Keycloak error page");
      }
      String rewritten =
          realm.replace(
              REDIRECT_URI_ANCHOR,
              REDIRECT_URI_ANCHOR
                  + "\n        \"http://host.testcontainers.internal:"
                  + APP_PORT
                  + "/*\",");

      Path target = Files.createTempFile("einvoice-e2e-realm", ".json");
      Files.writeString(target, rewritten, StandardCharsets.UTF_8);
      target.toFile().deleteOnExit();
      return target;
    } catch (IOException e) {
      throw new IllegalStateException("could not prepare the E2E realm", e);
    }
  }

  private static ChromeOptions chromeOptions() {
    ChromeOptions options = new ChromeOptions();
    options.addArguments("--disable-dev-shm-usage", "--window-size=1280,900");
    options.setCapability("goog:loggingPrefs", Map.of("browser", "ALL"));
    return options;
  }

  /** The application as the BROWSER sees it. */
  private static String appUrl(String path) {
    return "http://host.testcontainers.internal:" + APP_PORT + path;
  }
}
