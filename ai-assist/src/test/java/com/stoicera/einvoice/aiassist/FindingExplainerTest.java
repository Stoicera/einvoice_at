package com.stoicera.einvoice.aiassist;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.aiassist.internal.ExplanationCache;
import com.stoicera.einvoice.aiassist.llm.LlmClient;
import com.stoicera.einvoice.aiassist.llm.LlmCompletion;
import com.stoicera.einvoice.aiassist.llm.LlmException;
import com.stoicera.einvoice.aiassist.llm.LlmPrompt;
import com.stoicera.einvoice.aiassist.llm.LlmUsage;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two claims worth testing here are the two the milestone's Abnahme rests on: that nothing
 * unscrubbed reaches the client, and that <em>every</em> failure degrades to an empty {@link
 * Optional} instead of an exception.
 */
class FindingExplainerTest {

  private static final String IBAN = "AT611904300234573201";

  private static final ExplanationContext CONTEXT =
      ExplanationContext.of("ebinterface-6.1", "at-b2g");

  private static Finding finding(String ruleId, String messageDe) {
    return Finding.of(Severity.ERROR, ruleId, "/Invoice/PaymentMethod", messageDe, "English text");
  }

  // ------------------------------------------------------------------ happy path

  @Test
  void returnsTheModelsExplanation() {
    RecordingClient client = new RecordingClient("Die Auftragsreferenz fehlt. Ergänzen Sie sie.");

    Optional<String> explanation =
        new FindingExplainer(client)
            .explain(finding("AT-B2G-01", "Auftragsreferenz fehlt"), CONTEXT);

    assertThat(explanation).contains("Die Auftragsreferenz fehlt. Ergänzen Sie sie.");
  }

  @Test
  void sendsTheShippedSystemPromptAndTheFindingsFieldsAsTheUserMessage() {
    RecordingClient client = new RecordingClient("ok");

    new FindingExplainer(client).explain(finding("AT-B2G-01", "Auftragsreferenz fehlt"), CONTEXT);

    LlmPrompt prompt = client.prompts.get(0);
    assertThat(prompt.systemMessage()).contains("Antworte ausschließlich auf Deutsch");
    assertThat(prompt.userMessage())
        .contains("AT-B2G-01")
        .contains("ERROR")
        .contains("ebinterface-6.1")
        .contains("at-b2g")
        .contains("/Invoice/PaymentMethod")
        .contains("Auftragsreferenz fehlt")
        .contains("English text");
  }

  @Test
  void namesTheAbsenceOfALocationRatherThanSendingAnEmptyField() {
    RecordingClient client = new RecordingClient("ok");
    Finding documentLevel =
        Finding.of(Severity.WARN, "XML-02", null, "Dokument zu groß", "Document too large");

    new FindingExplainer(client).explain(documentLevel, CONTEXT);

    assertThat(client.prompts.get(0).userMessage()).contains("keine Fundstelle angegeben");
  }

  @Test
  void treatsABlankLocationTheSameAsAMissingOne() {
    RecordingClient client = new RecordingClient("ok");

    new FindingExplainer(client)
        .explain(Finding.of(Severity.INFO, "XSD-01", "   ", "Hinweis", "Note"), CONTEXT);

    assertThat(client.prompts.get(0).userMessage()).contains("keine Fundstelle angegeben");
  }

  // ---------------------------------------------------------------------- privacy

  @Test
  void scrubsPiiOutOfEverythingItSends() {
    RecordingClient client = new RecordingClient("ok");
    Finding withIban =
        Finding.of(
            Severity.ERROR,
            "AT-B2G-02",
            "/Invoice/PaymentMethod/IBAN",
            "IBAN " + IBAN + " ist ungültig; Kontakt office@stoicera-software.at",
            "IBAN " + IBAN + " is invalid");

    new FindingExplainer(client).explain(withIban, CONTEXT);

    String sent = client.prompts.get(0).userMessage();
    assertThat(sent)
        .doesNotContain(IBAN)
        .doesNotContain("office@stoicera-software.at")
        .contains("[IBAN]")
        .contains("[E-MAIL]")
        // The rule id must survive — it is what the explanation is about.
        .contains("AT-B2G-02");
  }

