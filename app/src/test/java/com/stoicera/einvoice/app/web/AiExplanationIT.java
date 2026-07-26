package com.stoicera.einvoice.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.MultipartBodies;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The milestone's headline user flow, end to end: <strong>upload → report → erklären</strong>, with
 * the AI feature switched <em>on</em> and pointed at a stub provider on a loopback port.
 *
 * <p>{@code PublicWebIT} proves the flag-off half (no button, no provider client, nothing lost).
 * This proves the other half — that the flow actually produces a German explanation on the page,
 * that the PII masking really happens on the wire, and that a provider outage degrades to a notice
 * rather than an error page. Together they are the M5 Abnahme "KI abschaltbar ohne
 * Funktionsverlust", asserted from both sides.
 *
 * <p><strong>A stub provider, not a mocked bean.</strong> Overriding {@code LlmClient} with a mock
 * would skip the adapter, the HTTP call, the JSON, and the scrubbing — i.e. everything that could
 * actually be wrong between this platform and a real provider. The stub is a real HTTP server that
 * records the request body, so {@link #noPiiReachesTheProvider} can read what genuinely left the
 * JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiExplanationIT extends AbstractPostgresIT {

  private static final Path INVALID_IBAN_SAMPLE =
      Path.of("src/test/resources/fixtures/at-b2g-01-missing-order-reference.xml");

  private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

  /** The stub provider. Static so one instance serves the whole cached Spring context. */
  private static HttpServer provider;

  /** What the stub last received, so a test can assert on the bytes that really left the JVM. */
  private static final AtomicReference<String> lastRequestBody = new AtomicReference<>("");

  private static final AtomicInteger providerCalls = new AtomicInteger();

  /** Flipped by the outage test so the stub answers 503 instead of a completion. */
  private static final AtomicReference<Boolean> providerDown = new AtomicReference<>(false);

  @LocalServerPort private int port;

  @Autowired private MeterRegistry meters;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @BeforeAll
  static void startProvider() throws IOException {
    provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext("/chat/completions", AiExplanationIT::answer);
    provider.start();
  }

  @AfterAll
  static void stopProvider() {
    provider.stop(0);
  }

  @DynamicPropertySource
  static void aiProperties(DynamicPropertyRegistry registry) {
    // The feature ON, pointed at the loopback stub. A dedicated context (these properties differ
    // from
    // every other IT's), which is what keeps the flag-off assertions in PublicWebIT honest.
    registry.add("features.ai-explanations", () -> "true");
    registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    registry.add("app.ai.api-key", () -> "sk-test-key-never-logged");
    registry.add("app.ai.max-retries", () -> "0");
    registry.add("app.rate-limit.validate.capacity", () -> "1000");
    registry.add("app.rate-limit.validate.refill-per-minute", () -> "1000");
  }

  // ---------------------------------------------------------------- happy path

  @Test
  void withAiEnabledTheReportOffersAnExplainButtonPerFinding() throws Exception {
    String report = upload();

    assertThat(report).contains("Fehler erklären");
    // The button posts the finding back, because an anonymous report has no id — the hidden fields
    // are the mechanism, so their presence is part of the contract.
    assertThat(report).contains("name=\"ruleId\"").contains("name=\"messageDe\"");
  }

  @Test
  void clickingErklaerenRendersTheGermanExplanation() throws Exception {
    Session session = openValidatorPage();

    String fragment = session.explain("AT-B2G-01", "Auftragsreferenz fehlt.");

    assertThat(fragment)
        .contains("KI-Erklärung")
        .contains("Die Auftragsreferenz fehlt")
        .contains("Automatisch erzeugt und nicht geprüft");
  }

  @Test
  void theExplanationIsCachedSoASecondClickCostsNothing() throws Exception {
    Session session = openValidatorPage();
    providerCalls.set(0);

    String first =
        session.explain("BR-16", "Die Rechnung muss mindestens eine Position enthalten.");
    String second =
        session.explain("BR-16", "Die Rechnung muss mindestens eine Position enthalten.");

    assertThat(second).isEqualTo(first);
    assertThat(providerCalls).hasValue(1);
  }

  @Test
  void tokenAndCostMetricsAreRecorded() throws Exception {
    Session session = openValidatorPage();

    session.explain("BR-02", "Die Rechnung muss eine Rechnungsnummer enthalten.");

    // ENGINEERING_STANDARDS §8's "Kosten-/Token-Logging", as meters rather than as a claim.
    assertThat(meters.find("einvoice.ai.calls").counter()).isNotNull();
    assertThat(meters.find("einvoice.ai.calls").counter().count()).isPositive();
    assertThat(meters.find("einvoice.ai.tokens").counters()).isNotEmpty();
    assertThat(meters.find("einvoice.ai.cost.usd").counter()).isNotNull();
    assertThat(meters.find("einvoice.ai.cost.usd").counter().count()).isPositive();
  }

  // ------------------------------------------------------------------- privacy

  @Test
  void noPiiReachesTheProvider() throws Exception {
    Session session = openValidatorPage();

    // A finding message shaped exactly like a real Schematron diagnostic: it quotes the offending
    // document value verbatim, which is the whole reason the scrubber exists.
    session.explain(
        "AT-B2G-02",
        "IBAN AT611904300234573201 ist ungültig; Kontakt office@stoicera-software.at, UID ATU12345678");

    String sent = lastRequestBody.get();
    assertThat(sent)
        .doesNotContain("AT611904300234573201")
        .doesNotContain("office@stoicera-software.at")
        .doesNotContain("ATU12345678")
        .contains("[IBAN]")
        .contains("[E-MAIL]")
        .contains("[UID]")
        // The rule id must survive — it is what the explanation is about.
        .contains("AT-B2G-02");
  }

  @Test
  void noSamplingParameterIsSentOnTheWire() throws Exception {
    // The adapter's own test asserts this too; asserted again from the real wired app because the
    // failure mode is a 400 from the provider on every single request, and the two places it could
    // creep back in (settings, request body) are both in play here.
    Session session = openValidatorPage();

    session.explain("BR-05", "Die Rechnung muss einen Währungscode enthalten.");

    assertThat(lastRequestBody.get())
        .doesNotContain("temperature")
        .doesNotContain("top_p")
        .doesNotContain("top_k");
  }

  // ---------------------------------------------------------------- degradation

  @Test
  void aProviderOutageDegradesToTheFriendlyNoticeAndNeverA500() throws Exception {
    Session session = openValidatorPage();
    providerDown.set(true);
    try {
      String fragment = session.explain("BR-03", "Die Rechnung muss ein Rechnungsdatum enthalten.");

      assertThat(fragment).contains("Erklärung nicht verfügbar").contains("nicht betroffen");
    } finally {
      providerDown.set(false);
    }
  }

  @Test
  void aProviderOutageLeavesTheReportItselfUsable() throws Exception {
    providerDown.set(true);
    try {
      // The point of the whole degradation contract: the validator keeps working while the AI is
      // down.
      assertThat(upload()).contains("AT-B2G-01").contains("Auftragsreferenz");
    } finally {
      providerDown.set(false);
    }
  }

  // -------------------------------------------------------------------- stub

  private static void answer(HttpExchange exchange) throws IOException {
    providerCalls.incrementAndGet();
    lastRequestBody.set(
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

    String body;
    int status;
    if (providerDown.get()) {
      status = 503;
      body = "{\"error\":\"overloaded\"}";
    } else {
      status = 200;
      // Echoes the rule id back so a test can tell one answer from another, and reports a cost so
      // the
      // metrics assertions have something real to read.
      body =
          """
          {"model":"anthropic/claude-sonnet-5",
           "choices":[{"message":{"role":"assistant","content":"Die Auftragsreferenz fehlt. Ergänzen Sie sie im Feld OrderReference/OrderID."}}],
           "usage":{"prompt_tokens":210,"completion_tokens":48,"cost":0.00031}}
          """;
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  // ------------------------------------------------------------------ helpers

  private String upload() throws Exception {
    Session session = openValidatorPage();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("_csrf", session.csrfToken);
    return session
        .post(
            "/validator/pruefen",
            MultipartBodies.form(
                fields, "file", "invoice.xml", Files.readAllBytes(INVALID_IBAN_SAMPLE)))
        .body();
  }

  private Session openValidatorPage() throws Exception {
    HttpResponse<String> page =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/validator"))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = CSRF_INPUT.matcher(page.body());
    assertThat(matcher.find()).isTrue();
    String cookie =
        page.headers().firstValue("set-cookie").map(value -> value.split(";", 2)[0]).orElse("");
    return new Session(matcher.group(1), cookie);
  }

  private final class Session {

    private final String csrfToken;
    private final String cookie;

    private Session(String csrfToken, String cookie) {
      this.csrfToken = csrfToken;
      this.cookie = cookie;
    }

    /** Posts the "Erklären" form for a synthetic finding and returns the rendered fragment. */
    private String explain(String ruleId, String messageDe) throws Exception {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("_csrf", csrfToken);
      fields.put("ruleId", ruleId);
      fields.put("severity", "ERROR");
      fields.put("location", "/Invoice/OrderReference/OrderID");
      fields.put("messageDe", messageDe);
      fields.put("messageEn", "English mirror of " + ruleId);
      fields.put("sourceFormat", "ebinterface-6.1");
      fields.put("profile", "at-b2g");

      HttpResponse<String> response =
          post("/validator/erklaeren", MultipartBodies.form(fields, null, null, null));
      assertThat(response.statusCode()).isEqualTo(200);
      return response.body();
    }

    private HttpResponse<String> post(String path, MultipartBodies.Multipart body)
        throws Exception {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url(path)))
              .header("Content-Type", body.contentType())
              .POST(HttpRequest.BodyPublishers.ofByteArray(body.body()));
      if (!cookie.isBlank()) {
        request.header("Cookie", cookie);
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
