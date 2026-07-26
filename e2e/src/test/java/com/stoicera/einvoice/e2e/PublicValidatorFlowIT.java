package com.stoicera.einvoice.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.EinvoiceApplication;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The M5 Abnahme flow, in a real browser: <strong>Upload → Report → Erklären</strong>.
 *
 * <p>MILESTONES names exactly this sequence, and until now it was covered at the HTTP level only.
 * That level is genuinely strong — it drives the real forms, harvests the real CSRF token and
 * asserts the real rendered markup — and there is precisely one thing it cannot see: whether the
 * <em>browser</em> does what the markup implies. The report fragment is swapped in by 40 lines of
 * first-party JavaScript (ADR-0009: no htmx), and no HTTP assertion can tell you that the swap
 * happens, that the target element exists, or that the explanation lands in the right place. That
 * gap is what this test closes, and it is the reason the milestone asked for a browser at all.
 *
 * <h2>How the browser reaches the application</h2>
 *
 * <p>The app runs in this JVM on a random port; Chrome runs in a container and has no route to the
 * host's loopback. {@link Testcontainers#exposeHostPorts(int...)} opens a tunnel and the container
 * reaches it as {@code host.testcontainers.internal:<same port>} — so the port number is shared and
 * only the hostname differs. That is also why the AI stub is bound on the host: only the
 * application calls it, never the browser.
 *
 * <h2>Chrome, container, no local browser</h2>
 *
 * <p>A container rather than a locally installed Chrome plus a driver on the {@code PATH}: the E2E
 * job must not depend on what a CI runner happens to have, and a version skew between browser and
 * driver is the classic way a Selenium suite goes red for no reason. The image is digest-pinned for
 * the same reason every other image in this repository is.
 */
/*
 * `classes` is named explicitly because this test lives in com.stoicera.einvoice.e2e, a SIBLING of
 * com.stoicera.einvoice.app — Spring Boot finds @SpringBootConfiguration by walking packages upward
 * from the test, and a sibling is never on that path. Without it the failure is
 * "Unable to find a @SpringBootConfiguration", which reads like a missing annotation on the
 * application rather than a package-layout consequence.
 */
