package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stoicera.einvoice.app.http.RequestBodyTooLargeException;
import com.stoicera.einvoice.app.problem.Problems;
import com.stoicera.einvoice.app.security.ApiKeyNotFoundException;
import com.stoicera.einvoice.app.security.TooManyApiKeysException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * The catch-all 500's two-sided contract: the caller learns nothing, the operator learns
 * everything.
 *
 * <p>Both halves matter and they pull in opposite directions, which is why they are pinned
 * together. The M3 hostile review found the handler honouring only the first half — it returned a
 * generic problem and discarded the exception, so a production 500 left no stack trace anywhere and
 * the incident could not be investigated (finding F3). A future "let's log the message into the
 * detail for easier debugging" would break the other half just as quietly.
 *
 * <p>The log assertion reads through a Logback {@link ListAppender} attached to the handler's own
 * logger. {@code ResponseEntityExceptionHandler} logs through Commons Logging, which spring-jcl
 * routes to SLF4J under the same logger name, so the appender sees it.
 */
class ApiExceptionHandlerTest {

  private static final String SECRET_DETAIL = "jdbc:postgresql://db/einvoice?password=hunter2";

  private final ApiExceptionHandler handler = new ApiExceptionHandler();
  private ch.qos.logback.classic.Logger handlerLogger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureLogs() {
    handlerLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    captured = new ListAppender<>();
    captured.start();
    handlerLogger.addAppender(captured);
  }

  @AfterEach
  void releaseLogs() {
    handlerLogger.detachAppender(captured);
  }

  @Test
  void anUnexpectedExceptionBecomesA500ThatLeaksNothingToTheCaller() {
    ProblemDetail problem = handler.handleUnexpected(new IllegalStateException(SECRET_DETAIL));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(problem.getType()).isEqualTo(Problems.type("internal-error"));
    assertThat(problem.getTitle()).isEqualTo("Internal server error");
    assertThat(problem.getDetail())
        .isEqualTo("An unexpected error occurred while processing the request.")
        // Neither the exception's message nor its class name may appear anywhere in the response.
        .doesNotContain(SECRET_DETAIL)
        .doesNotContain("IllegalStateException");
  }

  @Test
  void anUnknownApiKeyGetsItsOwnNotFoundSlugRatherThanTheFrameworkDefault() {
    // ApiKeyService used to throw ResponseStatusException, which left this condition speaking the
    // framework's generic ".../not-found" type. ADR-0006 promises one slug per condition.
    ProblemDetail problem =
        handler.handleApiKeyNotFound(new ApiKeyNotFoundException(UUID.randomUUID()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getType()).isEqualTo(Problems.type("api-key-not-found"));
    assertThat(problem.getDetail())
        .isEqualTo("No API key with the given id exists for this tenant.");
  }

  @Test
  void reachingTheApiKeyLimitIsAConflictNamingTheLimit() {
    ProblemDetail problem = handler.handleTooManyApiKeys(new TooManyApiKeysException(25));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getType()).isEqualTo(Problems.type("api-key-limit-reached"));
    // The caller is told the number and how to make room — this is a state they can resolve.
    assertThat(problem.getDetail()).contains("25").contains("Revoke");
  }

  @Test
  void aFrameworkProblemWithoutATypeGetsTheProjectNamespaceStamped() {
    // Spring MVC's own exceptions (415, 405, a missing multipart part, …) arrive with the RFC's
    // default about:blank type. Left alone, the API would speak two vocabularies at once.
    ProblemDetail framework =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "nope");

    ProblemDetail stamped = stampInternal(framework, HttpStatus.UNSUPPORTED_MEDIA_TYPE);

