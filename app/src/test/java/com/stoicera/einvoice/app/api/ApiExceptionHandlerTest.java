package com.stoicera.einvoice.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stoicera.einvoice.app.problem.Problems;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

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
}
