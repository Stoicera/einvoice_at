package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.MultipartBodies;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import com.stoicera.einvoice.app.security.ApiKeys;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code POST /api/v1/reports/{id}/explain} — the REST counterpart of the UI's explain route (SPEC
 * §4), with the feature switched <em>on</em> against a stub provider.
 *
 * <p>Where the UI route posts a finding back to itself because an anonymous report has no id, this
 * endpoint works the other way round: it takes a <strong>stored</strong> report id and reads the
 * findings from the row, so the caller cannot choose the text to be explained. That difference is
 * the whole reason both exist, and it is what makes this the endpoint that can attach the invoice's
 * party names as literals to redact — the public page has no invoice to read them from.
 *
 * <p>Authenticated with an {@code X-Api-Key} rather than a real Keycloak login: the endpoint's
 * authorization rule is {@code anyRequest().authenticated()} on the API chain, which an API key
 * satisfies, so seeding a tenant and a key directly keeps this off {@code AbstractKeycloakIT}'s
 * heavier container. {@code ExplainApiDisabledIT} owns the flag-off half of the contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExplainApiIT extends AbstractPostgresIT {

  private static final String REPORTS = "/api/v1/reports";
  private static final String VALIDATE = "/api/v1/validate";
  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

  private static HttpServer provider;
  private static final AtomicReference<String> lastRequestBody = new AtomicReference<>("");
  private static final AtomicInteger providerCalls = new AtomicInteger();
  private static final AtomicReference<Boolean> providerDown = new AtomicReference<>(false);

  @LocalServerPort private int port;

  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @BeforeAll
  static void startProvider() throws IOException {
    provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext("/chat/completions", ExplainApiIT::answer);
    provider.start();
  }

  @AfterAll
  static void stopProvider() {
    provider.stop(0);
  }

  @DynamicPropertySource
  static void aiProperties(DynamicPropertyRegistry registry) {
    registry.add("features.ai-explanations", () -> "true");
    registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    registry.add("app.ai.api-key", () -> "sk-test-key-never-logged");
    registry.add("app.ai.max-retries", () -> "0");
    // A low cap so the "explains at most the cap" assertion is cheap and unambiguous.
    registry.add("app.ai.max-findings-per-request", () -> "2");
    registry.add("app.rate-limit.validate.capacity", () -> "1000");
    registry.add("app.rate-limit.validate.refill-per-minute", () -> "1000");
  }

  // -------------------------------------------------------------------- happy path

  @Test
  void explainingAStoredReportAttachesAGermanExplanationToItsFindings() throws Exception {
    String key = seedTenantWithKey("Explain Tenant");
    String reportId = validateAndStore(key, invalidFixture());

    HttpResponse<String> response = post(REPORTS + "/" + reportId + "/explain", key);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode detail = json.readTree(response.body());
    // The response is a ReportDetail: same shape as GET /reports/{id}, with aiExplanation filled
    // in.
    assertThat(detail.get("id").asText()).isEqualTo(reportId);
    assertThat(detail.get("findings")).isNotEmpty();
    JsonNode finding = detail.get("findings").get(0);
    assertThat(finding.get("ruleId").asText()).isEqualTo("AT-B2G-01");
    assertThat(finding.get("aiExplanation").asText()).contains("Auftragsreferenz");
  }

  @Test
  void theExplanationIsNotPersistedBackOntoTheReportRow() throws Exception {
    // Explaining is a read plus a paid call, not a mutation: a second GET must still show the
    // stored row, or the report would silently stop being the validator's own verdict.
    String key = seedTenantWithKey("Explain Tenant Read Only");
    String reportId = validateAndStore(key, invalidFixture());

    post(REPORTS + "/" + reportId + "/explain", key);
    HttpResponse<String> stored = get(REPORTS + "/" + reportId, key);

    assertThat(stored.statusCode()).isEqualTo(200);
    assertThat(json.readTree(stored.body()).get("findings").get(0).get("aiExplanation").isNull())
        .isTrue();
  }

  @Test
  void aSingleFindingCanBeExplainedByItsIndex() throws Exception {
    // A multi-finding report, so "exactly one was explained" is a real claim rather than a
    // tautology.
    // Asserted on the response and not on a provider call count: FindingExplainer's cache is shared
    // across this context, so a count would be measuring whether an earlier test happened to
    // explain
    // the same text first — the response is the contract either way.
    String key = seedTenantWithKey("Explain Tenant Index");
    String reportId = validateAndStore(key, manyFindingsFixture());

    HttpResponse<String> response = post(REPORTS + "/" + reportId + "/explain?findingIndex=0", key);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode findings = json.readTree(response.body()).get("findings");
    assertThat(findings.size()).isGreaterThan(1);
    assertThat(findings.get(0).get("aiExplanation").isNull()).isFalse();
    assertThat(countExplained(findings)).isEqualTo(1);
  }

  @Test
  void anOutOfRangeFindingIndexIsRefusedRatherThanIgnored() throws Exception {
    String key = seedTenantWithKey("Explain Tenant Range");
    String reportId = validateAndStore(key, invalidFixture());

    assertProblem(
        post(REPORTS + "/" + reportId + "/explain?findingIndex=99", key),
        400,
        "invalid-finding-index");
  }

  @Test
  void aReportWithNoFindingsIsAnsweredWithoutCallingTheProvider() throws Exception {
    String key = seedTenantWithKey("Explain Tenant Clean");
    String reportId = validateAndStore(key, validFixture());
    providerCalls.set(0);

    HttpResponse<String> response = post(REPORTS + "/" + reportId + "/explain", key);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).get("findings")).isEmpty();
    assertThat(providerCalls).hasValue(0);
  }

  // ------------------------------------------------------------------------- bounds

  @Test
  void atMostTheConfiguredNumberOfFindingsIsExplainedPerRequest() throws Exception {
    // app.ai.max-findings-per-request=2 above. A report with more findings than the cap must not
    // turn one request into an unbounded number of paid calls; the findings beyond the cap keep a
    // null explanation, which is exactly what that field's absence means.
    String key = seedTenantWithKey("Explain Tenant Cap");
    String reportId = validateAndStore(key, manyFindingsFixture());
    providerCalls.set(0);

    HttpResponse<String> response = post(REPORTS + "/" + reportId + "/explain", key);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode findings = json.readTree(response.body()).get("findings");
    assertThat(findings.size()).isGreaterThan(2);
    assertThat(countExplained(findings)).isEqualTo(2);
    // At most the cap reached the provider. "At most" rather than "exactly": a cached explanation
    // costs no call, so equality would be asserting cache state, not the bound that matters.
    assertThat(providerCalls.get()).isLessThanOrEqualTo(2);
  }

  // -------------------------------------------------------------------- degradation

  @Test
  void aProviderOutageIsReportedAsUnavailableRatherThanAsAnEmptySuccess() throws Exception {
    // Targets AT-B2G-02 (the IBAN checksum rule) by index, and only it. The outage has to be
    // observable, and an explanation already in FindingExplainer's shared cache is served without
    // touching the provider — so this test must ask for a text no other test in this class has
    // explained. AT-B2G-01 is explained by nearly all of them; AT-B2G-02 by none.
    String key = seedTenantWithKey("Explain Tenant Outage");
    String reportId = validateAndStore(key, invalidIbanFixture());
    int ibanFinding = indexOfRule(reportId, key, "AT-B2G-02");

    providerDown.set(true);
    try {
      // A 200 whose every aiExplanation is null is indistinguishable from "nothing to explain".
      // The caller asked for an explanation and got none because the provider is down; that is a
      // 503, and the UI's friendly-notice path is unaffected (it has its own route).
      assertProblem(
          post(REPORTS + "/" + reportId + "/explain?findingIndex=" + ibanFinding, key),
          503,
          "ai-explanation-unavailable");
    } finally {
      providerDown.set(false);
    }
  }

  @Test
  void aPartialFailureStillAnswers200WithWhatCouldBeExplained() throws Exception {
    // The other half of the degradation contract, and the reason the 503 is "none succeeded" rather
    // than "any failed": a body carrying some explanations and some nulls is honest and useful.
    String key = seedTenantWithKey("Explain Tenant Partial");
    String reportId = validateAndStore(key, manyFindingsFixture());

    HttpResponse<String> response = post(REPORTS + "/" + reportId + "/explain", key);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode findings = json.readTree(response.body()).get("findings");
    // The cap leaves the rest unexplained, which is exactly the partial shape a caller must
    // tolerate.
    assertThat(countExplained(findings)).isPositive().isLessThan(findings.size());
  }

  // -------------------------------------------------------------- auth and isolation

  @Test
  void theEndpointRefusesAnAnonymousCaller() throws Exception {
    String key = seedTenantWithKey("Explain Tenant Anon");
    String reportId = validateAndStore(key, invalidFixture());

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url(REPORTS + "/" + reportId + "/explain")))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void aTenantCannotExplainAnotherTenantsReport() throws Exception {
    String keyA = seedTenantWithKey("Explain Tenant A");
    String reportIdA = validateAndStore(keyA, invalidFixture());
    String keyB = seedTenantWithKey("Explain Tenant B");

    // The same indistinguishable 404 an unknown id gets — no oracle for "this id exists elsewhere".
    assertProblem(post(REPORTS + "/" + reportIdA + "/explain", keyB), 404, "report-not-found");
  }

  @Test
  void anUnknownReportIdIsNotFound() throws Exception {
    String key = seedTenantWithKey("Explain Tenant Unknown");

    assertProblem(
        post(REPORTS + "/" + UUID.randomUUID() + "/explain", key), 404, "report-not-found");
  }

  // ------------------------------------------------------- the invoice-tied branch

  @Test
  void aReportTiedToAStoredInvoiceCanBeExplained() throws Exception {
    // The branch that distinguishes this endpoint from the public route: an invoice-tied report
    // looks its invoice up to attach the party names as literals to redact. That lookup is real
    // code with a real tenant boundary, and it must not turn explaining into a 500 — which is what
    // an invoice-tied report did until the lookup existed.
    String key = seedTenantWithKey("Explain Tenant Invoice");
    String reportId = createInvoiceAndFindItsReport(key);

    HttpResponse<String> response = post(REPORTS + "/" + reportId + "/explain", key);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode detail = json.readTree(response.body());
    assertThat(detail.get("invoiceId").isNull()).isFalse();
    assertThat(detail.get("findings").get(0).get("aiExplanation").isNull()).isFalse();
  }

  // --------------------------------------------------------------------------- stub

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

  // ------------------------------------------------------------------------ helpers

  private String seedTenantWithKey(String name) {
    TenantEntity tenant = tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), name));
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "explain-key", generated.keyHash(), generated.prefix()));
    return generated.plaintext();
  }

  /** Validates {@code fileBytes} as the key's tenant and returns the stored report's id. */
  private String validateAndStore(String key, byte[] fileBytes) throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fileBytes);
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url(VALIDATE)))
                .header("Content-Type", multipart.contentType())
                .header("X-Api-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body()).get("id").asText();
  }

  /**
   * Creates an invoice whose generated document fails AT-B2G-01 (no Auftragsreferenz) — still
   * created, per the "invalid but stored" rule — and returns the id of the report row that creation
   * wrote, which carries a non-null {@code invoiceId}.
   */
  private String createInvoiceAndFindItsReport(String key) throws Exception {
    String number = "RE-" + UUID.randomUUID();
    HttpResponse<String> created =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/invoices")))
                .header("X-Api-Key", key)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        invoiceJson(number), StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(json.readTree(created.body()).get("report").get("findings")).isNotEmpty();
    String invoiceId = json.readTree(created.body()).get("id").asText();

    HttpResponse<String> listed = get(REPORTS + "?size=100", key);
    for (JsonNode row : json.readTree(listed.body()).get("content")) {
      JsonNode rowInvoiceId = row.get("invoiceId");
      if (!rowInvoiceId.isNull() && rowInvoiceId.asText().equals(invoiceId)) {
        return row.get("id").asText();
      }
    }
    throw new IllegalStateException("no report row found for invoice " + invoiceId);
  }

  /** Canonical invoice JSON with no {@code orderReference}, so the result fails AT-B2G-01. */
  private static String invoiceJson(String number) {
    return """
        {
          "invoiceNumber": "%s",
          "type": "INVOICE",
          "issueDate": "2026-07-24",
          "dueDate": "2026-08-23",
          "deliveryDate": "2026-07-24",
          "currency": "EUR",
          "supplierNumber": "L-100234",
          "seller": { "name": "Stoicera Software GesbR", "vatId": "ATU12345678", "email": "office@stoicera-software.at",
            "address": { "street": "Hauptplatz 1", "city": "Linz", "postalCode": "4020", "countryCode": "AT" } },
          "buyer": { "name": "Bundesbeschaffung GmbH", "vatId": "ATU87654321",
            "address": { "street": "Lassallestraße 9b", "city": "Wien", "postalCode": "1020", "countryCode": "AT" } },
          "lines": [
            { "id": "1", "description": "Softwareentwicklung Juli 2026", "quantity": "80", "unitCode": "HUR", "unitPrice": "120.00", "vatCategory": "STANDARD", "vatPercent": "20" }
          ],
          "paymentMeans": { "iban": "AT611904300234573201", "bic": "BKAUATWW" },
          "paymentTerms": "Zahlbar innerhalb von 30 Tagen ohne Abzug"
        }
        """
        .formatted(number);
  }

  /** How many of a response's findings carry a non-null {@code aiExplanation}. */
  private static int countExplained(JsonNode findings) {
    int explained = 0;
    for (JsonNode finding : findings) {
      if (!finding.get("aiExplanation").isNull()) {
        explained++;
      }
    }
    return explained;
  }

  /**
   * The position of the first finding with {@code ruleId} in the stored report, read back through
   * the API rather than assumed — the validator's finding order is its own business, and
   * hard-coding a position here would make this test fail for a reason that has nothing to do with
   * what it asserts.
   */
  private int indexOfRule(String reportId, String key, String ruleId) throws Exception {
    JsonNode findings = json.readTree(get(REPORTS + "/" + reportId, key).body()).get("findings");
    for (int index = 0; index < findings.size(); index++) {
      if (findings.get(index).get("ruleId").asText().equals(ruleId)) {
        return index;
      }
    }
    throw new IllegalStateException(
        "no " + ruleId + " finding in report " + reportId + "; findings were " + findings);
  }

  private HttpResponse<String> post(String path, String key) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("X-Api-Key", key)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, String key) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path))).header("X-Api-Key", key).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertProblem(HttpResponse<String> response, int status, String slug)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + slug);
    assertThat(problem.get("status").asInt()).isEqualTo(status);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private static byte[] validFixture() throws Exception {
    return readFixture("invoice-b2g-sample.ebinterface.xml");
  }

  private static byte[] invalidFixture() throws Exception {
    return readFixture("at-b2g-01-missing-order-reference.xml");
  }

  /**
   * A document failing several AT-B2G rules at once, so the per-request cap has something to clamp:
   * the fixture already omits the Auftragsreferenz, and this additionally drops the biller e-mail
   * and the Lieferantennummer ({@code InvoiceRecipientsBillerID}), each of which is its own rule.
   * The fixture uses ebInterface's default namespace, so the element names carry no prefix.
   */
  private static byte[] manyFindingsFixture() throws Exception {
    String xml = new String(invalidFixture(), StandardCharsets.UTF_8);
    String stripped =
        xml.replaceAll("(?s)\\s*<Email>.*?</Email>", "")
            .replaceAll("(?s)\\s*<InvoiceRecipientsBillerID>.*?</InvoiceRecipientsBillerID>", "");
    assertThat(stripped)
        .withFailMessage("the fixture's shape changed; this variant no longer removes anything")
        .doesNotContain("<Email>")
        .doesNotContain("<InvoiceRecipientsBillerID>");
    return stripped.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The AT-B2G-01 fixture with a beneficiary IBAN whose mod-97 checksum fails, so the report also
   * carries an AT-B2G-02 finding — a rule no other test in this class explains, which is what makes
   * the provider-outage assertion observable past the explanation cache.
   */
  private static byte[] invalidIbanFixture() throws Exception {
    String xml = new String(invalidFixture(), StandardCharsets.UTF_8);
    // Last digit changed: same length and country, checksum no longer valid.
    String broken = xml.replace("AT611904300234573201", "AT611904300234573202");
    assertThat(broken).doesNotContain("AT611904300234573201");
    return broken.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] readFixture(String name) throws Exception {
    try (var in = ExplainApiIT.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Fixture not found on classpath: " + name);
      }
      return in.readAllBytes();
    }
  }
}
