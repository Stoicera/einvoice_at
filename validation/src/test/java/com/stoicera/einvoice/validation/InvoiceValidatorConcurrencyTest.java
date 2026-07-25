package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Concurrency regression net for the shared compiled Schematron (finding B2 / P2-9).
 *
 * <p>ph-schematron-pure binds ("compiles") its schema lazily through a non-volatile, unsynchronised
 * instance field. {@link com.stoicera.einvoice.validation.stage.SchematronStage} now forces that
 * bind exactly once, eagerly, inside its static holder, so the JVM class-initialisation lock
 * publishes a fully-bound, thereafter read-only resource. This test pins the guarantee the module
 * Javadoc makes: many threads that reach a fresh validator for the first time simultaneously (M3's
 * single shared validator over HTTP request threads) must each get the correct report and none may
 * throw.
 *
 * <p>The underlying race is not deterministically reproducible — by the time this test runs the
 * holder may already have been bound by an earlier test in the same JVM fork, and even pre-fix the
 * window is narrow — so this is a permanent regression net rather than a red-then-green
 * demonstration. It fails loudly if a future change reintroduces a concurrent-first-use hazard: a
 * double compile, a half-published bound schema surfacing as an exception, or a wrong report under
 * load.
 *
 * <p>The expected file → rule-id table is reused verbatim from {@link CorpusTest#corpus()} so the
 * two tests cannot drift apart; the "compliance-affecting" (ERROR + WARN) semantics match {@code
 * CorpusTest} exactly.
 */
class InvoiceValidatorConcurrencyTest {

  private static final int THREADS = 8;

  @Test
  void concurrentFirstUseProducesCorrectReportsAndNeverThrows() throws Exception {
    Map<String, Set<String>> expected = expectedCorpus();
    Map<String, byte[]> docs =
        expected.keySet().stream()
            .collect(
                Collectors.toMap(
                    resource -> resource, InvoiceValidatorConcurrencyTest::readResource));

    CountDownLatch parked = new CountDownLatch(THREADS);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      List<Future<Map<String, Set<String>>>> futures = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        futures.add(
            pool.submit(
                () -> {
                  // A FRESH validator per thread; the compiled Schematron is JVM-global, so every
                  // thread still contends on the same holder's (now eager) first bind.
                  InvoiceValidator validator = new InvoiceValidator();
                  parked.countDown();
                  release.await(); // block until all threads are constructed and parked
                  Map<String, Set<String>> actual = new HashMap<>();
                  for (Map.Entry<String, byte[]> doc : docs.entrySet()) {
                    ValidationReport report = validator.validate(doc.getValue());
                    actual.put(doc.getKey(), complianceAffectingRuleIds(report));
                  }
                  return actual;
                }));
      }

      assertThat(parked.await(30, TimeUnit.SECONDS))
          .as("all worker threads constructed a validator and parked on the latch")
          .isTrue();
      release.countDown(); // fire every thread into validate() simultaneously

      for (Future<Map<String, Set<String>>> future : futures) {
        // future.get() rethrows any exception the worker threw (the race's failure mode).
        Map<String, Set<String>> actual = future.get(60, TimeUnit.SECONDS);
        assertThat(actual)
            .as("compliance-affecting rule ids per corpus file under concurrent first use")
            .isEqualTo(expected);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static Set<String> complianceAffectingRuleIds(ValidationReport report) {
    return report.findings().stream()
        .filter(
            finding -> finding.severity() == Severity.ERROR || finding.severity() == Severity.WARN)
        .map(Finding::ruleId)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** The single source of truth for expectations: {@link CorpusTest#corpus()}. */
  @SuppressWarnings("unchecked")
  private static Map<String, Set<String>> expectedCorpus() {
    Map<String, Set<String>> expected = new HashMap<>();
    for (Arguments arguments : CorpusTest.corpus()) {
      Object[] row = arguments.get();
      expected.put((String) row[0], (Set<String>) row[1]);
    }
    return expected;
  }

  private static byte[] readResource(String resource) {
    try (InputStream in =
        InvoiceValidatorConcurrencyTest.class.getClassLoader().getResourceAsStream(resource)) {
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
