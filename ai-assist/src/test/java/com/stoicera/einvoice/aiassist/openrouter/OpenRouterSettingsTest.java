package com.stoicera.einvoice.aiassist.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenRouterSettingsTest {

  @Test
  void defaultsMatchTheDocumentedValues() {
    OpenRouterSettings settings = OpenRouterSettings.withDefaults("sk-test");

    assertThat(settings.baseUrl()).hasToString("https://openrouter.ai/api/v1");
    assertThat(settings.model()).isEqualTo("anthropic/claude-sonnet-5");
    assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(15)); // SPEC §6
    assertThat(settings.maxRetries()).isEqualTo(1); // SPEC §6
    assertThat(settings.maxOutputTokens()).isPositive();
  }

  @Test
  void derivesTheCompletionsEndpoint() {
    assertThat(OpenRouterSettings.withDefaults("sk-test").completionsEndpoint())
        .hasToString("https://openrouter.ai/api/v1/chat/completions");
  }

  @Test
  void derivesTheCompletionsEndpointFromABaseUrlWithATrailingSlash() {
    // An operator setting AI_BASE_URL from a browser address bar will paste a trailing slash; a
    // naive
    // concatenation would produce "//chat/completions" and a 404 that looks like a credential
    // problem.
    OpenRouterSettings settings =
        new OpenRouterSettings(
            URI.create("https://gateway.internal/v1/"),
            "sk-test",
            "anthropic/claude-sonnet-5",
            Duration.ofSeconds(5),
            0,
            100);

    assertThat(settings.completionsEndpoint())
        .hasToString("https://gateway.internal/v1/chat/completions");
  }

  @Test
  void toStringNeverRevealsTheApiKey() {
    // A record's generated toString prints every component, so one log line would leak the
    // credential.
    String printed = OpenRouterSettings.withDefaults("sk-super-secret-value").toString();

    assertThat(printed).doesNotContain("sk-super-secret-value").contains("apiKey=***");
    assertThat(printed).contains("anthropic/claude-sonnet-5");
  }

  @Test
  void rejectsIncompleteConfiguration() {
    URI base = URI.create("https://openrouter.ai/api/v1");

    assertThatThrownBy(() -> new OpenRouterSettings(null, "k", "m", Duration.ofSeconds(1), 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OpenRouterSettings(
                    URI.create("/relative"), "k", "m", Duration.ofSeconds(1), 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> new OpenRouterSettings(base, "  ", "m", Duration.ofSeconds(1), 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("apiKey");
    assertThatThrownBy(() -> new OpenRouterSettings(base, "k", "", Duration.ofSeconds(1), 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
    assertThatThrownBy(() -> new OpenRouterSettings(base, "k", "m", Duration.ZERO, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout");
    assertThatThrownBy(() -> new OpenRouterSettings(base, "k", "m", null, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout");
    assertThatThrownBy(
            () -> new OpenRouterSettings(base, "k", "m", Duration.ofSeconds(1).negated(), 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout");
    assertThatThrownBy(() -> new OpenRouterSettings(base, "k", "m", Duration.ofSeconds(1), -1, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRetries");
    assertThatThrownBy(() -> new OpenRouterSettings(base, "k", "m", Duration.ofSeconds(1), 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxOutputTokens");
  }
}
