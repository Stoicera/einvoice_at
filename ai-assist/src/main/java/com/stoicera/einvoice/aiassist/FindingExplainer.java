package com.stoicera.einvoice.aiassist;

import com.stoicera.einvoice.aiassist.internal.ExplanationCache;
import com.stoicera.einvoice.aiassist.internal.PiiScrubber;
import com.stoicera.einvoice.aiassist.internal.PromptTemplate;
import com.stoicera.einvoice.aiassist.llm.LlmClient;
import com.stoicera.einvoice.aiassist.llm.LlmCompletion;
import com.stoicera.einvoice.aiassist.llm.LlmException;
import com.stoicera.einvoice.aiassist.llm.LlmPrompt;
import com.stoicera.einvoice.core.validation.Finding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns one validator {@link Finding} into a plain-German explanation with a concrete fix.
 *
 * <p>This is the module's whole public purpose, and the answer to the gap M4 recorded honestly: the
 * OpenPeppol rule sets ship English message text only, so for a Peppol finding the "German message"
 * this platform shows is a German frame around official English wording. Explaining that finding in
 * actual German is what this class does.
 *
 * <h2>The degradation contract</h2>
 *
 * <p><strong>{@link #explain} never throws.</strong> Not "rarely" — never: every failure path
 * returns {@link Optional#empty()}, including a provider outage, a timeout, a refusal, an
 * unparseable answer, and a bug in this class's own prompt rendering. That is the load-bearing half
 * of "KI abschaltbar ohne Funktionsverlust": the caller renders the report and a friendly notice,
 * and a reader who never clicks "Erklären" cannot tell the provider is down. A failure is logged at
 * WARN so an operator can, with the rule id but never the message text.
 *
 * <h2>What is sent, and what is not</h2>
 *
 * <p>The document is <em>never</em> sent — only the finding, and only after {@link PiiScrubber} has
 * masked it. SPEC §6 additionally described sending "an XML fragment (max ~40 lines around
 * location)"; that is deliberately <strong>not built</strong>, and the reason is structural rather
 * than an omission: this platform retains no upload (the public validator's GDPR promise) and
 * stores no XML (invoices are regenerated from canonical JSON), so at the moment a user clicks
 * "Erklären" there is no document in existence to quote 40 lines of. Building one would mean
 * starting to keep uploads, which is a worse trade than a slightly less specific explanation.
 * Recorded in ADR-0010 and {@code docs/privacy.md}.
 *
 * <p>Thread-safe: the cache synchronizes, the templates are immutable, and the client is required
 * to be safe for concurrent use.
 */
public final class FindingExplainer {

  private static final Logger log = LoggerFactory.getLogger(FindingExplainer.class);

  static final String SYSTEM_TEMPLATE = "prompts/finding-explanation.system.v1.st";
  static final String USER_TEMPLATE = "prompts/finding-explanation.user.v1.st";

  /**
   * Ceiling on the stored explanation, matching {@code Finding}'s own {@code aiExplanation} cap. A
   * provider that ignores {@code max_tokens} and answers with an essay must not make {@link
   * Finding#withAiExplanation} throw — that would turn a too-long answer into a failed request
   * instead of a trimmed one.
   */
  private static final int MAX_EXPLANATION_LENGTH = 8192;

  /** Shown in place of the location when a finding has none (a document-level rule). */
  private static final String NO_LOCATION = "keine Fundstelle angegeben";

  private final LlmClient client;
  private final ExplanationCache cache;
  private final PromptTemplate systemTemplate;
  private final PromptTemplate userTemplate;

  public FindingExplainer(LlmClient client) {
    this(client, new ExplanationCache());
  }

  public FindingExplainer(LlmClient client, ExplanationCache cache) {
    if (client == null) {
      throw new IllegalArgumentException("LLM client must not be null");
    }
    if (cache == null) {
      throw new IllegalArgumentException("Explanation cache must not be null");
    }
    this.client = client;
    this.cache = cache;
    // Loaded once, at construction: a missing prompt resource is a packaging fault and should fail
    // when the bean is built, not on the first user click.
    this.systemTemplate = PromptTemplate.load(SYSTEM_TEMPLATE);
    this.userTemplate = PromptTemplate.load(USER_TEMPLATE);
  }

  /**
   * Explains {@code finding}, or returns empty if it cannot be explained right now.
   *
   * @param finding the finding to explain
   * @param context what document it came from, and what to redact — see {@link ExplanationContext}
   * @return the German explanation, or {@link Optional#empty()} on any failure
   */
  public Optional<String> explain(Finding finding, ExplanationContext context) {
    if (finding == null || context == null) {
      // A programming error in the caller, but this method's contract is "never throw", and
      // throwing
      // here would break the degradation promise for the one caller that matters most.
      log.warn("Explanation requested with a null finding or context; skipping");
      return Optional.empty();
    }

    try {
      String userMessage = renderUserMessage(finding, context);
      String key = cacheKey(finding.ruleId(), userMessage);

      Optional<String> cached = cache.get(key);
      if (cached.isPresent()) {
        return cached;
      }

      LlmCompletion completion =
          client.complete(new LlmPrompt(systemTemplate.render(Map.of()), userMessage));
      // No blank check: LlmCompletion's own invariant already rejects blank text, and the adapter
      // turns a content-less 200 into an LlmException. A second check here would be a branch no
      // test
      // could reach.
      String explanation = bound(completion.text().strip());
      cache.put(key, explanation);
      return Optional.of(explanation);
    } catch (LlmException e) {
      // The expected failure: the provider is unreachable, slow, or refused. Rule id only — the
      // message text is document content and does not belong in an operational log.
      log.warn(
          "LLM explanation unavailable for rule {} ({}): {}",
          finding.ruleId(),
          e.isRetryable() ? "retryable" : "permanent",
          e.getMessage());
      return Optional.empty();
    } catch (RuntimeException e) {
      // A bug here — a renamed placeholder, a template that stopped matching its call site — must
      // still degrade rather than fail a page. Logged with its stack trace precisely because,
      // unlike
      // an outage, it is this repository's own fault and someone has to see it.
      log.warn("Explanation failed unexpectedly for rule {}", finding.ruleId(), e);
      return Optional.empty();
    }
  }

  private String renderUserMessage(Finding finding, ExplanationContext context) {
    var sensitive = context.sensitiveLiterals();
    // LinkedHashMap, not Map.of: the values are scrubbed strings and a null would be a bug worth
    // seeing as a NullPointerException here rather than as a silently absent placeholder. Ordered
    // so
    // a debugger shows the prompt fields in template order.
    Map<String, String> values = new LinkedHashMap<>();
    values.put("sourceFormat", PiiScrubber.scrub(context.sourceFormat(), sensitive));
    values.put("profile", PiiScrubber.scrub(context.profile(), sensitive));
    values.put("ruleId", PiiScrubber.scrub(finding.ruleId(), sensitive));
    values.put("severity", finding.severity().name());
    values.put(
        "location",
        finding.location() == null || finding.location().isBlank()
            ? NO_LOCATION
            : PiiScrubber.scrub(finding.location(), sensitive));
    values.put("messageDe", PiiScrubber.scrub(finding.messageDe(), sensitive));
    values.put("messageEn", PiiScrubber.scrub(finding.messageEn(), sensitive));
    return userTemplate.render(values);
  }

  /**
   * SPEC §6's {@code (ruleId, fragmentHash)} key, with the rendered prompt standing in for the
   * fragment there is none of.
   *
   * <p>Hashing the whole rendered message rather than just the rule id is what makes the cache
   * correct: the same rule reported with a different message, location, format or profile is a
   * different question and deserves its own answer.
   *
   * <p>Hashing the <strong>scrubbed</strong> message has a consequence worth being deliberate
   * about: two documents that violate {@code AT-B2G-02} with two different IBANs both scrub to
   * {@code "IBAN [IBAN] ungültig"} and therefore share one explanation. That is right, not a leak —
   * the model never saw either IBAN, so its answer cannot depend on which one it was, and the hit
   * costs one fewer paid call and one less value leaving the platform. Pinned by {@code
   * FindingExplainerTest.sharesOneExplanationBetweenFindingsThatDifferOnlyInMaskedPii}.
   */
  private static String cacheKey(String ruleId, String userMessage) {
    return ruleId + '|' + sha256Hex(userMessage);
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // Mandated on every JVM; its absence is a broken runtime, not a recoverable state. Same
      // reasoning as InvoiceService.sha256Hex.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String bound(String explanation) {
    return explanation.length() <= MAX_EXPLANATION_LENGTH
        ? explanation
        : explanation.substring(0, MAX_EXPLANATION_LENGTH);
  }
}