  @Test
  void redactsPartyNamesTheCallerSupplies() {
    RecordingClient client = new RecordingClient("ok");
    ExplanationContext withNames =
        ExplanationContext.withPartyNames(
            "ubl-invoice-2.1", "peppol-bis-billing-3.0", "Stoicera Software GesbR", null);
    Finding withName =
        Finding.of(
            Severity.ERROR,
            "PEPPOL-EN16931-R020",
            "/Invoice",
            "Stoicera Software GesbR hat keine elektronische Adresse",
            "Seller electronic address missing");

    new FindingExplainer(client).explain(withName, withNames);

    assertThat(client.prompts.get(0).userMessage())
        .doesNotContain("Stoicera")
        .contains("[NAME]")
        .contains("PEPPOL-EN16931-R020");
  }

  // ----------------------------------------------------------------------- cache

  @Test
  void servesARepeatedFindingFromTheCacheWithoutCallingTheProviderAgain() {
    RecordingClient client = new RecordingClient("Die Auftragsreferenz fehlt.");
    FindingExplainer explainer = new FindingExplainer(client);
    Finding same = finding("AT-B2G-01", "Auftragsreferenz fehlt");

    Optional<String> first = explainer.explain(same, CONTEXT);
    Optional<String> second = explainer.explain(same, CONTEXT);

    assertThat(second).isEqualTo(first);
    assertThat(client.prompts).hasSize(1);
  }

  @Test
  void sharesOneExplanationBetweenFindingsThatDifferOnlyInMaskedPii() {
    // Two documents, two different IBANs, same rule. Because the key is the SCRUBBED text, both
    // scrub to "IBAN [IBAN] ungültig" and share one explanation — which is correct rather than a
    // leak: the model never saw either IBAN, so its answer cannot depend on which one it was. The
    // effect is an extra cache hit, i.e. one fewer paid call and one less value leaving the
    // platform.
    RecordingClient client = new RecordingClient("Die Prüfsumme der IBAN stimmt nicht.");
    FindingExplainer explainer = new FindingExplainer(client);

    Optional<String> first =
        explainer.explain(finding("AT-B2G-02", "IBAN AT611904300234573201 ungültig"), CONTEXT);
    Optional<String> second =
        explainer.explain(finding("AT-B2G-02", "IBAN AT483200000012345864 ungültig"), CONTEXT);

    assertThat(client.prompts).hasSize(1);
    assertThat(second).isEqualTo(first);
  }

  @Test
  void doesNotServeOneFindingsExplanationForAFindingTheModelWouldSeeDifferently() {
    // The other half of the same rule: anything the model can actually see — here the rule and its
    // message — must produce its own explanation. Keying on the rule id alone would collapse these
    // two.
    RecordingClient client = new RecordingClient("ok");
    FindingExplainer explainer = new FindingExplainer(client);

    explainer.explain(finding("AT-B2G-01", "Auftragsreferenz fehlt"), CONTEXT);
    explainer.explain(finding("AT-B2G-01", "Auftragsreferenz ist leer"), CONTEXT);

    assertThat(client.prompts).hasSize(2);
  }

  @Test
  void distinguishesFindingsByContextToo() {
    RecordingClient client = new RecordingClient("ok");
    FindingExplainer explainer = new FindingExplainer(client);
    Finding same = finding("BR-01", "Rechnungsnummer fehlt");

    explainer.explain(same, ExplanationContext.of("ebinterface-6.1", "at-b2g"));
    explainer.explain(same, ExplanationContext.of("ubl-invoice-2.1", "peppol-bis-billing-3.0"));

    assertThat(client.prompts).hasSize(2);
  }

  @Test
  void usesTheCacheItWasGiven() {
    ExplanationCache shared = new ExplanationCache(8);
    RecordingClient first = new RecordingClient("Aus dem Cache.");
    new FindingExplainer(first, shared).explain(finding("AT-B2G-01", "fehlt"), CONTEXT);

    // A second explainer over the same cache must not re-ask: this is the shape app wires, where
    // one
    // cache bean outlives any single request.
    RecordingClient second = new RecordingClient("Anderer Text.");
    Optional<String> explanation =
        new FindingExplainer(second, shared).explain(finding("AT-B2G-01", "fehlt"), CONTEXT);

    assertThat(explanation).contains("Aus dem Cache.");
    assertThat(second.prompts).isEmpty();
  }

