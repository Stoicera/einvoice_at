package com.stoicera.einvoice.aiassist.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {

  private static final String TEMPLATE = "prompts/test-template.st";

  @Test
  void rendersEveryPlaceholder() {
    String rendered =
        PromptTemplate.load(TEMPLATE)
            .render(
                Map.of(
                    "ruleId", "AT-B2G-01",
                    "sourceFormat", "ebinterface-6.1",
                    "messageDe", "Auftragsreferenz fehlt"));

    assertThat(rendered).isEqualTo("Regel AT-B2G-01 in ebinterface-6.1: Auftragsreferenz fehlt");
  }

  @Test
  void rendersARepeatedPlaceholderEveryTimeItAppears() {
    assertThat(
            PromptTemplate.load("prompts/test-repeated-placeholder.st")
                .render(Map.of("ruleId", "BR-01")))
        .isEqualTo("BR-01 — siehe BR-01");
  }

  @Test
  void rejectsAMissingValue() {
    // The bug this prevents: a literal "{messageDe}" reaching the model, which answers plausibly
    // about nothing at all.
    assertThatThrownBy(
            () ->
                PromptTemplate.load(TEMPLATE)
                    .render(Map.of("ruleId", "AT-B2G-01", "sourceFormat", "ebinterface-6.1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("{messageDe}")
        .hasMessageContaining(TEMPLATE);
  }

  @Test
  void rejectsAValueWithNoMatchingPlaceholder() {
    // The mirror-image bug: a caller renames a field, the value is silently dropped, and the prompt
    // quietly loses information nobody notices is gone.
    Map<String, String> values = new LinkedHashMap<>();
    values.put("ruleId", "AT-B2G-01");
    values.put("sourceFormat", "ebinterface-6.1");
    values.put("messageDe", "Auftragsreferenz fehlt");
    values.put("messageDeutsch", "Tippfehler");

    assertThatThrownBy(() -> PromptTemplate.load(TEMPLATE).render(values))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("messageDeutsch");
  }

  @Test
  void rejectsAnUnusedValueEvenWhenAPlaceholderRepeats() {
    // A count-based check would pass here (two substitutions, two supplied values) and let the
    // unused
    // key through; the used-key set is what catches it.
    Map<String, String> values = new LinkedHashMap<>();
    values.put("ruleId", "BR-01");
    values.put("unused", "x");

    assertThatThrownBy(
            () -> PromptTemplate.load("prompts/test-repeated-placeholder.st").render(values))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unused");
  }

  @Test
  void insertsRegexReplacementMetacharactersLiterally() {
    // A finding message is untrusted text; "$1" or a backslash must not be read as a
    // back-reference.
    String rendered =
        PromptTemplate.load(TEMPLATE)
            .render(
                Map.of(
                    "ruleId", "XSD-01",
                    "sourceFormat", "ubl-invoice-2.1",
                    "messageDe", "Wert '$1\\x' ungültig"));

    assertThat(rendered).endsWith("Wert '$1\\x' ungültig");
  }

  @Test
  void rejectsANullValueMap() {
    assertThatThrownBy(() -> PromptTemplate.load(TEMPLATE).render(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failsLoudlyWhenTheResourceIsMissing() {
    // A packaging fault, so it must surface when the bean is built rather than on a user's first
    // click.
    assertThatThrownBy(() -> PromptTemplate.load("prompts/does-not-exist.st"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void failsLoudlyWhenTheResourceIsEmpty() {
    assertThatThrownBy(() -> PromptTemplate.load("prompts/test-empty.st"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void exposesItsVersionedNameForLogsAndMetrics() {
    assertThat(PromptTemplate.load(TEMPLATE).name()).isEqualTo(TEMPLATE);
  }

  @Test
  void shippedPromptsLoadAndCarryTheExpectedPlaceholders() {
    // Guards the seam between FindingExplainer's value map and the committed prompt files: renaming
    // a
    // placeholder in either one without the other is caught here rather than at runtime.
    assertThat(PromptTemplate.load("prompts/finding-explanation.system.v1.st").render(Map.of()))
        .contains("Antworte ausschließlich auf Deutsch");

    String user =
        PromptTemplate.load("prompts/finding-explanation.user.v1.st")
            .render(
                Map.of(
                    "sourceFormat", "ebinterface-6.1",
                    "profile", "at-b2g",
                    "ruleId", "AT-B2G-01",
                    "severity", "ERROR",
                    "location", "/Invoice/OrderReference",
                    "messageDe", "Auftragsreferenz fehlt",
                    "messageEn", "Order reference missing"));

    assertThat(user).contains("AT-B2G-01").contains("at-b2g").contains("Auftragsreferenz fehlt");
  }
}
