package com.stoicera.einvoice.app.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * The problem {@code type} base is part of the API contract, so its default and its override rules
 * are pinned here rather than left to whatever the environment of a test run happens to hold.
 */
class ProblemsTest {

  @Test
  void defaultsToTheDemoHostWhenNothingIsConfigured() {
    assertThat(Problems.resolveBase(null))
        .isEqualTo("https://einvoice.sebastiankern.net/problems/");
    assertThat(Problems.resolveBase("  ")).isEqualTo(Problems.DEFAULT_BASE);
  }

  @Test
  void anOverrideIsUsedAndAlwaysEndsInASlash() {
    assertThat(Problems.resolveBase("https://invoices.example.at/problems"))
        .isEqualTo("https://invoices.example.at/problems/");
    assertThat(Problems.resolveBase(" https://invoices.example.at/problems/ "))
        .isEqualTo("https://invoices.example.at/problems/");
  }

  @Test
  void aRelativeOrNonHttpBaseFailsInsteadOfProducingUnresolvableTypes() {
    assertThatIllegalArgumentException().isThrownBy(() -> Problems.resolveBase("/problems/"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Problems.resolveBase("ftp://example.at/problems/"));
    assertThatIllegalArgumentException().isThrownBy(() -> Problems.resolveBase("https:///x/"));
  }

  @Test
  void aTypeIsTheBasePlusTheSlug() {
    assertThat(Problems.type("rate-limited")).hasToString(Problems.BASE + "rate-limited");
  }
}
