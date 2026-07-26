package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.app.MultipartBodies;
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

  /**
   * An unusable currency code is a 422, never a 500 — M4 hostile review, finding F2.
   *
   * <p>Neither format adapter validates against its schema when reading (that is the validation
   * module's job), so a currency code reaches the mapper exactly as the uploader wrote it. Handed
   * straight to {@code Currency.getInstance} it produced a raw {@code IllegalArgumentException},
   * which no handler maps: the caller got an opaque 500 and the server logged a stack trace, for a
   * request that was simply invalid. This is the end-to-end proof that it is now the same
   * well-described 422 every other domain rejection on this path produces.
   */
  @Test
  void answers422NotAServerErrorForAnUnusableCurrencyCode() throws Exception {
    byte[] bogusCurrency =
        new String(Fixtures.validFileBytes(), StandardCharsets.UTF_8)
            .replace("InvoiceCurrency=\"EUR\"", "InvoiceCurrency=\"BOGUS\"")
            .getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> response =
        convert(
            bogusCurrency, "ebinterface", "ubl", fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));

    assertProblem(response, 422, "invalid-invoice");
    assertThat(json.readTree(response.body()).get("detail").asText())
        .contains("BOGUS")
        .contains("ISO 4217");
  }

  /**
   * The conversion path never expands an external entity — M4 hostile review, finding F10.
   *
   * <p>This is a regression test for an ordering that is load-bearing but easy to mistake for a
   * redundant sanity check. {@code ConversionService.read} asks {@code InvoiceValidator
   * .detectFormat} what the upload is <em>before</em> handing the raw bytes to a format adapter's
   * own JAXB reader, and {@code detectFormat} parses through {@code SecureXml}, which rejects any
   * document that so much as declares a {@code DOCTYPE}. A refactor that reordered those two
   * statements — or that "optimised away" a detection step whose result looks unused on the
   * ebInterface branch — would send untrusted XML straight into a parser this module never
   * configured.
   *
   * <p>Asserting the status alone would not catch that: an adapter might reject the document for
   * its own reasons <em>after</em> resolving the entity. So the response body is checked for the
   * file's content too.
   */
  @Test
  void neverResolvesAnExternalEntityInAnUploadedDocument() throws Exception {
    String xxe =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE Invoice [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <Invoice xmlns="http://www.ebinterface.at/schema/6p1/" GeneratingSystem="x"\
         DocumentType="Invoice" InvoiceCurrency="EUR" Language="de">
          <InvoiceNumber>&xxe;</InvoiceNumber>
        </Invoice>
        """;

    HttpResponse<String> response =
        convert(
            xxe.getBytes(StandardCharsets.UTF_8),
            "ebinterface",
            "ubl",
            fetchAccessToken(TEST_USERNAME, TEST_PASSWORD));

    assertProblem(response, 400, "unsupported-conversion");
    assertThat(response.body()).doesNotContain("root:").doesNotContain("/bin/");
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
