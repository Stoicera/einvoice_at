package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import com.stoicera.einvoice.app.persistence.InvoiceEntity;
import com.stoicera.einvoice.app.persistence.InvoiceRepository;
import com.stoicera.einvoice.app.persistence.ReportEntity;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The invoices API end to end over real HTTP against Postgres + Keycloak: create (with its
 * persisted report and audit row), the "invalid-but-still-created" rule, every error mapping,
 * tenant isolation (one tenant cannot read another's invoice, listings are disjoint), pagination
 * bounds, and the canonical-JSON / ebInterface-XML reads.
 *
 * <p>Tenant A is a JWT login ({@code testuser}, provisioned on first use); tenant B is seeded with
 * an API key, exercising both authentication kinds. The Postgres container is shared across the
 * run, so every invoice number is made unique to keep the methods independent of the uniqueness
 * constraint and of rows other tests leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceApiIT extends AbstractKeycloakIT {

  private static final String INVOICES = "/api/v1/invoices";
  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

  @LocalServerPort private int port;
  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;
  @Autowired private InvoiceRepository invoices;
  @Autowired private ReportRepository reports;
  @Autowired private AuditEventRepository auditEvents;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void createReturns201WithReportAndPersistsInvoiceReportAndAudit() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String number = uniqueNumber();
    byte[] body = invoiceJson(number, true).getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> response = send("POST", INVOICES, body, jsonAuth(bearer(token)));
    assertThat(response.statusCode()).isEqualTo(201);

    JsonNode payload = json.readTree(response.body());
    String id = payload.get("id").asText();
    assertThat(response.headers().firstValue("Location")).hasValue(INVOICES + "/" + id);

    // Body carries the ValidationReport shape; the fully-populated invoice is spotless.
    JsonNode report = payload.get("report");
    assertThat(report.get("sourceFormat").asText()).isEqualTo("ebinterface-6.1");
    assertThat(report.get("profile").asText()).isEqualTo("at-b2g");
    assertThat(report.get("valid").asBoolean()).isTrue();
    assertThat(report.get("findings").size()).isZero();

    UUID tenantId = tenants.findByExternalSubject(subjectOf(token)).orElseThrow().getId();
    UUID invoiceId = UUID.fromString(id);

    InvoiceEntity invoice = invoices.findByIdAndTenantId(invoiceId, tenantId).orElseThrow();
    assertThat(invoice.getInvoiceNumber()).isEqualTo(number);
    assertThat(invoice.getTypeCode()).isEqualTo("380");
    assertThat(invoice.getCurrency()).isEqualTo("EUR");
    assertThat(invoice.getSellerName()).isEqualTo("Stoicera Software GesbR");
    assertThat(invoice.getBuyerName()).isEqualTo("Bundesbeschaffung GmbH");
    assertThat(invoice.getPayableAmount()).isEqualByComparingTo("11670.15");
    // Canonical stored as received (JSONB-normalized), not reconstructed from the domain model.
    assertThat(json.readTree(invoice.getCanonical()).get("invoiceNumber").asText())
        .isEqualTo(number);

    List<ReportEntity> stored = reports.findByInvoiceIdIn(List.of(invoiceId));
    assertThat(stored).hasSize(1);
    ReportEntity storedReport = stored.get(0);
    assertThat(storedReport.getTenantId()).isEqualTo(tenantId);
    assertThat(storedReport.isValid()).isTrue();
    assertThat(storedReport.getSourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(storedReport.getProfile()).isEqualTo("at-b2g");
    assertThat(json.readTree(storedReport.getFindings()).size()).isZero();

    // Audit: an INVOICE_CREATED row for this tenant, hashing the exact request bytes.
    String expectedHash = sha256Hex(body);
    assertThat(auditEvents.findAll())
        .anyMatch(
            e ->
                e.getTenantId().equals(tenantId)
                    && e.getAction().equals("INVOICE_CREATED")
                    && expectedHash.equals(e.getPayloadSha256()));
  }

  @Test
  void anInvalidButParseableInvoiceIsStillCreatedWithFindings() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String number = uniqueNumber();
    // Omitting the Auftragsreferenz keeps the invoice parseable and domain-valid, but the
    // ebInterface
    // document fails the AT-B2G Schematron (AT-B2G-01).
    byte[] body = invoiceJson(number, false).getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> response = send("POST", INVOICES, body, jsonAuth(bearer(token)));
    assertThat(response.statusCode()).isEqualTo(201);

    JsonNode payload = json.readTree(response.body());
    UUID invoiceId = UUID.fromString(payload.get("id").asText());
    JsonNode report = payload.get("report");
    assertThat(report.get("valid").asBoolean()).isFalse();

    boolean hasOrderReferenceFinding = false;
    for (JsonNode finding : report.get("findings")) {
      if (finding.get("ruleId").asText().equals("AT-B2G-01")) {
        hasOrderReferenceFinding = true;
      }
    }
    assertThat(hasOrderReferenceFinding).isTrue();

    UUID tenantId = tenants.findByExternalSubject(subjectOf(token)).orElseThrow().getId();
    assertThat(invoices.findByIdAndTenantId(invoiceId, tenantId)).isPresent();
    assertThat(reports.findByInvoiceIdIn(List.of(invoiceId)).get(0).isValid()).isFalse();
  }

  @Test
  void malformedJsonBodyIsRejectedWith400() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    byte[] body = "{ this is not valid json".getBytes(StandardCharsets.UTF_8);

    assertProblem(send("POST", INVOICES, body, jsonAuth(bearer(token))), 400, "invalid-json");
  }

  @Test
  void aWellFormedButDomainInvalidInvoiceIsRejectedWith422() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    // Blank invoice number: well-formed JSON, rejected by a core invariant (not a JSON-shape
    // error).
    byte[] body = invoiceJson("", true).getBytes(StandardCharsets.UTF_8);

    assertProblem(send("POST", INVOICES, body, jsonAuth(bearer(token))), 422, "invalid-invoice");
  }

  @Test
  void aDuplicateInvoiceNumberIsRejectedWith409() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String number = uniqueNumber();
    create(number, bearer(token));

    byte[] body = invoiceJson(number, true).getBytes(StandardCharsets.UTF_8);
    assertProblem(send("POST", INVOICES, body, jsonAuth(bearer(token))), 409, "duplicate-invoice");
  }

  @Test
  void anUnknownInvoiceIdIsNotFound() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    HttpResponse<String> response =
        send("GET", INVOICES + "/" + UUID.randomUUID(), null, bearer(token));

    assertProblem(response, 404, "invoice-not-found");
  }

  @Test
  void getReturnsStoredCanonicalJson() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String number = uniqueNumber();
    String id = create(number, bearer(token));

    HttpResponse<String> response = send("GET", INVOICES + "/" + id, null, bearer(token));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(contentType(response)).contains("application/json");

    JsonNode canonical = json.readTree(response.body());
    assertThat(canonical.get("invoiceNumber").asText()).isEqualTo(number);
    assertThat(canonical.get("currency").asText()).isEqualTo("EUR");
    assertThat(canonical.get("seller").get("name").asText()).isEqualTo("Stoicera Software GesbR");
  }

  @Test
  void getEbInterfaceRegeneratesXml() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String number = uniqueNumber();
    String id = create(number, bearer(token));

    HttpResponse<String> response =
        send("GET", INVOICES + "/" + id + "/ebinterface", null, bearer(token));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(contentType(response)).contains("application/xml");
    assertThat(response.body()).startsWith("<?xml");
    assertThat(response.body()).contains("<Invoice"); // ebInterface 6.1 root
    assertThat(response.body()).contains(number);
  }

  @Test
  void listIsTenantScopedNewestFirstAndClampsSize() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String number1 = uniqueNumber();
    String number2 = uniqueNumber();
    String id1 = create(number1, bearer(token));
    String id2 = create(number2, bearer(token));

    // A size above the cap is clamped to 100, never rejected.
    HttpResponse<String> big = send("GET", INVOICES + "?page=0&size=1000", null, bearer(token));
    assertThat(big.statusCode()).isEqualTo(200);
    JsonNode page = json.readTree(big.body());
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(100);
    assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
    assertThat(page.get("totalPages").asInt()).isGreaterThanOrEqualTo(1);

    List<String> ids = idsInOrder(page);
    assertThat(ids).contains(id1, id2);
    // createdAt descending: the later creation comes first.
    assertThat(ids.indexOf(id2)).isLessThan(ids.indexOf(id1));

    // A summary row carries exactly the projection the contract promises.
    JsonNode row = rowFor(page, id1);
    assertThat(row.get("invoiceNumber").asText()).isEqualTo(number1);
    assertThat(row.get("typeCode").asText()).isEqualTo("380");
    assertThat(row.get("issueDate").asText()).isEqualTo("2026-07-24");
    assertThat(row.get("currency").asText()).isEqualTo("EUR");
    assertThat(row.get("buyerName").asText()).isEqualTo("Bundesbeschaffung GmbH");
    assertThat(row.get("payableAmount").decimalValue()).isEqualByComparingTo("11670.15");
    assertThat(row.get("valid").asBoolean()).isTrue();

    // The default size is 20 when none is requested.
    HttpResponse<String> defaults = send("GET", INVOICES, null, bearer(token));
    assertThat(json.readTree(defaults.body()).get("size").asInt()).isEqualTo(20);
  }

  @Test
  void aTenantCannotReadAnotherTenantsInvoiceAndListingsAreDisjoint() throws Exception {
    String tokenA = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String idA = create(uniqueNumber(), bearer(tokenA));

    String apiKeyB = seedApiKeyTenant();
    String idB = create(uniqueNumber(), apiKey(apiKeyB));

    // B is refused A's invoice and its XML with the same 404 as a nonexistent id — no cross-tenant
    // existence oracle.
    assertProblem(
        send("GET", INVOICES + "/" + idA, null, apiKey(apiKeyB)), 404, "invoice-not-found");
    assertProblem(
        send("GET", INVOICES + "/" + idA + "/ebinterface", null, apiKey(apiKeyB)),
        404,
        "invoice-not-found");

    Set<String> aIds = allListedIds(bearer(tokenA));
    Set<String> bIds = allListedIds(apiKey(apiKeyB));
    assertThat(aIds).contains(idA).doesNotContain(idB);
    assertThat(bIds).contains(idB).doesNotContain(idA);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private String create(String number, String[] auth) throws Exception {
    byte[] body = invoiceJson(number, true).getBytes(StandardCharsets.UTF_8);
    HttpResponse<String> response = send("POST", INVOICES, body, jsonAuth(auth));
    assertThat(response.statusCode()).isEqualTo(201);
    return json.readTree(response.body()).get("id").asText();
  }

  private String seedApiKeyTenant() {
    TenantEntity tenant = tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Tenant B"));
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "b-key", generated.keyHash(), generated.prefix()));
    return generated.plaintext();
  }

  private Set<String> allListedIds(String[] auth) throws Exception {
    HttpResponse<String> response = send("GET", INVOICES + "?size=100", null, auth);
    assertThat(response.statusCode()).isEqualTo(200);
    return new HashSet<>(idsInOrder(json.readTree(response.body())));
  }

  private List<String> idsInOrder(JsonNode page) {
    List<String> ids = new ArrayList<>();
    for (JsonNode row : page.get("content")) {
      ids.add(row.get("id").asText());
    }
    return ids;
  }

  private JsonNode rowFor(JsonNode page, String id) {
    for (JsonNode row : page.get("content")) {
      if (row.get("id").asText().equals(id)) {
        return row;
      }
    }
    throw new AssertionError("Row not found in page: " + id);
  }

  private void assertProblem(HttpResponse<String> response, int status, String slug)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(contentType(response)).contains("application/problem+json");
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + slug);
    assertThat(problem.get("status").asInt()).isEqualTo(status);
    assertThat(problem.get("title").asText()).isNotBlank();
  }

  private static String contentType(HttpResponse<String> response) {
    return response.headers().firstValue("Content-Type").orElse("");
  }

  private static String[] bearer(String token) {
    return new String[] {"Authorization", "Bearer " + token};
  }

  private static String[] apiKey(String key) {
    return new String[] {"X-Api-Key", key};
  }

  /** Appends a JSON content-type to an auth header pair. */
  private static String[] jsonAuth(String[] auth) {
    String[] headers = Arrays.copyOf(auth, auth.length + 2);
    headers[auth.length] = "Content-Type";
    headers[auth.length + 1] = "application/json";
    return headers;
  }

  private HttpResponse<String> send(String method, String path, byte[] body, String[] headers)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    for (int i = 0; i < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    HttpRequest.BodyPublisher publisher =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body);
    builder.method(method, publisher);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String uniqueNumber() {
    return "RE-" + UUID.randomUUID();
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** Builds a canonical-invoice JSON body; {@code withOrderReference=false} triggers AT-B2G-01. */
  private static String invoiceJson(String number, boolean withOrderReference) {
    String orderReference = withOrderReference ? "\"orderReference\": \"BBG-2026-4711\"," : "";
    return """
        {
          "invoiceNumber": "%s",
          "type": "INVOICE",
          "issueDate": "2026-07-24",
          "dueDate": "2026-08-23",
          "deliveryDate": "2026-07-24",
          "currency": "EUR",
          %s
          "supplierNumber": "L-100234",
          "seller": { "name": "Stoicera Software GesbR", "vatId": "ATU12345678", "email": "office@stoicera-software.at",
            "address": { "street": "Hauptplatz 1", "city": "Linz", "postalCode": "4020", "countryCode": "AT" } },
          "buyer": { "name": "Bundesbeschaffung GmbH", "vatId": "ATU87654321",
            "address": { "street": "Lassallestraße 9b", "city": "Wien", "postalCode": "1020", "countryCode": "AT" } },
          "lines": [
            { "id": "1", "description": "Softwareentwicklung Juli 2026", "quantity": "80", "unitCode": "HUR", "unitPrice": "120.00", "vatCategory": "STANDARD", "vatPercent": "20" },
            { "id": "2", "description": "Fachliteratur", "quantity": "3", "unitCode": "C62", "unitPrice": "45.50", "vatCategory": "STANDARD", "vatPercent": "10" }
          ],
          "paymentMeans": { "iban": "AT611904300234573201", "bic": "BKAUATWW" },
          "paymentTerms": "Zahlbar innerhalb von 30 Tagen ohne Abzug"
        }
        """
        .formatted(number, orderReference);
  }
}
