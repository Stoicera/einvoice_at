package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code POST /api/v1/convert} end to end over real HTTP against Postgres + Keycloak.
 *
 * <p>The load-bearing assertion is the last one in {@link
 * #convertsEbInterfaceToUblAndValidatesTheResult()}: the converted document is run through the
 * <em>official</em> OpenPeppol rule set, so this test proves the whole chain — read ebInterface,
 * canonicalise, write UBL, validate against Peppol — and not merely that an endpoint returns 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConvertApiIT extends AbstractKeycloakIT {

  private static final String CONVERT = "/api/v1/convert";
  private static final String PROBLEM_BASE = "https://einvoice-at.stoicera.com/problems/";

  @LocalServerPort private int port;
  @Autowired private AuditEventRepository auditEvents;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void requiresAuthentication() throws Exception {
    HttpResponse<String> response = convert(Fixtures.validFileBytes(), "ebinterface", "ubl", null);

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void convertsEbInterfaceToUblAndValidatesTheResult() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);
    long auditsBefore = auditEvents.count();

    HttpResponse<String> response = convert(Fixtures.validFileBytes(), "ebinterface", "ubl", token);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());

    // The converted document really is UBL, and really is an invoice rather than a credit note.
    assertThat(body.get("xml").asText())
        .contains("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2")
        .contains("urn:cen.eu:en16931:2017#compliant");

    // The conversion report names both ends and is machine-readable.
    assertThat(body.get("conversion").get("sourceFormat").asText()).isEqualTo("ebinterface");
    assertThat(body.get("conversion").get("targetFormat").asText()).isEqualTo("ubl");

    // The result was judged by the official Peppol rule set, not by our own opinion of it.
    assertThat(body.get("report").get("profile").asText()).isEqualTo("peppol-bis-billing-3.0");
    assertThat(body.get("report").get("sourceFormat").asText()).isEqualTo("ubl-invoice-2.1");

    assertThat(auditEvents.count()).isEqualTo(auditsBefore + 1);
  }

  /**
   * The ebInterface fixture carries no electronic addresses — ebInterface has no element for them —
   * so the Peppol rule set rejects the converted document, and the caller is told so <em>here</em>
   * rather than by an access point later. That is the point of validating the output.
   */
  @Test
  void reportsWhyAConvertedDocumentIsNotYetPeppolReady() throws Exception {
    HttpResponse<String> response =
        convert(
            Fixtures.validFileBytes(),
            "ebinterface",
            "ubl",
            fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));

    JsonNode report = json.readTree(response.body()).get("report");

    assertThat(report.get("findings")).isNotEmpty();
    assertThat(report.toString()).contains("PEPPOL-EN16931-R");
  }

  @Test
  void convertsUblBackToEbInterface() throws Exception {
    String token = fetchAccessToken(TEST_USERNAME, TEST_PASSWORD);

    // Round the fixture out to UBL first, then bring it back — a genuine two-way conversion.
    String ubl =
        json.readTree(convert(Fixtures.validFileBytes(), "ebinterface", "ubl", token).body())
            .get("xml")
            .asText();

    HttpResponse<String> back =
        convert(ubl.getBytes(StandardCharsets.UTF_8), "ubl", "ebinterface", token);

    assertThat(back.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(back.body());
    assertThat(body.get("xml").asText()).contains("http://www.ebinterface.at/schema/6p1/");
    assertThat(body.get("report").get("profile").asText()).isEqualTo("at-b2g");
  }

  @Test
  void refusesAConversionBetweenTheSameFormats() throws Exception {
    HttpResponse<String> response =
        convert(
            Fixtures.validFileBytes(),
            "ebinterface",
            "ebinterface",
            fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));

    assertProblem(response, 400, "unsupported-conversion");
  }

  /** A caller who mislabels the upload gets told which format it actually is. */
  @Test
  void refusesAnUploadThatIsNotInTheDeclaredFormat() throws Exception {
    HttpResponse<String> response =
        convert(
            Fixtures.validFileBytes(),
            "ubl",
            "ebinterface",
            fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));

    assertProblem(response, 400, "unsupported-conversion");
    assertThat(json.readTree(response.body()).get("detail").asText()).contains("ebinterface-6.1");
  }

  @Test
  void refusesAnUnknownFormatName() throws Exception {
    HttpResponse<String> response =
        convert(
            Fixtures.validFileBytes(),
            "cii",
            "ubl",
            fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));

    // Spring rejects the unbindable enum before the controller runs; it is still problem+json.
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
  }

  @Test
  void refusesGarbageThatIsNotAnInvoiceAtAll() throws Exception {
    HttpResponse<String> response =
        convert(
            "not xml".getBytes(StandardCharsets.UTF_8),
            "ebinterface",
            "ubl",
            fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));

    assertProblem(response, 400, "unsupported-conversion");
  }

  private HttpResponse<String> convert(byte[] document, String from, String to, String token)
      throws Exception {
    MultipartBodies.Multipart multipart =
        MultipartBodies.singleFilePart("file", "invoice.xml", document);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + CONVERT + "?from=" + from + "&to=" + to))
            .header("Content-Type", multipart.contentType());
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body())).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertProblem(HttpResponse<String> response, int status, String slug)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + slug);
    assertThat(problem.get("title").asText()).isNotBlank();
  }
}
