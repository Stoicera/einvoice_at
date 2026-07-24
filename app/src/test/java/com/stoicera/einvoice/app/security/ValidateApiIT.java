package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import com.stoicera.einvoice.app.persistence.ReportEntity;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import com.stoicera.einvoice.validation.RuleIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code POST /api/v1/validate} end to end over real HTTP: the anonymous carve-out (a report comes
 * back, nothing is written), the authenticated persist-and-audit path for both authentication
 * kinds, the validator's own never-throws findings path for unparseable input, and the two upload
 * boundary conditions — an oversized upload (413) and a missing {@code file} part (400).
 *
 * <p>Row-count deltas (not absolute counts) are asserted for the anonymous cases: the shared
 * Postgres container accumulates rows across the whole test run, but Failsafe runs these tests
 * sequentially in one JVM, so "before" and "after" counts around a single anonymous call are a
 * valid zero-rows-written assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValidateApiIT extends AbstractKeycloakIT {

  private static final String VALIDATE = "/api/v1/validate";
  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

  @LocalServerPort private int port;
  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;
  @Autowired private ReportRepository reports;
  @Autowired private AuditEventRepository auditEvents;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void anonymousValidatingAValidFileReturns200WithNullIdAndPersistsNothing() throws Exception {
    long reportsBefore = reports.count();
    long auditBefore = auditEvents.count();

    HttpResponse<String> response = postValidate(Fixtures.validFileBytes(), null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(contentType(response)).contains("application/json");

    JsonNode payload = json.readTree(response.body());
    assertThat(payload.get("id").isNull()).isTrue();
    JsonNode report = payload.get("report");
    assertThat(report.get("sourceFormat").asText()).isEqualTo("ebinterface-6.1");
    assertThat(report.get("profile").asText()).isEqualTo("at-b2g");
    assertThat(report.get("valid").asBoolean()).isTrue();
    assertThat(report.get("findings")).isEmpty();

    assertThat(reports.count()).isEqualTo(reportsBefore);
    assertThat(auditEvents.count()).isEqualTo(auditBefore);
  }

  @Test
  void anonymousValidatingAnInvalidFileReturns200WithFindingsAndPersistsNothing() throws Exception {
    long reportsBefore = reports.count();
    long auditBefore = auditEvents.count();

    HttpResponse<String> response = postValidate(Fixtures.invalidFileBytes(), null);
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode payload = json.readTree(response.body());
    assertThat(payload.get("id").isNull()).isTrue();
    JsonNode report = payload.get("report");
    assertThat(report.get("valid").asBoolean()).isFalse();

    boolean hasOrderReferenceFinding = false;
    for (JsonNode finding : report.get("findings")) {
      if (finding.get("ruleId").asText().equals(RuleIds.AT_B2G_01)) {
        hasOrderReferenceFinding = true;
      }
    }
    assertThat(hasOrderReferenceFinding).isTrue();

    assertThat(reports.count()).isEqualTo(reportsBefore);
    assertThat(auditEvents.count()).isEqualTo(auditBefore);
  }

  @Test
  void anonymousValidatingGarbageBytesReturns200WithAnXmlErrorFindingNotA500() throws Exception {
    byte[] garbage =
        "this is not xml at all <<< not >>> well-formed".getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> response = postValidate(garbage, null);
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode payload = json.readTree(response.body());
    assertThat(payload.get("id").isNull()).isTrue();
    JsonNode report = payload.get("report");
    assertThat(report.get("valid").asBoolean()).isFalse();
    assertThat(report.get("findings").get(0).get("ruleId").asText()).isEqualTo(RuleIds.XML_01);
  }

  @Test
  void jwtAuthenticatedValidatePersistsReportAndAudit() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    byte[] fileBytes = Fixtures.validFileBytes();

    HttpResponse<String> response = postValidate(fileBytes, bearer(token));
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode payload = json.readTree(response.body());
    UUID id = UUID.fromString(payload.get("id").asText());
    assertThat(payload.get("report").get("valid").asBoolean()).isTrue();

    UUID tenantId = tenants.findByExternalSubject(subjectOf(token)).orElseThrow().getId();
    ReportEntity stored = reports.findByIdAndTenantId(id, tenantId).orElseThrow();
    assertThat(stored.getInvoiceId()).isNull();
    assertThat(stored.isValid()).isTrue();
    assertThat(stored.getSourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(stored.getProfile()).isEqualTo("at-b2g");
    assertThat(json.readTree(stored.getFindings())).isEmpty();

    String expectedHash = sha256Hex(fileBytes);
    assertThat(auditEvents.findAll())
        .anyMatch(
            e ->
                e.getTenantId().equals(tenantId)
                    && e.getAction().equals("VALIDATION_RUN")
                    && expectedHash.equals(e.getPayloadSha256()));
  }

  @Test
  void apiKeyAuthenticatedValidatePersistsReportAndAudit() throws Exception {
    TenantEntity tenant =
        tenants.save(new TenantEntity("kc-sub-" + UUID.randomUUID(), "Key Tenant"));
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "validate-key", generated.keyHash(), generated.prefix()));
    byte[] fileBytes = Fixtures.invalidFileBytes();

    HttpResponse<String> response = postValidate(fileBytes, apiKeyHeader(generated.plaintext()));
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode payload = json.readTree(response.body());
    UUID id = UUID.fromString(payload.get("id").asText());
    assertThat(payload.get("report").get("valid").asBoolean()).isFalse();

    ReportEntity stored = reports.findByIdAndTenantId(id, tenant.getId()).orElseThrow();
    assertThat(stored.getInvoiceId()).isNull();
    assertThat(stored.isValid()).isFalse();

    String expectedHash = sha256Hex(fileBytes);
    assertThat(auditEvents.findAll())
        .anyMatch(
            e ->
                e.getTenantId().equals(tenant.getId())
                    && e.getAction().equals("VALIDATION_RUN")
                    && expectedHash.equals(e.getPayloadSha256()));
  }

  @Test
  void anUploadAboveTheTwoMegabyteCapIsRejectedWith413() throws Exception {
    byte[] tooBig = new byte[3 * 1024 * 1024]; // 3 MB, above the 2 MB application-layer cap

    HttpResponse<String> response = postValidate(tooBig, null);
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(contentType(response)).contains("application/problem+json");

    // Spring Framework 7 resolves HTTP 413 to the HttpStatus enum constant CONTENT_TOO_LARGE (the
    // successor to the deprecated PAYLOAD_TOO_LARGE name), so that is the slug
    // ApiExceptionHandler's
    // slugForStatus stamps onto the problem — verified here against the running application, not
    // assumed from the Boot 3-era name.
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "content-too-large");
    assertThat(problem.get("status").asInt()).isEqualTo(413);
  }

  @Test
  void aMissingFilePartIsRejectedWith400() throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart(
            "not-the-file-part", "invoice.xml", Fixtures.validFileBytes());
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + VALIDATE))
            .header("Content-Type", multipart.contentType())
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()));

    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(contentType(response)).contains("application/problem+json");

    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "bad-request");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private HttpResponse<String> postValidate(byte[] fileBytes, String[] auth) throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", fileBytes);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + VALIDATE))
            .header("Content-Type", multipart.contentType());
    if (auth != null) {
      builder.header(auth[0], auth[1]);
    }
    builder.POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String[] bearer(String token) {
    return new String[] {"Authorization", "Bearer " + token};
  }

  private static String[] apiKeyHeader(String key) {
    return new String[] {"X-Api-Key", key};
  }

  private static String contentType(HttpResponse<String> response) {
    return response.headers().firstValue("Content-Type").orElse("");
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
