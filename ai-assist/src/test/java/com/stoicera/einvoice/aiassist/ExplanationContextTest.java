package com.stoicera.einvoice.aiassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExplanationContextTest {

  @Test
  void ofCarriesNoLiterals() {
    ExplanationContext context = ExplanationContext.of("ebinterface-6.1", "at-b2g");

    assertThat(context.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(context.profile()).isEqualTo("at-b2g");
    assertThat(context.sensitiveLiterals()).isEmpty();
  }

  @Test
  void withPartyNamesKeepsTheUsableNames() {
    ExplanationContext context =
        ExplanationContext.withPartyNames(
            "ubl-invoice-2.1",
            "peppol-bis-billing-3.0",
            "Stoicera Software GesbR",
            "Bundesbeschaffung GmbH");

    assertThat(context.sensitiveLiterals())
        .containsExactlyInAnyOrder("Stoicera Software GesbR", "Bundesbeschaffung GmbH");
  }

  @Test
  void withPartyNamesSkipsNullAndBlankNames() {
    // A buyer name is optional in the canonical model, so a null reaching here is normal input
    // rather
    // than a caller bug — and List.of would have thrown on it.
    ExplanationContext context =
        ExplanationContext.withPartyNames("ebinterface-6.1", "at-b2g", "Stoicera", null, "   ");

    assertThat(context.sensitiveLiterals()).containsExactly("Stoicera");
  }

  @Test
  void withPartyNamesToleratesNoNamesAtAll() {
    assertThat(ExplanationContext.withPartyNames("ebinterface-6.1", "at-b2g").sensitiveLiterals())
        .isEmpty();
    assertThat(
            ExplanationContext.withPartyNames("ebinterface-6.1", "at-b2g", (String[]) null)
                .sensitiveLiterals())
        .isEmpty();
  }

  @Test
  void defensivelyCopiesTheLiteralSet() {
    Set<String> mutable = new HashSet<>(Set.of("Stoicera"));
    ExplanationContext context = new ExplanationContext("ebinterface-6.1", "at-b2g", mutable);

    mutable.add("Bundesbeschaffung GmbH");

    assertThat(context.sensitiveLiterals()).containsExactly("Stoicera");
  }

  @Test
  void rejectsIncompleteContext() {
    assertThatThrownBy(() -> new ExplanationContext(null, "at-b2g", Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source format");
    assertThatThrownBy(() -> new ExplanationContext("ebinterface-6.1", "  ", Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("profile");
    assertThatThrownBy(() -> new ExplanationContext("ebinterface-6.1", "at-b2g", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Set.of()");
  }
}
