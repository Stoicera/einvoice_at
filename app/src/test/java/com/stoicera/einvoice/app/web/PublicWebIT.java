package com.stoicera.einvoice.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.MultipartBodies;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The public browser surface, end to end against a real context and a real Postgres.
 *
 * <p>Four claims are asserted here that nothing else covers, and each is a promise this project
 * makes in prose somewhere:
 *
 * <ul>
 *   <li>the public pages are reachable <strong>with no credential</strong> — the web filter chain
 *       has not accidentally put a login in front of the lead magnet;
 *   <li>the DSGVO notice MILESTONES names by name is on the page, and is <strong>true</strong>: an
 *       anonymous upload writes no report row;
 *   <li>with the AI flag off (the shipped default) no "Erklären" button is rendered — the M5
 *       Abnahme's "abschaltbar ohne Funktionsverlust" as an assertion rather than a claim;
 *   <li>the two filter chains still own their own paths (ADR-0009): {@code /api/**} answers 401 as
 *       an API, not a login redirect.
 * </ul>
 *
 * <p><strong>The forms are driven the way a browser drives them</strong>, not by posting past the
 * CSRF filter: each test fetches the page, harvests the session cookie and the {@code _csrf} token
 * Thymeleaf injected, and posts both back. That is more setup than disabling CSRF for the public
 * routes would have been, and it is the point — the enforcement ENGINEERING_STANDARDS §4 asks for
 * is on, and these tests prove the forms still work with it on. A helper that silently skipped the
 * token would have hidden a broken form until someone opened a browser.
 *
 * <p>{@link java.net.http.HttpClient} rather than {@code RestTemplate}: it does not throw on a 4xx,
 * so the "must not be readable" assertions can look at a status code instead of catching an
 * exception — the same reason the API ITs use it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicWebIT extends AbstractPostgresIT {

  private static final Path VALID_SAMPLE =
      Path.of("src/test/resources/fixtures/invoice-b2g-sample.ebinterface.xml");
  private static final Path INVALID_SAMPLE =
      Path.of("src/test/resources/fixtures/at-b2g-01-missing-order-reference.xml");
  private static final Path PEPPOL_WITHOUT_ENDPOINTS =
      Path.of("src/test/resources/fixtures/peppol-missing-endpoint-ids.xml");

  /** The uploaded document as the correction form carries it back (RepairView). */
  private static final Pattern DOCUMENT_INPUT =
      Pattern.compile("name=\"document\"\s+value=\"([^\"]+)\"");

  /**
   * Thymeleaf renders Spring Security's token as a hidden input on every {@code th:action} form.
   */
  private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

  /**
   * This class uploads more than the default anonymous allowance of 10 a minute — every
   * correction-proposal test validates at least once, the input step twice — all from one address.
   * Raised here, as {@code ExplainApiIT} does, so the limit stays a property {@code RateLimitIT}
   * tests on purpose rather than something this class trips over by accident.
   */
  @DynamicPropertySource
  static void generousRateLimit(DynamicPropertyRegistry registry) {
    registry.add("app.rate-limit.validate.capacity", () -> "1000");
    registry.add("app.rate-limit.validate.refill-per-minute", () -> "1000");
  }

  @LocalServerPort private int port;

  @Autowired private ReportRepository reports;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  // -------------------------------------------------------------- public reach

  @Test
  void theLandingPageIsReachableAnonymously() throws Exception {
    HttpResponse<String> response = get("/");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("E-Rechnung für Österreich");
    // German-first, and declared as such: PRD §8 wants this indexed for German searches.
    assertThat(response.body()).contains("lang=\"de\"");
  }

  @Test
  void theValidatorPageIsReachableAnonymouslyAndCarriesItsSeoMeta() throws Exception {
    HttpResponse<String> response = get("/validator");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("Österreichischer E-Rechnungs-Prüfer")
        .contains("<meta name=\"description\"")
        .contains("ebInterface");
  }

  /** MILESTONES names this notice explicitly; a test keeps it from being edited away. */
  @Test
  void theValidatorPageCarriesTheDsgvoNotice() throws Exception {
    assertThat(get("/validator").body()).contains("Der Upload wird nicht gespeichert");
  }

  /**
   * The API's problem {@code type} URIs point at {@code /problems/{slug}} on this host. Following
   * one must lead an anonymous client to the documentation — not to a login page, which is where
   * the web chain's catch-all would send it — and a path that is not slug-shaped must not be
   * reflected into the redirect.
   */
  @Test
  void aProblemTypeUriRedirectsAnonymouslyToItsDocumentation() throws Exception {
    HttpResponse<String> response = get("/problems/rate-limited");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .contains(
            "https://github.com/Stoicera/einvoice_at/blob/main/docs/problems.md#rate-limited");
    assertThat(get("/problems").statusCode()).isEqualTo(302);
    assertThat(get("/problems/Not_A_Slug").statusCode()).isEqualTo(404);
  }

  @Test
  void theStylesheetAndScriptAreServedAnonymously() throws Exception {
    assertThat(get("/app.css").statusCode()).isEqualTo(200);
    assertThat(get("/app.js").statusCode()).isEqualTo(200);
    assertThat(get("/favicon.svg").statusCode()).isEqualTo(200);
  }

  /**
   * {@code SecurityConfig} permitted {@code /robots.txt} while no such file existed (M5 hostile
   * review, F15). A permit rule for a path that 404s is a rule nobody can act on, and MILESTONES
   * names SEO for this page — so the file exists now, and this asserts both that it is served and
   * that it says the two things it has to say.
   */
  @Test
  void theCrawlerPolicyIsServedAndKeepsTheDashboardOutOfTheIndex() throws Exception {
    HttpResponse<String> response = get("/robots.txt");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Allow: /validator").contains("Disallow: /app/");
  }

  /**
   * RFC 9116: the contact SECURITY.md names is also published machine-readably at {@code
   * /.well-known/security.txt}. Before the web chain permitted the prefix, the live demo answered
   * that URL with a redirect to the Keycloak login (checked 2026-10-13) — the one visitor who most
   * needs an answer was the one asked to log in. This asserts the file is served, as plain text
   * with the {@code utf-8} charset RFC 9116 §3 requires ({@code PlainTextCharsetConfig}), and that
   * it names a contact.
   */
  @Test
  void theSecurityContactIsServedAnonymouslyAsPlainText() throws Exception {
    HttpResponse<String> response = get("/.well-known/security.txt");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("text/plain")
        .containsIgnoringCase("charset=utf-8");
    assertThat(response.body()).contains("Contact:").contains("Expires:");
  }

  // ------------------------------------------------------------------- uploads

  @Test
  void aValidUploadIsReportedAsValidAndNothingIsPersisted() throws Exception {
    long before = reports.count();

    HttpResponse<String> response = upload(VALID_SAMPLE);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Diese Datei ist gültig");
    // The DSGVO promise, asserted rather than trusted: the anonymous path passes an empty tenant to
    // ReportService, which writes no row.
    assertThat(reports.count()).isEqualTo(before);
  }

  @Test
  void anInvalidUploadShowsTheGermanFindingWithItsRuleId() throws Exception {
    HttpResponse<String> response = upload(INVALID_SAMPLE);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("Diese Datei ist nicht gültig")
        .contains("AT-B2G-01")
        .contains("Auftragsreferenz");
  }

  @Test
  void anUploadWithNoFileAsksForOneInsteadOfFailing() throws Exception {
    Session session = openValidatorPage();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("_csrf", session.csrfToken());

    HttpResponse<String> response =
        session.post("/validator/pruefen", MultipartBodies.form(fields, null, null, null));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Bitte wählen Sie eine XML-Datei aus");
  }

  @Test
  void anUploadWithoutTheCsrfTokenIsRefused() throws Exception {
    // The other half of driving the form properly: without the token the request must be rejected,
    // or the token would be decoration. ENGINEERING_STANDARDS §4 asks for this enforcement, and the
    // API chain legitimately does not have it — so it has to be proven here.
    Session session = openValidatorPage();
    Map<String, String> noToken = new LinkedHashMap<>();

    HttpResponse<String> response =
        session.post(
            "/validator/pruefen",
            MultipartBodies.form(noToken, "file", "invoice.xml", Files.readAllBytes(VALID_SAMPLE)));

    assertThat(response.statusCode()).isEqualTo(403);
  }

  // ------------------------------------------------------ correction proposal

  @Test
  void aPeppolUploadGetsACorrectionProposalWithEvidenceAndAGreenRevalidation() throws Exception {
    long before = reports.count();

    HttpResponse<String> response = upload(PEPPOL_WITHOUT_ENDPOINTS);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("Korrekturvorschlag mit Nachweis")
        .contains("Regelwerk entscheidet, Vorschlag ist nachvollziehbar.")
        .contains("PEPPOL-EN16931-R020")
        .contains("PEPPOL-EN16931-R010")
        .contains("ATU12345678 (Schema 9914)")
        .contains("aus dem Dokument")
        .contains("BT-31")
        .contains("behoben")
        .contains("Erneute Prüfung der korrigierten Datei: gültig.")
        .contains("class=\"diff-add\"")
        // No input is needed here, so the document is not sent back to the browser.
        .doesNotContain("name=\"document\"");
    assertThat(reports.count()).isEqualTo(before);
  }

  @Test
  void anOrderReferenceIsAskedForNotInvented() throws Exception {
    HttpResponse<String> response = upload(INVALID_SAMPLE);

    assertThat(response.body())
        .contains("Korrekturvorschlag mit Nachweis")
        .contains("Eingabe erforderlich")
        .contains("name=\"orderReference\"")
        .doesNotContain("Erneute Prüfung der korrigierten Datei");
  }

  @Test
  void theOrderReferenceIsAppliedRevalidatedAndNothingIsPersisted() throws Exception {
    Session session = openValidatorPage();
    String document = documentField(upload(INVALID_SAMPLE).body());
    long before = reports.count();

    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("_csrf", session.csrfToken());
    fields.put("document", document);
    fields.put("filename", "rechnung.xml");
    fields.put("orderReference", "BBG-2026-4711");
    HttpResponse<String> response =
        session.post("/validator/korrigieren", MultipartBodies.form(fields, null, null, null));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("Ihre Eingabe")
        .contains("BBG-2026-4711")
        .contains("Erneute Prüfung der korrigierten Datei: gültig.")
        .contains("rechnung.xml");
    assertThat(reports.count()).isEqualTo(before);
  }

  @Test
  void anOverlongOrderReferenceIsRefusedWithAReadableMessage() throws Exception {
    Session session = openValidatorPage();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("_csrf", session.csrfToken());
    fields.put("document", documentField(upload(INVALID_SAMPLE).body()));
    fields.put("orderReference", "x".repeat(256));

    HttpResponse<String> response =
        session.post("/validator/korrigieren", MultipartBodies.form(fields, null, null, null));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("höchstens 255 Zeichen")
        .contains("Eingabe erforderlich")
        // What was typed stays in the field, cut to the field's limit.
        .contains("value=\"" + "x".repeat(255) + "\"");
  }

  @Test
  void aDamagedDocumentFieldAsksForANewUploadInsteadOfFailing() throws Exception {
    Session session = openValidatorPage();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("_csrf", session.csrfToken());
    fields.put("document", "not base64 !!!");
    fields.put("orderReference", "A-1");

    HttpResponse<String> response =
        session.post("/validator/korrigieren", MultipartBodies.form(fields, null, null, null));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Bitte laden Sie sie erneut hoch");
  }

  @Test
  void theCorrectionRouteRequiresTheCsrfToken() throws Exception {
    Session session = openValidatorPage();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("document", "PEludm9pY2UvPg==");

    HttpResponse<String> response =
        session.post("/validator/korrigieren", MultipartBodies.form(fields, null, null, null));

    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  void withAiDisabledTheProposalMakesNoAiClaim() throws Exception {
    assertThat(upload(PEPPOL_WITHOUT_ENDPOINTS).body())
        .contains("Korrekturvorschlag mit Nachweis")
        .doesNotContain("KI-Erklärung je Befund");
  }

  private static String documentField(String html) {
    Matcher matcher = DOCUMENT_INPUT.matcher(html);
    assertThat(matcher.find()).withFailMessage("no document field in the report").isTrue();
    return matcher.group(1);
  }

  // --------------------------------------------------------- the AI off-switch

  @Test
  void withAiDisabledNoExplainButtonIsOffered() throws Exception {
    // features.ai-explanations defaults to false, so this is the shipped default's behaviour.
    assertThat(upload(INVALID_SAMPLE).body()).doesNotContain("Fehler erklären");
  }

  @Test
  void withAiDisabledTheExplainRouteStillAnswersWithTheFriendlyNotice() throws Exception {
    // The route stays reachable so a stale page's button cannot produce a 404 or a 500; it answers
    // the same "not available" fragment a provider outage would.
    Session session = openValidatorPage();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("_csrf", session.csrfToken());
    fields.put("ruleId", "AT-B2G-01");
    fields.put("severity", "ERROR");
    fields.put("location", "/Invoice");
    fields.put("messageDe", "Auftragsreferenz fehlt");
    fields.put("messageEn", "Order reference missing");
    fields.put("sourceFormat", "ebinterface-6.1");
    fields.put("profile", "at-b2g");

    HttpResponse<String> response =
        session.post("/validator/erklaeren", MultipartBodies.form(fields, null, null, null));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Erklärung nicht verfügbar");
  }

  // ------------------------------------------------- chain routing (ADR-0009)

  @Test
  void theDashboardIsNotReachableAnonymously() throws Exception {
    // No OAuth2 client registration is configured in this context (deliberately — see
    // SecurityConfig),
    // so the web chain has no login entry point and refuses outright rather than redirecting.
    // Either
    // way it must not be readable.
    assertThat(get("/app").statusCode()).isNotEqualTo(200);
  }

  @Test
  void theStatelessApiChainStillOwnsItsPaths() throws Exception {
    // ADR-0009's load-bearing detail: /api/** must keep answering as an API (401, not a redirect to
    // a
    // login page). A 3xx here would mean the chain order broke and the browser chain swallowed it.
    HttpResponse<String> response = get("/api/v1/invoices");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void theOpenApiDocumentIsStillAnonymousAndStillJson() throws Exception {
    // Also the API chain's, and also easy to lose to the catch-all: Swagger UI is an Abnahme item
    // from M3 and must not start requiring a login because M5 added a login.
    HttpResponse<String> response = get("/v3/api-docs");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"openapi\"");
  }

  // -------------------------------------------------------------------- helpers

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> upload(Path file) throws Exception {
    Session session = openValidatorPage();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("_csrf", session.csrfToken());
    return session.post(
        "/validator/pruefen",
        MultipartBodies.form(
            fields, "file", file.getFileName().toString(), Files.readAllBytes(file)));
  }

  /**
   * Fetches {@code /validator} and keeps what a browser would keep: the cookie and the CSRF token.
   */
  private Session openValidatorPage() throws Exception {
    HttpResponse<String> page = get("/validator");
    assertThat(page.statusCode()).isEqualTo(200);

    Matcher matcher = CSRF_INPUT.matcher(page.body());
    assertThat(matcher.find())
        .withFailMessage(
            "no _csrf hidden input on /validator — either CSRF is off (it must not be) or Thymeleaf"
                + " stopped injecting the token into th:action forms")
        .isTrue();

    String cookie =
        page.headers().firstValue("set-cookie").map(value -> value.split(";", 2)[0]).orElse("");
    return new Session(matcher.group(1), cookie);
  }

  /** One browser-like session: the CSRF token from the rendered form and the session cookie. */
  private final class Session {

    private final String csrfToken;
    private final String cookie;

    private Session(String csrfToken, String cookie) {
      this.csrfToken = csrfToken;
      this.cookie = cookie;
    }

    private String csrfToken() {
      return csrfToken;
    }

    private HttpResponse<String> post(String path, MultipartBodies.Multipart body)
        throws IOException, InterruptedException {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url(path)))
              .header("Content-Type", body.contentType())
              .POST(HttpRequest.BodyPublishers.ofByteArray(body.body()));
      Optional.of(cookie)
          .filter(value -> !value.isBlank())
          .ifPresent(value -> request.header("Cookie", value));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
