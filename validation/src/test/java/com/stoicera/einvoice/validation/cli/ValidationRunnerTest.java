package com.stoicera.einvoice.validation.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Severity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the {@link ValidationRunner#run} seam: no process spawning, only the documented
 * file/directory/exit-code contract. File paths are module-relative ({@code
 * src/test/resources/corpus/...}) because Surefire runs with the module directory as the working
 * directory, and the CLI reads real files from disk, not the test classpath.
 */
class ValidationRunnerTest {

  private static final String VALID_FILE = "src/test/resources/corpus/valid/minimal.xml";
  private static final String INVALID_FILE =
      "src/test/resources/corpus/invalid/at-b2g-01-missing-order-reference.xml";
  private static final String CORPUS_DIR = "src/test/resources/corpus";

  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void setUp() {
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @Test
  void noArgumentsPrintsGermanUsageOnErrAndExits2() {
    int exitCode = ValidationRunner.run(new String[0], out, err);

    assertThat(exitCode).isEqualTo(2);
    assertThat(errText()).contains("Aufruf: ValidationRunner <datei-oder-verzeichnis>...");
    assertThat(outText()).isEmpty();
  }

  @Test
  void validCorpusFileExits0AndReportsGueltig() {
    int exitCode = ValidationRunner.run(new String[] {VALID_FILE}, out, err);

    assertThat(exitCode).isEqualTo(0);
    assertThat(outText()).contains("GÜLTIG");
    assertThat(outText()).doesNotContain("UNGÜLTIG");
  }

  @Test
  void invalidCorpusFileExits1AndReportsRuleIdInBothLanguages() {
    int exitCode = ValidationRunner.run(new String[] {INVALID_FILE}, out, err);

    assertThat(exitCode).isEqualTo(1);
    assertThat(outText()).contains("UNGÜLTIG");
    assertThat(outText()).contains("AT-B2G-01");
    assertThat(outText()).contains("FEHLER");
    assertThat(outText()).contains("EN: ");
  }

  @Test
  void directoryArgumentWalksRecursivelyInSortedOrderAndAggregatesExitCode() {
    int exitCode = ValidationRunner.run(new String[] {CORPUS_DIR}, out, err);

    // The corpus has both valid and invalid files nested under valid/ and invalid/ subdirectories;
    // at least one invalid file makes the aggregate exit code 1.
    assertThat(exitCode).isEqualTo(1);
    String output = outText();
    // Every *.xml file in the corpus (nested two levels deep) must have been picked up...
    assertThat(output).contains("minimal.xml");
    assertThat(output).contains("b2g-full.xml");
    assertThat(output).contains("malformed.xml");
    assertThat(output).contains("wrong-namespace-ebi60.xml");
    assertThat(output).contains("xsd-missing-invoice-number.xml");
    assertThat(output).contains("at-b2g-01-missing-order-reference.xml");
    assertThat(output).contains("at-b2g-02-invalid-iban.xml");
    // ...but the non-xml README.md must not have been treated as a document to validate.
    assertThat(output).doesNotContain("README.md");
    // Deterministic (sorted) order: within invalid/, at-b2g-01 sorts before at-b2g-02, which sorts
    // before malformed, which sorts before wrong-namespace-ebi60, which sorts before
    // xsd-missing-invoice-number.
    assertThat(output)
        .containsSubsequence(
            "at-b2g-01-missing-order-reference.xml",
            "at-b2g-02-invalid-iban.xml",
            "malformed.xml",
            "wrong-namespace-ebi60.xml",
            "xsd-missing-invoice-number.xml");
  }

  @Test
  void nonexistentPathExits2WithGermanErrorOnErr() {
    int exitCode =
        ValidationRunner.run(
            new String[] {"src/test/resources/corpus/does-not-exist.xml"}, out, err);

    assertThat(exitCode).isEqualTo(2);
    assertThat(errText()).contains("Pfad nicht gefunden");
    assertThat(outText()).isEmpty();
  }

  @Test
  void emptyDirectoryExits2WithGermanErrorAndNoOutput(@TempDir Path emptyDir) {
    int exitCode = ValidationRunner.run(new String[] {emptyDir.toString()}, out, err);

    assertThat(exitCode).isEqualTo(2);
    assertThat(errText()).contains("Keine XML-Dateien gefunden unter: " + emptyDir);
    assertThat(outText()).isEmpty();
  }

  @Test
  void directoryWithOnlyNonXmlFilesExits2WithGermanErrorAndNoOutput(@TempDir Path dir)
      throws IOException {
    Files.writeString(dir.resolve("README.md"), "not xml");

    int exitCode = ValidationRunner.run(new String[] {dir.toString()}, out, err);

    assertThat(exitCode).isEqualTo(2);
    assertThat(errText()).contains("Keine XML-Dateien gefunden unter: " + dir);
    assertThat(outText()).isEmpty();
  }

  @Test
  void multipleFileArgumentsAreAllValidated() {
    int exitCode = ValidationRunner.run(new String[] {VALID_FILE, INVALID_FILE}, out, err);

    assertThat(exitCode).isEqualTo(1);
    assertThat(outText()).contains("minimal.xml");
    assertThat(outText()).contains("at-b2g-01-missing-order-reference.xml");
  }

  @Test
  void severityLabelsAreGermanFirstForAllThreeSeverities() {
    assertThat(ValidationRunner.germanSeverityLabel(Severity.ERROR)).isEqualTo("FEHLER");
    assertThat(ValidationRunner.germanSeverityLabel(Severity.WARN)).isEqualTo("WARNUNG");
    assertThat(ValidationRunner.germanSeverityLabel(Severity.INFO)).isEqualTo("HINWEIS");
  }

  private String outText() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String errText() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }
}
