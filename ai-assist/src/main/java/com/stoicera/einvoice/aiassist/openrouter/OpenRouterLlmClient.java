package com.stoicera.einvoice.aiassist.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stoicera.einvoice.aiassist.llm.LlmClient;
import com.stoicera.einvoice.aiassist.llm.LlmCompletion;
import com.stoicera.einvoice.aiassist.llm.LlmException;
import com.stoicera.einvoice.aiassist.llm.LlmPrompt;
import com.stoicera.einvoice.aiassist.llm.LlmUsage;
import com.stoicera.einvoice.aiassist.llm.LlmUsageListener;
import com.stoicera.einvoice.core.text.Texts;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link LlmClient} over OpenRouter's OpenAI-compatible {@code POST /chat/completions}.
 *
 * <h2>Why the JDK's HttpClient</h2>
 *
 * <p>One POST with a JSON body and a bearer header. {@code java.net.http.HttpClient} does that,
 * ships with the platform, and keeps this module's dependency list at "core + Jackson" — which is
 * what lets {@code ai-assist} stay a plain-Java module testable against a loopback server rather
 * than a mock. Adding a client library would buy nothing here and would need keeping in step with
 * Boot's.
 *
 * <h2>What is deliberately not sent</h2>
 *
 * <p>No {@code temperature}, {@code top_p} or {@code top_k}. The current Anthropic models reject a
 * non-default value for any of the three with HTTP 400 (removed with Opus 4.7 / Sonnet 5), and
 * OpenRouter forwards the body it is given — so sending a "sensible" {@code temperature: 0.2} would
 * make every request fail against this platform's own default model. Determinism and tone come from
 * the prompt template instead. See {@link LlmPrompt} and ADR-0010.
 *
 * <p>{@code usage: {include: true}} <em>is</em> sent: it makes the provider return the actual
 * charge for the call, which is what {@link LlmUsage} reports rather than a locally computed guess.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Every failure becomes an {@link LlmException} — nothing else escapes, including runtime
 * exceptions from JSON parsing, because a caller degrading gracefully must only have to catch one
 * thing. A transport error, a timeout, HTTP 429 and HTTP 5xx are retryable; HTTP 4xx and an
 * unintelligible body are not (a rejected request and a broken contract do not fix themselves). At
 * most {@link OpenRouterSettings#maxRetries()} retries follow the first attempt.
 *
 * <p><strong>Nothing untrusted is echoed unbounded.</strong> Provider text in an exception message
 * goes through {@link Texts#safeEcho}, the same discipline the M2 hostile review established for
 * the validator's foreign-text seams and the M4 review re-established for {@code /convert}: an
 * error body is attacker-influenceable in length and content, and a raw echo turns it into a
 * log-injection and unbounded-message vector. The API key never appears in a message at all.
 *
 * <p>Thread-safe: {@code HttpClient} and {@code ObjectMapper} both are, and this class holds no
 * other mutable state.
 */
public final class OpenRouterLlmClient implements LlmClient {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Bound on the provider text echoed into an exception message before {@link Texts#safeEcho} caps
   * it further. Applied first so a multi-megabyte error body is never fully copied into a String
   * just to be truncated afterwards.
   */
  private static final int MAX_ERROR_BODY_READ = 512;

  private final HttpClient http;
  private final OpenRouterSettings settings;
  private final LlmUsageListener usageListener;

  /**
   * @param settings where to call and with what credential
   * @param usageListener receives the usage of every successful call; {@link LlmUsageListener#NONE}
   *     when metrics are not wired
   */
  public OpenRouterLlmClient(OpenRouterSettings settings, LlmUsageListener usageListener) {
    this(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        settings,
        usageListener);
  }

  /** Package-private: lets the tests supply a client pointed at a loopback server. */
  OpenRouterLlmClient(
      HttpClient http, OpenRouterSettings settings, LlmUsageListener usageListener) {
    if (settings == null) {
      throw new IllegalArgumentException("OpenRouter settings must not be null");
    }
    if (usageListener == null) {
      throw new IllegalArgumentException(
          "LLM usage listener must not be null; use LlmUsageListener.NONE for no metrics");
    }
    this.http = http;
    this.settings = settings;
    this.usageListener = usageListener;
  }

  @Override
  public LlmCompletion complete(LlmPrompt prompt) throws LlmException {
    if (prompt == null) {
      throw new IllegalArgumentException("LLM prompt must not be null");
    }
    byte[] body = requestBody(prompt);

    LlmException last = null;
    // maxRetries counts retries AFTER the first attempt, so the loop runs maxRetries + 1 times.
    for (int attempt = 0; attempt <= settings.maxRetries(); attempt++) {
      try {
        LlmCompletion completion = attempt(body);
        usageListener.onUsage(completion.usage());
        return completion;
      } catch (LlmException e) {
        if (!e.isRetryable()) {
          throw e;
        }
        last = e;
      }
    }
    // Unreachable with maxRetries >= 0 unless every attempt failed retryably, in which case `last`
    // holds the final failure; the assignment above guarantees it is non-null here.
    throw last;
  }

  private LlmCompletion attempt(byte[] body) throws LlmException {
    HttpRequest request =
        HttpRequest.newBuilder(settings.completionsEndpoint())
            .timeout(settings.timeout())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + settings.apiKey())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // Covers connect failures, resets and HttpTimeoutException (an IOException subclass) alike:
      // all three are "the provider might answer if asked again".
      throw new LlmException(
          "LLM provider request failed: " + e.getClass().getSimpleName(), true, e);
    } catch (InterruptedException e) {
      // Never swallow the interrupt: the thread was asked to stop, and the caller above must be
      // able
      // to see it. Not retryable — retrying is precisely what an interrupt says not to do.
      Thread.currentThread().interrupt();
      throw new LlmException("LLM provider request interrupted", false, e);
    }

    int status = response.statusCode();
    if (status != 200) {
      throw new LlmException(
          "LLM provider answered HTTP %d: %s"
              .formatted(status, Texts.safeEcho(truncate(response.body()))),
          status == 429 || status >= 500);
    }
    return parse(response.body());
  }

  private byte[] requestBody(LlmPrompt prompt) {
    ObjectNode root = JSON.createObjectNode();
    root.put("model", settings.model());
    root.put("max_tokens", settings.maxOutputTokens());

    ArrayNode messages = root.putArray("messages");
    messages.addObject().put("role", "system").put("content", prompt.systemMessage());
    messages.addObject().put("role", "user").put("content", prompt.userMessage());

    // Ask the provider to report what the call cost, so LlmUsage carries a real figure instead of a
    // number this repository would have to derive from a price table that goes stale.
    root.putObject("usage").put("include", true);

    return root.toString().getBytes(StandardCharsets.UTF_8);
  }

  private LlmCompletion parse(String body) throws LlmException {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException e) {
      throw new LlmException(
          "LLM provider answered unparseable JSON: " + Texts.safeEcho(truncate(body)), false, e);
    }

    JsonNode choices = root.path("choices");
    if (!choices.isArray() || choices.isEmpty()) {
      throw new LlmException(
          "LLM provider answered without a choice: " + Texts.safeEcho(truncate(body)), false);
    }
    String text = choices.get(0).path("message").path("content").asText("").trim();
    if (text.isEmpty()) {
      // A 200 with an empty completion is a real OpenRouter outcome (an upstream refusal, or a
      // response cut off at max_tokens before any text). It is not an explanation, so it must not
      // be
      // presented as one — the caller degrades exactly as it would for a transport failure.
      throw new LlmException("LLM provider answered with empty content", false);
    }

    // The model that actually served the request. Providers route and substitute, so this can
    // differ
    // from what was asked for; the metric should say what ran, not what was requested.
    String servingModel = root.path("model").asText("");
    JsonNode usage = root.path("usage");
    return new LlmCompletion(
        text,
        new LlmUsage(
            servingModel.isBlank() ? settings.model() : servingModel,
            Math.max(usage.path("prompt_tokens").asInt(0), 0),
            Math.max(usage.path("completion_tokens").asInt(0), 0),
            cost(usage)));
  }

  /**
   * The provider-reported cost, if it reported one that is a usable number. A missing, null,
   * non-numeric or negative value yields {@link Optional#empty()} rather than a fabricated zero —
   * "cost unknown" and "cost nothing" are different facts and a metric must not conflate them.
   */
  private static Optional<BigDecimal> cost(JsonNode usage) {
    JsonNode cost = usage.path("cost");
    if (!cost.isNumber()) {
      return Optional.empty();
    }
    BigDecimal value = cost.decimalValue();
    return value.signum() < 0 ? Optional.empty() : Optional.of(value);
  }

  private static String truncate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= MAX_ERROR_BODY_READ ? body : body.substring(0, MAX_ERROR_BODY_READ);
  }
}