    assertThat(stamped.getType()).isEqualTo(Problems.type("unsupported-media-type"));
    // A title is filled in from the status when the framework left none.
    assertThat(stamped.getTitle()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase());
    // The framework's own status and detail survive untouched.
    assertThat(stamped.getStatus()).isEqualTo(415);
    assertThat(stamped.getDetail()).isEqualTo("nope");
  }

  @Test
  void aProblemThatAlreadyCarriesATypeIsLeftAlone() {
    ProblemDetail alreadyTyped = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    alreadyTyped.setType(Problems.type("duplicate-invoice"));
    alreadyTyped.setTitle("Duplicate invoice number");

    ProblemDetail result = stampInternal(alreadyTyped, HttpStatus.CONFLICT);

    assertThat(result.getType()).isEqualTo(Problems.type("duplicate-invoice"));
    assertThat(result.getTitle()).isEqualTo("Duplicate invoice number");
  }

  @Test
  void theSlugFollowsTheStatusEnumNameNotTheNumber() {
    // 413 resolves to CONTENT_TOO_LARGE in Spring Framework 7 (the successor to the deprecated
    // PAYLOAD_TOO_LARGE name), which is what the body-size cap and the multipart cap both produce.
    assertThat(stampInternal(ProblemDetail.forStatus(413), HttpStatus.CONTENT_TOO_LARGE).getType())
        .isEqualTo(Problems.type("content-too-large"));
  }

  @Test
  void anUnknownStatusCodeFallsBackToTheGenericErrorSlug() {
    // A status outside the HttpStatus enum has no reason phrase to derive a slug from; it must
    // still land inside the project namespace rather than staying about:blank.
    HttpStatusCode nonStandard = HttpStatusCode.valueOf(499);

    assertThat(stampInternal(ProblemDetail.forStatus(499), nonStandard).getType())
        .isEqualTo(Problems.type("error"));
  }

  @Test
  void anOversizedChunkedBodyIsRecoveredAsA413FromDeepInTheCauseChain() {
    // The counting stream's failure is wrapped by Spring's message converters, so the handler has
    // to walk the chain; only the immediate cause would miss it and report a generic 400.
    HttpMessageNotReadableException wrapped =
        new HttpMessageNotReadableException(
            "I/O error while reading input message",
            new java.io.IOException("outer", new RequestBodyTooLargeException(2_097_152)),
            new MockHttpInputMessage(new byte[0]));

    ResponseEntity<Object> response =
        handler.handleHttpMessageNotReadable(
            wrapped, new HttpHeaders(), HttpStatus.BAD_REQUEST, webRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    ProblemDetail problem = (ProblemDetail) response.getBody();
    assertThat(problem.getType()).isEqualTo(Problems.type("content-too-large"));
    assertThat(problem.getDetail()).contains("2097152");
  }

  @Test
  void aGenuinelyUnreadableBodyKeepsItsBadRequest() {
    // The other side of the same branch: a malformed body is still a 400, not a mis-reported 413.
    HttpMessageNotReadableException malformed =
        new HttpMessageNotReadableException(
            "malformed", new MockHttpInputMessage("{ nope".getBytes(StandardCharsets.UTF_8)));

    ResponseEntity<Object> response =
        handler.handleHttpMessageNotReadable(
            malformed, new HttpHeaders(), HttpStatus.BAD_REQUEST, webRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void theSameExceptionIsLoggedInFullWithItsStackTraceForTheOperator() {
    IllegalStateException cause = new IllegalStateException(SECRET_DETAIL);

    handler.handleUnexpected(cause);

    assertThat(captured.list).hasSize(1);
    ILoggingEvent event = captured.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
    // The throwable itself is attached, not merely its message: that is what carries the stack
    // trace an incident is actually investigated from.
    assertThat(event.getThrowableProxy()).isNotNull();
    assertThat(event.getThrowableProxy().getMessage()).isEqualTo(SECRET_DETAIL);
    assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** Runs a framework-produced problem through the handler's type/title stamping. */
  private ProblemDetail stampInternal(ProblemDetail body, HttpStatusCode status) {
    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            new IllegalStateException("framework"), body, new HttpHeaders(), status, webRequest());
    return (ProblemDetail) response.getBody();
  }

  private static WebRequest webRequest() {
    return new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
  }
}
