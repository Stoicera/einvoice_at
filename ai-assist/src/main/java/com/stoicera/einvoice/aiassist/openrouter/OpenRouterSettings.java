package com.stoicera.einvoice.aiassist.openrouter;

import java.net.URI;
import java.time.Duration;

/**
 * Everything {@link OpenRouterLlmClient} needs to reach a provider. Supplied by {@code app} from
 * the environment; the documented defaults live in {@code app}'s {@code application.yml} and {@code
 * .env.example}, and are named here as constants so a reader of this module can see what the
 * platform actually runs with.
 *
 * @param baseUrl the OpenAI-compatible API root, without a trailing slash — {@code
 *     /chat/completions} is appended. Configurable so a LiteLLM gateway or a self-hosted proxy can
 *     be substituted for OpenRouter with no code change.
 * @param apiKey the bearer credential. Never logged, never included in an exception message.
 * @param model the provider's model slug (see {@link #DEFAULT_MODEL})
 * @param timeout per-attempt ceiling, applied to the HTTP request as a whole
 * @param maxRetries retries <em>after</em> the first attempt, so {@code 1} means at most two calls.
 *     Only a retryable failure is retried — see {@link
 *     com.stoicera.einvoice.aiassist.llm.LlmException#isRetryable()}.
 * @param maxOutputTokens ceiling on one explanation's length
 */
public record OpenRouterSettings(
    URI baseUrl,
    String apiKey,
    String model,
    Duration timeout,
    int maxRetries,
    int maxOutputTokens) {

  /** OpenRouter's OpenAI-compatible API root. */
  public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

  /**
   * The default model slug.
   *
   * <p><strong>Kept at Sonnet tier, and updated from a stale id.</strong> SPEC §6 pinned {@code
   * anthropic/claude-sonnet-4.5}; that model has a successor, and the id is corrected here rather
   * than left to 404 at the first real call. The <em>tier</em> is the owner's documented choice and
   * is deliberately not changed on this platform's behalf: an explanation is a short, bounded
   * answer about a published Schematron rule, generated on a page that is free to the public, so
   * per-explanation cost is a real constraint rather than a hypothetical one. Anything else is one
   * environment variable away — see {@code AI_MODEL} in {@code .env.example} and ADR-0010.
   */
  public static final String DEFAULT_MODEL = "anthropic/claude-sonnet-5";

  /** SPEC §6: "timeout 15 s". */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

  /** SPEC §6: "retries 1". */
  public static final int DEFAULT_MAX_RETRIES = 1;

  /**
   * Enough for the two short German paragraphs the prompt asks for, and a hard bound on what one
   * finding can cost. {@code Finding.aiExplanation} caps at 8192 <em>characters</em> anyway, so a
   * ceiling in this range keeps the model from writing an answer that would only be truncated.
   */
  public static final int DEFAULT_MAX_OUTPUT_TOKENS = 700;

  public OpenRouterSettings {
    if (baseUrl == null) {
      throw new IllegalArgumentException("OpenRouter baseUrl must not be null");
    }
    if (!baseUrl.isAbsolute()) {
      throw new IllegalArgumentException("OpenRouter baseUrl must be absolute, was " + baseUrl);
    }
    requireNonBlank(apiKey, "OpenRouter apiKey");
    requireNonBlank(model, "OpenRouter model");
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("OpenRouter timeout must be positive, was " + timeout);
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException(
          "OpenRouter maxRetries must not be negative, was " + maxRetries);
    }
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException(
          "OpenRouter maxOutputTokens must be positive, was " + maxOutputTokens);
    }
  }

  /** The settings this platform runs with by default, for a given credential. */
  public static OpenRouterSettings withDefaults(String apiKey) {
    return new OpenRouterSettings(
        URI.create(DEFAULT_BASE_URL),
        apiKey,
        DEFAULT_MODEL,
        DEFAULT_TIMEOUT,
        DEFAULT_MAX_RETRIES,
        DEFAULT_MAX_OUTPUT_TOKENS);
  }

  /** The completions endpoint derived from {@link #baseUrl}. */
  URI completionsEndpoint() {
    String root = baseUrl.toString();
    String trimmed = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    return URI.create(trimmed + "/chat/completions");
  }

  /**
   * Redacted on purpose: this record holds a credential, and a record's generated {@code toString}
   * prints every component. Without this override, one {@code log.debug("settings {}", settings)} —
   * or an exception message built by string concatenation — would put the API key in the log. That
   * is the kind of leak that is invisible in review and permanent in a log aggregator.
   */
  @Override
  public String toString() {
    return "OpenRouterSettings[baseUrl=%s, model=%s, timeout=%s, maxRetries=%d, maxOutputTokens=%d, apiKey=***]"
        .formatted(baseUrl, model, timeout, maxRetries, maxOutputTokens);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
