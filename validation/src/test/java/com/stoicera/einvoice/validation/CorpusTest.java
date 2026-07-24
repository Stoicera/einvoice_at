package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden-file corpus regression test. Every file under {@code src/test/resources/corpus} is run
 * through the real {@link EbInterface61Validator} and the set of rule ids it fails on is asserted
 * against the expectation documented in {@code corpus/README.md}.
 *
 * <p>The corpus is the validator's living specification: it grows by "first a failing corpus file,
 * then the fix" (CLAUDE.md). Each invalid file carries exactly one deliberate defect derived from
 * {@code valid/b2g-full.xml}, so a single expected rule id isolates the behaviour under test; the
 * {@code xsd-missing-invoice-number} case is the exception the brief calls out — one structural
 * defect can surface as several {@code EBI61-XSD} findings, hence the assertion compares the
 * <em>distinct</em> rule ids rather than a multiset.
 *
 * <p>Only {@link Severity#ERROR} and {@link Severity#WARN} findings gate compliance, so those are
 * the ones asserted; a valid document is one whose compliance-affecting finding set is empty (an
 * {@code INFO} note, e.g. on {@code b2g-full.xml}, does not count and is deliberately ignored
 * here).
 */
class CorpusTest {

  private final EbInterface61Validator validator = new EbInterface61Validator();

  /** The binding corpus table (file → expected compliance-affecting rule ids). */
  static List<Arguments> corpus() {
    return List.of(
        Arguments.of("corpus/valid/minimal.xml", Set.of()),
        Arguments.of("corpus/valid/b2g-full.xml", Set.of()),
        Arguments.of("corpus/invalid/malformed.xml", Set.of("XML-01")),
        Arguments.of("corpus/invalid/wrong-namespace-ebi60.xml", Set.of("FORMAT-02")),
        Arguments.of("corpus/invalid/xsd-missing-invoice-number.xml", Set.of("EBI61-XSD")),
        Arguments.of("corpus/invalid/at-b2g-01-missing-order-reference.xml", Set.of("AT-B2G-01")),
        Arguments.of("corpus/invalid/at-b2g-02-invalid-iban.xml", Set.of("AT-B2G-02")));
  }

  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("corpus")
  void corpusFileYieldsExpectedRuleIds(String resource, Set<String> expectedRuleIds) {
    ValidationReport report = validator.validate(readResource(resource));

    Set<String> actualRuleIds =
        report.findings().stream()
            .filter(finding -> isComplianceAffecting(finding.severity()))
            .map(Finding::ruleId)
            .collect(Collectors.toUnmodifiableSet());

    assertThat(actualRuleIds)
        .as("compliance-affecting (ERROR/WARN) rule ids for %s", resource)
        .isEqualTo(expectedRuleIds);
    // A file with no compliance-affecting findings is, by definition, valid; the reverse holds too.
    assertThat(report.isValid()).isEqualTo(expectedRuleIds.isEmpty());
  }

  private static boolean isComplianceAffecting(Severity severity) {
    return severity == Severity.ERROR || severity == Severity.WARN;
  }

  private static byte[] readResource(String resource) {
    try (InputStream in = CorpusTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "Missing corpus resource on the test classpath: " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read corpus resource: " + resource, e);
    }
  }
}
