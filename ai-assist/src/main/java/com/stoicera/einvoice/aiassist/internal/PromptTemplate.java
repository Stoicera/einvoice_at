package com.stoicera.einvoice.aiassist.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prompt loaded from {@code src/main/resources/prompts} and rendered by named-placeholder
 * substitution.
 *
 * <p>SPEC §6 requires prompts to live in the repository as versioned files rather than as string
 * literals in code, and the reason is reviewability: a change to what the model is told should show
 * up in a diff a human reads, not buried in a Java concatenation. The version is part of the
 * filename ({@code …-v1.st}), so revising a prompt is adding a file and pointing at it — the old
 * wording stays in history rather than being overwritten in place.
 *
 * <p><strong>Rendering is strict in both directions.</strong> A placeholder left unfilled throws,
 * and so does a supplied value whose placeholder does not appear in the template. Silently
 * rendering {@code {messageDe}} into the prompt, or silently dropping a value the caller thought it
 * had passed, both produce a plausible-looking explanation built from the wrong input — the class
 * of bug that survives review because the output still reads fine.
 *
 * <p>{@code .st} matches the extension SPEC §6 names. The substitution is deliberately this
 * repository's own eight lines rather than a template engine: one {@code {name}} form, no loops, no
 * conditionals, no expression language — and therefore no evaluation surface reachable from a
 * document value.
 */
public final class PromptTemplate {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

  private final String name;
  private final String body;

  private PromptTemplate(String name, String body) {
    this.name = name;
    this.body = body;
  }

  /**
   * Loads a template from the classpath, e.g. {@code "prompts/finding-explanation.system.v1.st"}.
   *
   * @throws IllegalStateException the resource is absent or empty — a packaging fault, not a
   *     runtime condition, so it fails loudly at wiring time rather than degrading on the first
   *     request
   */
  public static PromptTemplate load(String resourcePath) {
    try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException(
            "Prompt template not found on the classpath: " + resourcePath);
      }
      String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      if (body.isEmpty()) {
        throw new IllegalStateException("Prompt template is empty: " + resourcePath);
      }
      return new PromptTemplate(resourcePath, body);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read prompt template " + resourcePath, e);
    }
  }

  /** The template's resource path, which carries its version — used in logs and metrics. */
  public String name() {
    return name;
  }

  /**
   * Substitutes every {@code {placeholder}} with the matching value.
   *
   * @throws IllegalArgumentException a placeholder has no value, or a value has no placeholder
   */
  public String render(Map<String, String> values) {
    if (values == null) {
      throw new IllegalArgumentException("Prompt template values must not be null");
    }
    Matcher matcher = PLACEHOLDER.matcher(body);
    StringBuilder out = new StringBuilder(body.length() + 256);
    // A set of the keys actually substituted, not a count: a template may legitimately use the same
    // placeholder twice, and a count would then hide an unused value behind the duplicate.
    Set<String> used = new HashSet<>();
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = values.get(key);
      if (value == null) {
        throw new IllegalArgumentException(
            "Prompt template %s has no value for placeholder {%s}".formatted(name, key));
      }
      // quoteReplacement so a value containing $ or \ is inserted literally rather than being read
      // as a replacement back-reference.
      matcher.appendReplacement(out, Matcher.quoteReplacement(value));
      used.add(key);
    }
    matcher.appendTail(out);

    if (!used.containsAll(values.keySet())) {
      Set<String> unused = new TreeSet<>(values.keySet());
      unused.removeAll(used);
      throw new IllegalArgumentException(
          "Prompt template %s has no placeholder for the supplied value(s) %s"
              .formatted(name, unused));
    }
    return out.toString();
  }
}