@SpringBootTest(
    classes = EinvoiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class PublicValidatorFlowIT extends AbstractPostgresIT {

  /**
   * A fixed port, unusually: {@link Testcontainers#exposeHostPorts} has to be told the port before
   * the container starts, and a {@code RANDOM_PORT} is only known after the context is up. Chosen
   * high and unlikely to collide.
   */
  private static final int APP_PORT = 18080;

  private static final Path INVALID_SAMPLE =
      Path.of("../app/src/test/resources/fixtures/at-b2g-01-missing-order-reference.xml");

  /**
   * Digest-pinned, like every other image in this repository. {@code DockerImageName}'s parser
   * takes the canonical {@code repository@digest} form (not {@code tag@digest}), so the tag is
   * omitted and the content-addressed digest alone pins it — the same idiom {@code
   * AbstractKeycloakIT} uses.
   *
   * <p>{@code asCompatibleSubstituteFor} is what lets Testcontainers accept a digest reference for
   * an image whose <em>name</em> it matches against its own known-image list.
   */
  private static final DockerImageName CHROME_IMAGE =
      DockerImageName.parse(
              "selenium/standalone-chrome@sha256:4763757c927315586d6e9093e87d250d92e640f12d009e4f947c9bc5dabacc14")
          .asCompatibleSubstituteFor("selenium/standalone-chrome");

  private static HttpServer aiProvider;
  private static final AtomicInteger providerCalls = new AtomicInteger();

  @SuppressWarnings("resource") // stopped by the Testcontainers reaper at JVM exit
  private static final BrowserWebDriverContainer<?> BROWSER =
      new BrowserWebDriverContainer<>(CHROME_IMAGE).withCapabilities(chromeOptions());

  @LocalServerPort private int port;

  @Autowired private ReportRepository reports;

  private RemoteWebDriver driver;
  private WebDriverWait wait;

  @BeforeAll
  static void startEverything() throws IOException {
    aiProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    aiProvider.createContext("/chat/completions", PublicValidatorFlowIT::explain);
    aiProvider.start();

    // Before the browser starts: the tunnel has to exist for the container to be able to use it.
    Testcontainers.exposeHostPorts(APP_PORT);
    BROWSER.start();
  }

  @AfterAll
  static void stopProvider() {
    aiProvider.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> APP_PORT);
    // AI on, pointed at the loopback stub: "Erklären" is a third of the flow under test, and a real
    // provider in CI would make this test cost money and depend on someone else's uptime.
    registry.add("features.ai-explanations", () -> "true");
    registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + aiProvider.getAddress().getPort());
    registry.add("app.ai.api-key", () -> "sk-e2e-stub-key");
    registry.add("app.ai.max-retries", () -> "0");
    registry.add("app.rate-limit.validate.capacity", () -> "1000");
    registry.add("app.rate-limit.validate.refill-per-minute", () -> "1000");
  }

  @BeforeEach
  void openBrowser() {
    driver = new RemoteWebDriver(BROWSER.getSeleniumAddress(), chromeOptions());
    driver.setFileDetector(new org.openqa.selenium.remote.LocalFileDetector());
    wait = new WebDriverWait(driver, Duration.ofSeconds(30));
  }

  /**
   * <strong>Every session must be closed, or the next test cannot get one.</strong> The standalone
   * image serves one session at a time, so a driver left open does not leak quietly — it makes
   * every later test wait for a slot and fail with {@code SessionNotCreatedException} after a long
   * timeout, which reads like a broken image rather than a missing {@code quit()}. That is exactly
   * how this suite failed on its first run.
   */
  @AfterEach
  void closeBrowser() {
    if (driver != null) {
      driver.quit();
      driver = null;
    }
  }

  // ------------------------------------------------------------------ the flow

  @Test
  void aVisitorUploadsAnInvoiceReadsTheReportAndHasAFindingExplained() throws Exception {
    long reportsBefore = reports.count();

    driver.get(appUrl("/validator"));
    assertThat(driver.getTitle()).contains("E-Rechnung prüfen");

    // 1 — Upload. sendKeys on the file input is how WebDriver uploads; the file has to exist inside
    // the browser container, so a LocalFileDetector copies it there.
    WebElement file = driver.findElement(By.cssSelector("input[type=file]"));
    file.sendKeys(INVALID_SAMPLE.toAbsolutePath().toString());
    driver.findElement(By.cssSelector("form[data-swap='#report'] button[type=submit]")).click();

    // 2 — Report. The fragment is swapped INTO #report by app.js; waiting for a finding inside that
    // container is what proves the swap happened rather than a full page navigation.
    WebElement finding =
        wait.until(
            ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#report .finding")));
    assertThat(finding.getText()).contains("AT-B2G-01").contains("Auftragsreferenz");
    assertThat(driver.findElement(By.cssSelector("#report .verdict")).getText())
        .contains("nicht gültig");

    // The DSGVO promise, verified through the browser this time: the upload left no row behind.
    assertThat(reports.count()).isEqualTo(reportsBefore);

    // 3 — Erklären. Same story: the explanation is swapped into a per-finding container.
    providerCalls.set(0);
    driver.findElement(By.cssSelector("#report .finding form button[type=submit]")).click();
    WebElement explanation =
        wait.until(
            ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#report .finding .explanation")));

    // containsIgnoringCase, and the reason is worth keeping: WebDriver's getText() returns the text
    // as RENDERED, and .finding .explanation .who carries text-transform: uppercase — so the label
    // arrives as "KI-ERKLÄRUNG". A case-sensitive assertion here fails on the stylesheet rather
    // than
    // on the behaviour. That the case differs at all is itself evidence app.css was applied.
    assertThat(explanation.getText())
        .containsIgnoringCase("KI-Erklärung")
        .contains("Auftragsreferenz")
        // The disclaimer is not decoration: an AI-generated explanation of a tax rule has to say
        // that it is AI-generated and unchecked.
        .containsIgnoringCase("Automatisch erzeugt und nicht geprüft");
    assertThat(providerCalls.get()).isPositive();
  }

  @Test
  void theLandingPageRendersWithoutAnyConsoleError() {
    // Cheap and worth having: a page that renders but logs a 404 for its own stylesheet or favicon
    // looks fine in a screenshot and is broken. This is how the missing favicon was found.
    driver.get(appUrl("/"));

    assertThat(driver.getTitle()).contains("E-Rechnung");
    var severe =
        driver.manage().logs().get(org.openqa.selenium.logging.LogType.BROWSER).getAll().stream()
            .filter(
                entry -> entry.getLevel().intValue() >= java.util.logging.Level.SEVERE.intValue())
            .map(entry -> entry.getMessage())
            .toList();
    assertThat(severe).as("severe browser console entries on the landing page").isEmpty();
  }

  @Test
  void theValidatorPageWorksWithJavaScriptDisabled() {
    // ADR-0009's claim that every page works without JavaScript, asserted rather than asserted-in-
    // prose: with scripting off the form posts normally and the fragment arrives as the whole
    // response, which is why fragments/report.html carries its own heading.
    //
    // The @BeforeEach session is released first: this image serves one at a time, so holding two
    // would deadlock this test against itself.
    closeBrowser();
    RemoteWebDriver noJs =
        new RemoteWebDriver(BROWSER.getSeleniumAddress(), chromeOptionsWithoutJavaScript());
    try {
      noJs.setFileDetector(new org.openqa.selenium.remote.LocalFileDetector());
      noJs.get(appUrl("/validator"));
      noJs.findElement(By.cssSelector("input[type=file]"))
          .sendKeys(INVALID_SAMPLE.toAbsolutePath().toString());
      noJs.findElement(By.cssSelector("form[data-swap='#report'] button[type=submit]")).click();

      new WebDriverWait(noJs, Duration.ofSeconds(30))
          .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".finding")));
      assertThat(noJs.findElement(By.tagName("body")).getText())
          .contains("AT-B2G-01")
          .contains("Auftragsreferenz");
    } finally {
      noJs.quit();
    }
  }

  // ---------------------------------------------------------------- the AI stub

  private static void explain(HttpExchange exchange) throws IOException {
    providerCalls.incrementAndGet();
    exchange.getRequestBody().readAllBytes();
    byte[] body =
        """
        {"model":"anthropic/claude-sonnet-5",
         "choices":[{"message":{"role":"assistant","content":"Die Auftragsreferenz fehlt. Ergänzen Sie sie im Feld OrderReference/OrderID."}}],
         "usage":{"prompt_tokens":210,"completion_tokens":48,"cost":0.00031}}
        """
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  // ------------------------------------------------------------------ helpers

  private static ChromeOptions chromeOptions() {
    ChromeOptions options = new ChromeOptions();
    // Container defaults that matter in CI: no /dev/shm to run out of, and a window big enough that
    // the responsive layout is the desktop one.
    options.addArguments("--disable-dev-shm-usage", "--window-size=1280,900");
    options.setCapability(
        "goog:loggingPrefs", java.util.Map.of("browser", java.util.logging.Level.ALL.getName()));
    return options;
  }

  private static ChromeOptions chromeOptionsWithoutJavaScript() {
    ChromeOptions options = chromeOptions();
    options.setExperimentalOption(
        "prefs", java.util.Map.of("profile.managed_default_content_settings.javascript", 2));
    return options;
  }

  /** The app as the BROWSER sees it: same port, host-tunnel hostname. */
  private String appUrl(String path) {
    return "http://host.testcontainers.internal:" + port + path;
  }

  static {
    // Fail early and clearly if the fixture path assumption breaks, rather than in a WebDriver
    // call.
    if (!Files.exists(INVALID_SAMPLE)) {
      throw new IllegalStateException(
          "E2E fixture not found at "
              + INVALID_SAMPLE.toAbsolutePath()
              + "; it is read from the"
              + " app module's test resources, so this module must be built from the reactor root");
    }
  }
}
