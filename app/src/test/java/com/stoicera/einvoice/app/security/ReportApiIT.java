package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code GET /api/v1/reports} and {@code GET /api/v1/reports/{id}} end to end: a report produced by
 * an ad-hoc {@code POST /api/v1/validate} appears in the caller's own listing with {@code
 * invoiceId} null, a report produced by {@code POST /api/v1/invoices} appears with its {@code
 * invoiceId} set, the detail endpoint returns the full findings array, and tenant isolation holds
 * (one tenant cannot read another's report — same 404 as an unknown id, no oracle).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportApiIT extends AbstractKeycloakIT {

  private static final String REPORTS = "/api/v1/reports";
  private static final String VALIDATE = "/api/v1/validate";
  private static final String INVOICES = "/api/v1/invoices";
  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

  @LocalServerPort private int port;
  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void anAdHocValidationReportAppearsInTheListingAndDetailWithNullInvoiceId() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);

    HttpResponse<String> validateResponse =
        postValidate(ValidateApiIT.validFileBytes(), bearer(token));
    assertThat(validateResponse.statusCode()).isEqualTo(200);
    String reportId = json.readTree(validateResponse.body()).get("id").asText();

    HttpResponse<String> listResponse = send("GET", REPORTS + "?size=100", null, bearer(token));
    assertThat(listResponse.statusCode()).isEqualTo(200);
    JsonNode page = json.readTree(listResponse.body());
    assertThat(page.get("page").asInt()).isZero();
    JsonNode row = rowFor(page, reportId);
    assertThat(row.get("invoiceId").isNull()).isTrue();
    assertThat(row.get("sourceFormat").asText()).isEqualTo("ebinterface-6.1");
    assertThat(row.get("profile").asText()).isEqualTo("at-b2g");
    assertThat(row.get("valid").asBoolean()).isTrue();

    HttpResponse<String> detailResponse =
        send("GET", REPORTS + "/" + reportId, null, bearer(token));
    assertThat(detailResponse.statusCode()).isEqualTo(200);
    JsonNode detail = json.readTree(detailResponse.body());
    assertThat(detail.get("id").asText()).isEqualTo(reportId);
    assertThat(detail.get("invoiceId").isNull()).isTrue();
    assertThat(detail.get("sourceFormat").asText()).isEqualTo("ebinterface-6.1");
    assertThat(detail.get("valid").asBoolean()).isTrue();
    assertThat(detail.get("findings")).isEmpty();

    // The default page size is 20 when none is requested — same envelope idiom as /invoices.
    HttpResponse<String> defaults = send("GET", REPORTS, null, bearer(token));
    assertThat(json.readTree(defaults.body()).get("size").asInt()).isEqualTo(20);
  }

  @Test
  void aReportCreatedByInvoiceCreationAppearsInTheListingWithItsInvoiceId() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    String number = "RE-" + UUID.randomUUID();

    HttpResponse<String> createResponse =
        send(
            "POST",
            INVOICES,
            invoiceJson(number).getBytes(StandardCharsets.UTF_8),
            jsonAuth(bearer(token)));
    assertThat(createResponse.statusCode()).isEqualTo(201);
    String invoiceId = json.readTree(createResponse.body()).get("id").asText();

    HttpResponse<String> listResponse = send("GET", REPORTS + "?size=100", null, bearer(token));
    assertThat(listResponse.statusCode()).isEqualTo(200);
    JsonNode page = json.readTree(listResponse.body());
    JsonNode row = rowForInvoiceId(page, invoiceId);
    assertThat(row).as("a report row with invoiceId=%s", invoiceId).isNotNull();
    assertThat(row.get("valid").asBoolean()).isTrue();
  }

  @Test
  void aTenantCannotReadAnotherTenantsReport() throws Exception {
    String tokenA = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    HttpResponse<String> validateA = postValidate(ValidateApiIT.validFileBytes(), bearer(tokenA));
    String reportIdA = json.readTree(validateA.body()).get("id").asText();

    String apiKeyB = seedApiKeyTenant();

    assertProblem(
        send("GET", REPORTS + "/" + reportIdA, null, apiKey(apiKeyB)), 404, "report-not-found");

    HttpResponse<String> listAsB = send("GET", REPORTS + "?size=100", null, apiKey(apiKeyB));
    assertThat(rowFor(json.readTree(listAsB.body()), reportIdA)).isNull();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private HttpResponse<String> postValidate(byte[] fileBytes, String[] auth) throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fileBytes);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + VALIDATE))
            .header("Content-Type", multipart.contentType())
            .header(auth[0], auth[1])
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String seedApiKeyTenant() {
    TenantEntity tenant =
        tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Report Tenant B"));
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "b-key", generated.keyHash(), generated.prefix()));
    return generated.plaintext();
  }

  private JsonNode rowFor(JsonNode page, String id) {
    for (JsonNode row : page.get("content")) {
      if (row.get("id").asText().equals(id)) {
        return row;
      }
    }
    return null;
  }

  private JsonNode rowForInvoiceId(JsonNode page, String invoiceId) {
    for (JsonNode row : page.get("content")) {
      JsonNode rowInvoiceId = row.get("invoiceId");
      if (rowInvoiceId != null
          && !rowInvoiceId.isNull()
          && rowInvoiceId.asText().equals(invoiceId)) {
        return row;
      }
    }
    return null;
  }

  private void assertProblem(HttpResponse<String> response, int status, String slug)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(contentType(response)).contains("application/problem+json");
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + slug);
    assertThat(problem.get("status").asInt()).isEqualTo(status);
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

  private static String[] jsonAuth(String[] auth) {
    return new String[] {auth[0], auth[1], "Content-Type", "application/json"};
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

  /** A minimal, always domain-valid canonical invoice JSON body for one invoice number. */
  private static String invoiceJson(String number) {
    return """
        {
          "invoiceNumber": "%s",
          "type": "INVOICE",
          "issueDate": "2026-07-24",
          "dueDate": "2026-08-23",
          "deliveryDate": "2026-07-24",
          "currency": "EUR",
          "orderReference": "BBG-2026-4711",
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
}
