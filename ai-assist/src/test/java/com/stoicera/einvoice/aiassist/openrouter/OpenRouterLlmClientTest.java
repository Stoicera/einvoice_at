package com.stoicera.einvoice.aiassist.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stoicera.einvoice.aiassist.llm.LlmCompletion;
import com.stoicera.einvoice.aiassist.llm.LlmException;
import com.stoicera.einvoice.aiassist.llm.LlmPrompt;
import com.stoicera.einvoice.aiassist.llm.LlmUsage;
import com.stoicera.einvoice.aiassist.llm.LlmUsageListener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the adapter against a real {@link HttpServer} on a loopback port — the same technique
 * {@code app}'s {@code JwtDecoderTest} uses for a throwaway JWKS. A mocked {@code HttpClient} would
 * prove the adapter calls a mock; a real socket proves the bytes on the wire, which is where the
 * two claims that matter live: that no sampling parameter is sent, and that a retry is a second
 * HTTP request.
 */
class OpenRouterLlmClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LlmPrompt PROMPT = new LlmPrompt("Du bist Assistent.", "Erkläre AT-B2G-01.");

  private HttpServer server;
  private final List<String> receivedBodies = new ArrayList<>();
  private final AtomicInteger requestCount = new AtomicInteger();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  // ---------------------------------------------------------------- happy path

  @Test
  void returnsTheCompletionAndTheProviderReportedUsage() throws Exception {
    respondWith(200, completionJson("Die Auftragsreferenz fehlt.", 120, 45, "0.00042"));

    LlmCompletion completion = client(1, LlmUsageListener.NONE).complete(PROMPT);

    assertThat(completion.text()).isEqualTo("Die Auftragsreferenz fehlt.");
    assertThat(completion.usage().model()).isEqualTo("anthropic/claude-sonnet-5");
    assertThat(completion.usage().promptTokens()).isEqualTo(120);
    assertThat(completion.usage().completionTokens()).isEqualTo(45);
    assertThat(completion.usage().totalTokens()).isEqualTo(165);
    assertThat(completion.usage().costUsd()).contains(new BigDecimal("0.00042"));
  }

  @Test
  void sendsTheModelMessagesAndBearerCredentialItWasConfiguredWith() throws Exception {
    respondWith(200, completionJson("ok", 1, 1, "0.1"));

    client(1, LlmUsageListener.NONE).complete(PROMPT);

    JsonNode body = JSON.readTree(receivedBodies.get(0));
    assertThat(body.get("model").asText()).isEqualTo("anthropic/claude-sonnet-5");
    assertThat(body.get("max_tokens").asInt()).isEqualTo(700);
    assertThat(body.get("messages")).hasSize(2);
    assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
    assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("Du bist Assistent.");
    assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
    assertThat(body.get("messages").get(1).get("content").asText()).isEqualTo("Erkläre AT-B2G-01.");
    // Asking the provider for the real charge is what keeps LlmUsage.costUsd honest.
    assertThat(body.get("usage").get("include").asBoolean()).isTrue();
  }

  @Test
  void sendsNoSamplingParameterAtAll() throws Exception {
    // Load-bearing, not tidiness: the current Anthropic models reject a non-default
    // temperature/top_p/
    // top_k with HTTP 400, and OpenRouter forwards the body as given. A well-meaning
    // "temperature: 0.2" here would break every request against this platform's own default model.
    respondWith(200, completionJson("ok", 1, 1, "0.1"));

    client(1, LlmUsageListener.NONE).complete(PROMPT);

    JsonNode body = JSON.readTree(receivedBodies.get(0));
    assertThat(body.has("temperature")).isFalse();
    assertThat(body.has("top_p")).isFalse();
    assertThat(body.has("top_k")).isFalse();
  }

  @Test
  void reportsUsageToTheListenerExactlyOncePerSuccess() throws Exception {
    respondWith(200, completionJson("ok", 7, 3, "0.5"));
    List<LlmUsage> observed = new ArrayList<>();

    client(1, observed::add).complete(PROMPT);

    assertThat(observed).hasSize(1);
    assertThat(observed.get(0).totalTokens()).isEqualTo(10);
  }

  // ------------------------------------------------------------------- retries

  @Test
  void retriesARetryableFailureAndSucceedsOnTheSecondAttempt() throws Exception {
    respondWithSequence(
        exchange -> write(exchange, 503, "{\"error\":\"overloaded\"}"),
        exchange -> write(exchange, 200, completionJson("Zweiter Versuch.", 1, 1, "0.1")));

    LlmCompletion completion = client(1, LlmUsageListener.NONE).complete(PROMPT);

    assertThat(completion.text()).isEqualTo("Zweiter Versuch.");
    assertThat(requestCount).hasValue(2);
  }

  @Test
  void givesUpAfterTheConfiguredRetriesAndReportsTheFailureAsRetryable() {
    respondWith(503, "{\"error\":\"overloaded\"}");

    LlmException thrown = failureOf(client(1, LlmUsageListener.NONE));

    assertThat(thrown.isRetryable()).isTrue();
    assertThat(thrown).hasMessageContaining("503");
    assertThat(requestCount).hasValue(2); // the first attempt plus one retry
  }

  @Test
  void doesNotRetryAClientError() {
    // A 401 will not become valid by asking again; retrying only doubles the latency the caller
    // pays
    // before degrading, and hammers a provider with a credential it already rejected.
    respondWith(401, "{\"error\":\"invalid api key\"}");

    LlmException thrown = failureOf(client(3, LlmUsageListener.NONE));

    assertThat(thrown.isRetryable()).isFalse();
    assertThat(requestCount).hasValue(1);
  }

  @Test
  void treatsTooManyRequestsAsRetryable() {
    respondWith(429, "{\"error\":\"rate limited\"}");

    LlmException thrown = failureOf(client(0, LlmUsageListener.NONE));

    assertThat(thrown.isRetryable()).isTrue();
    assertThat(requestCount).hasValue(1); // maxRetries 0 means one attempt, no retry
  }

  // -------------------------------------------------------------------- backoff

  /**
   * M5 hostile review, F6. The loop re-issued immediately, so a 429 — the provider explicitly
   * asking for less traffic — was answered by asking again in the same millisecond. Asserted
   * through the {@code Sleeper} seam rather than by measuring elapsed time, so the schedule is
   * pinned exactly and the test costs nothing.
   */
  @Test
  void waitsBeforeEachRetryAndDoublesTheWait() {
    respondWith(503, "{\"error\":\"overloaded\"}");
    List<Duration> waited = new ArrayList<>();

    failureOf(client(3, LlmUsageListener.NONE, waited::add));

    assertThat(requestCount).hasValue(4); // the first attempt plus three retries
    assertThat(waited)
        .containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
  }

  @Test
  void doesNotWaitAtAllWhenTheFirstAttemptSucceeds() throws Exception {
    respondWith(200, completionJson("Sofort.", 1, 1, "0.1"));
    List<Duration> waited = new ArrayList<>();

    client(2, LlmUsageListener.NONE, waited::add).complete(PROMPT);

    assertThat(waited).isEmpty();
  }

  @Test
  void honoursTheProvidersOwnRetryAfterInsteadOfGuessing() {
    // The provider is the only party that knows when its limit resets. Ignoring the header in
    // favour of a locally computed 500 ms is how a client turns a rate limit into a ban.
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Retry-After", "3");
          write(exchange, 429, "{\"error\":\"rate limited\"}");
        });
    List<Duration> waited = new ArrayList<>();

    failureOf(client(1, LlmUsageListener.NONE, waited::add));

    assertThat(waited).containsExactly(Duration.ofSeconds(3));
  }

  @Test
  void capsAnAbsurdRetryAfterRatherThanParkingTheThread() {
    // Retry-After is a third party's number and a request thread is ours. A day is not a wait.
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Retry-After", "86400");
          write(exchange, 429, "{\"error\":\"rate limited\"}");
        });
    List<Duration> waited = new ArrayList<>();

    failureOf(client(1, LlmUsageListener.NONE, waited::add));

    assertThat(waited).containsExactly(OpenRouterLlmClient.MAX_BACKOFF);
  }

  @Test
  void anInterruptDuringTheBackoffStopsRetryingAndPreservesTheFlag() {
    // Never swallow an interrupt: the thread was asked to stop, and retrying is precisely what an
    // interrupt says not to do. Same rule the send path already follows.
    respondWith(503, "{\"error\":\"overloaded\"}");
    OpenRouterSettings settings =
        new OpenRouterSettings(
            baseUri(), "sk-test", "anthropic/claude-sonnet-5", Duration.ofSeconds(5), 3, 700);
    OpenRouterLlmClient client =
        new OpenRouterLlmClient(
            HttpClient.newHttpClient(),
            settings,
            LlmUsageListener.NONE,
            duration -> {
              throw new InterruptedException("asked to stop");
            });

    LlmException thrown = failureOf(client);

    assertThat(thrown).hasMessageContaining("interrupted");
    assertThat(thrown.isRetryable()).isFalse();
    assertThat(requestCount).hasValue(1); // the first attempt only; no repeat after the interrupt
    assertThat(Thread.interrupted()).as("the interrupt flag is restored").isTrue();
  }

  @Test
  void fallsBackToTheScheduleWhenRetryAfterIsAnHttpDateOrNonsense() {
    // Only delta-seconds is honoured; an HTTP-date would mean trusting a third party's clock. An
    // unreadable header must fall back to the exponential schedule, never to zero.
    server.createContext(
        "/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().add("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");
          write(exchange, 429, "{\"error\":\"rate limited\"}");
        });
    List<Duration> waited = new ArrayList<>();

    failureOf(client(1, LlmUsageListener.NONE, waited::add));

    assertThat(waited).containsExactly(OpenRouterLlmClient.BASE_BACKOFF);
  }

  @Test
  void reportsNoUsageWhenEveryAttemptFails() {
    respondWith(500, "{}");
    List<LlmUsage> observed = new ArrayList<>();

    assertThatThrownBy(() -> client(1, observed::add).complete(PROMPT))
        .isInstanceOf(LlmException.class);

    assertThat(observed).isEmpty();
  }

  @Test
  void treatsATimeoutAsRetryable() {
    // The server never answers; the per-attempt timeout must fire and be classified as retryable
    // rather than escaping as a raw IOException.
    server.createContext(
        "/chat/completions",
        exchange -> {
          requestCount.incrementAndGet();
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    OpenRouterSettings settings =
        new OpenRouterSettings(
            baseUri(), "sk-test", "anthropic/claude-sonnet-5", Duration.ofMillis(250), 0, 700);

    LlmException thrown =
        failureOf(
            new OpenRouterLlmClient(HttpClient.newHttpClient(), settings, LlmUsageListener.NONE));

    assertThat(thrown.isRetryable()).isTrue();
  }

  // --------------------------------------------------------- malformed answers

  @Test
  void rejectsAnUnparseableBody() {
    respondWith(200, "not json at all");

    LlmException thrown = failureOf(client(1, LlmUsageListener.NONE));

    assertThat(thrown.isRetryable()).isFalse();
    assertThat(thrown).hasMessageContaining("unparseable");
    assertThat(requestCount).hasValue(1); // a broken contract is not retried
  }

  @Test
  void rejectsAnAnswerWithNoChoices() {
    respondWith(200, "{\"choices\":[],\"usage\":{}}");

    assertThat(failureOf(client(0, LlmUsageListener.NONE)))
        .hasMessageContaining("without a choice");
  }

  @Test
  void rejectsAnEmptyCompletion() {
    // A real OpenRouter outcome (an upstream refusal, or a cut-off before any text). It is not an
    // explanation, so it must not be presented as one.
    respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"   \"}}],\"usage\":{}}");

    assertThat(failureOf(client(0, LlmUsageListener.NONE))).hasMessageContaining("empty content");
  }

  @Test
  void boundsProviderTextEchoedIntoAnErrorMessage() throws Exception {
    // An error body is attacker-influenceable in length and content. Unbounded, it becomes an
    // unbounded-message and log-injection vector — the hazard the M2 review closed for the
    // validator's
    // foreign-text seams and the M4 review closed again for /convert.
    respondWith(500, "x".repeat(200_000) + "\nInjected: log line");

    LlmException thrown = failureOf(client(0, LlmUsageListener.NONE));

    assertThat(thrown.getMessage()).hasSizeLessThan(200);
    assertThat(thrown.getMessage()).doesNotContain("\n");
  }

  @Test
  void neverPutsTheApiKeyInAnErrorMessage() {
    respondWith(403, "{\"error\":\"forbidden\"}");

    assertThat(failureOf(client(0, LlmUsageListener.NONE)).getMessage()).doesNotContain("sk-test");
  }

  // ------------------------------------------------------------- usage parsing

  @Test
  void reportsNoCostWhenTheProviderReportsNone() throws Exception {
    respondWith(
        200,
        "{\"model\":\"anthropic/claude-sonnet-5\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");

    LlmCompletion completion = client(0, LlmUsageListener.NONE).complete(PROMPT);

    // Empty, not zero: "cost unknown" and "cost nothing" are different facts and a metric must not
    // conflate them.
    assertThat(completion.usage().costUsd()).isEmpty();
  }

  @Test
  void ignoresANonNumericOrNegativeCost() throws Exception {
    respondWith(
        200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"cost\":\"gratis\"}}");
    assertThat(client(0, LlmUsageListener.NONE).complete(PROMPT).usage().costUsd()).isEmpty();

    stopServer();
    startServer();
    respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"cost\":-1}}");
    assertThat(client(0, LlmUsageListener.NONE).complete(PROMPT).usage().costUsd()).isEmpty();
  }

  @Test
  void fallsBackToTheRequestedModelWhenTheProviderNamesNone() throws Exception {
    respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{}}");

    assertThat(client(0, LlmUsageListener.NONE).complete(PROMPT).usage().model())
        .isEqualTo("anthropic/claude-sonnet-5");
  }

  @Test
  void reportsTheModelThatActuallyServedTheRequest() throws Exception {
    // Providers route and substitute; the metric should say what ran, not what was asked for.
    respondWith(
        200,
        "{\"model\":\"anthropic/claude-sonnet-5:beta\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
            + "\"usage\":{}}");

    assertThat(client(0, LlmUsageListener.NONE).complete(PROMPT).usage().model())
        .isEqualTo("anthropic/claude-sonnet-5:beta");
  }

  // ------------------------------------------------------------- argument guards

  @Test
  void rejectsNullConstructorArgumentsAndANullPrompt() {
    OpenRouterSettings settings = OpenRouterSettings.withDefaults("sk-test");

    assertThatThrownBy(() -> new OpenRouterLlmClient(null, LlmUsageListener.NONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OpenRouterLlmClient(settings, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LlmUsageListener.NONE");
    assertThatThrownBy(() -> client(0, LlmUsageListener.NONE).complete(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theDefaultConstructorBuildsItsOwnHttpClient() throws Exception {
    // The production constructor is otherwise never exercised: every other test injects a client.
    respondWith(200, completionJson("ok", 1, 1, "0.1"));
    OpenRouterSettings settings =
        new OpenRouterSettings(
            baseUri(), "sk-test", "anthropic/claude-sonnet-5", Duration.ofSeconds(5), 0, 700);

    assertThat(new OpenRouterLlmClient(settings, LlmUsageListener.NONE).complete(PROMPT).text())
        .isEqualTo("ok");
  }

  // --------------------------------------------------------------------- helpers

  /**
   * Runs a completion that is expected to fail and returns the failure.
   *
   * <p>Written out rather than using AssertJ's {@code catchThrowableOfType}, whose two overloads
   * swapped argument order between versions — a test helper should not be the thing that breaks on
   * a dependency bump.
   */
  private LlmException failureOf(OpenRouterLlmClient client) {
    try {
      client.complete(PROMPT);
    } catch (LlmException expected) {
      return expected;
    }
    throw new AssertionError("expected the completion to fail, but it succeeded");
  }

  private OpenRouterLlmClient client(int maxRetries, LlmUsageListener listener) {
    // A no-op sleeper by default: every test in this class that is not ABOUT the backoff would
    // otherwise pay it in wall-clock time.
    return client(maxRetries, listener, duration -> {});
  }

  private OpenRouterLlmClient client(
      int maxRetries, LlmUsageListener listener, Consumer<Duration> onSleep) {
    OpenRouterSettings settings =
        new OpenRouterSettings(
            baseUri(),
            "sk-test",
            "anthropic/claude-sonnet-5",
            Duration.ofSeconds(5),
            maxRetries,
            700);
    return new OpenRouterLlmClient(HttpClient.newHttpClient(), settings, listener, onSleep::accept);
  }

  private URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void respondWith(int status, String body) {
    server.createContext("/chat/completions", exchange -> write(exchange, status, body));
  }

  /**
   * Responds with each handler in turn, so a retry can be given a different answer than the first
   * try.
   */
  @SafeVarargs
  private void respondWithSequence(Consumer<HttpExchange>... handlers) {
    server.createContext(
        "/chat/completions",
        exchange -> {
          int index = Math.min(requestCount.get(), handlers.length - 1);
          handlers[index].accept(exchange);
        });
  }

  private void write(HttpExchange exchange, int status, String body) {
    requestCount.incrementAndGet();
    try {
      receivedBodies.add(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    } catch (IOException e) {
      throw new IllegalStateException("test server failed to answer", e);
    }
  }

  private static String completionJson(
      String content, int promptTokens, int completionTokens, String cost) {
    return """
        {"model":"anthropic/claude-sonnet-5",
         "choices":[{"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":%d,"completion_tokens":%d,"cost":%s}}
        """
        .formatted(content, promptTokens, completionTokens, cost);
  }
}