  // ----------------------------------------------------------------- degradation

  @Test
  void degradesToEmptyWhenTheProviderFails() {
    LlmClient failing =
        prompt -> {
          throw new LlmException("provider down", true);
        };

    assertThat(new FindingExplainer(failing).explain(finding("AT-B2G-01", "fehlt"), CONTEXT))
        .isEmpty();
  }

  @Test
  void degradesToEmptyWhenTheProviderFailsPermanently() {
    LlmClient failing =
        prompt -> {
          throw new LlmException("invalid api key", false);
        };

    assertThat(new FindingExplainer(failing).explain(finding("AT-B2G-01", "fehlt"), CONTEXT))
        .isEmpty();
  }

  @Test
  void degradesToEmptyOnAnUnexpectedRuntimeFailure() {
    // A bug in this module — or in a client implementation — must still not fail the page that was
    // rendering a perfectly good report.
    LlmClient exploding =
        prompt -> {
          throw new IllegalStateException("boom");
        };

    assertThat(new FindingExplainer(exploding).explain(finding("AT-B2G-01", "fehlt"), CONTEXT))
        .isEmpty();
  }

  @Test
  void degradesToEmptyRatherThanThrowingOnNullArguments() {
    RecordingClient client = new RecordingClient("ok");
    FindingExplainer explainer = new FindingExplainer(client);

    assertThat(explainer.explain(null, CONTEXT)).isEmpty();
    assertThat(explainer.explain(finding("AT-B2G-01", "fehlt"), null)).isEmpty();
    assertThat(client.prompts).isEmpty();
  }

  @Test
  void nothingIsCachedWhenTheCallFails() {
    ExplanationCache cache = new ExplanationCache(8);
    LlmClient failing =
        prompt -> {
          throw new LlmException("provider down", true);
        };

    new FindingExplainer(failing, cache).explain(finding("AT-B2G-01", "fehlt"), CONTEXT);

    assertThat(cache.size()).isZero();
  }

  // ----------------------------------------------------------------- boundedness

  @Test
  void trimsAnOverLongAnswerToWhatAFindingCanHold() {
    // A provider that ignores max_tokens must not make Finding.withAiExplanation throw — that would
    // turn a too-long answer into a failed request instead of a trimmed one.
    RecordingClient client = new RecordingClient("z".repeat(20_000));

    Optional<String> explanation =
        new FindingExplainer(client).explain(finding("AT-B2G-01", "fehlt"), CONTEXT);

    assertThat(explanation).isPresent();
    assertThat(finding("AT-B2G-01", "fehlt").withAiExplanation(explanation.orElseThrow()))
        .isNotNull();
    assertThat(explanation.orElseThrow()).hasSize(8192);
  }

  @Test
  void rejectsNullConstructorArguments() {
    assertThat(catchIllegalArgument(() -> new FindingExplainer(null))).isTrue();
    assertThat(catchIllegalArgument(() -> new FindingExplainer(new RecordingClient("ok"), null)))
        .isTrue();
  }

  private static boolean catchIllegalArgument(Runnable runnable) {
    try {
      runnable.run();
      return false;
    } catch (IllegalArgumentException expected) {
      return true;
    }
  }

  /** A client that answers with a fixed text and remembers every prompt it was handed. */
  private static final class RecordingClient implements LlmClient {

    private final String answer;
    private final List<LlmPrompt> prompts = new ArrayList<>();

    private RecordingClient(String answer) {
      this.answer = answer;
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
      prompts.add(prompt);
      return new LlmCompletion(
          answer,
          new LlmUsage(
              "anthropic/claude-sonnet-5", 100, 40, Optional.of(new BigDecimal("0.0004"))));
    }
  }

  @Test
  void theAnonymousValidatorsContextCarriesNothingToRedact() {
    // Pins the public-validator case explicitly: nothing is retained about the upload, so there are
    // no
    // literals to pass, and the scrubber is down to pattern masking alone. Stated as a test because
    // it
    // is a privacy limit worth being deliberate about rather than discovering later.
    assertThat(CONTEXT.sensitiveLiterals()).isEqualTo(Set.of());
  }
}
