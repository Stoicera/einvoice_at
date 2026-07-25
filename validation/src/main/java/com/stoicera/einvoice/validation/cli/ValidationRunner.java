package com.stoicera.einvoice.validation.cli;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.InvoiceValidator;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Command-line front end for {@link InvoiceValidator}: validates one or more files or directories
 * and prints a German-first report per file, mirroring the console output an enterprise reviewer or
 * CI log would want to read.
 *
 * <p>Each positional argument is either a file — validated as given, regardless of its extension —
 * or a directory, whose {@code *.xml} files are discovered by a recursive walk ({@link Files#walk},
 * unbounded depth) and sorted lexicographically by their string path before validation. Both
 * choices are deliberate: recursive because the corpus under {@code src/test/resources/corpus}
 * nests documents under {@code valid/} and {@code invalid/} subdirectories, and sorted because
 * deterministic output is what makes a CI log or a golden-file comparison reproducible run to run.
 *
 * <p>No Spring, no logging framework: this class is plain Java so it can run standalone via {@code
 * exec:java} without pulling in the application context, keeping corpus smoke runs fast. All output
 * goes through the {@link PrintStream}s passed to {@link #run(String[], PrintStream, PrintStream)}
 * — the testable seam — so unit tests can assert on captured output without spawning a process;
 * {@link #main(String[])} wires that seam to {@link System#out}/{@link System#err} and translates
 * the result into a process exit code.
 *
 * <p>Exit codes: {@code 0} every validated file is valid, {@code 1} at least one validated file is
 * invalid, {@code 2} a usage or I/O error occurred before (or instead of) a validation verdict — no
 * arguments, a nonexistent path, a file that could not be read, or a resolved file list that came
 * back empty (e.g. a directory with no {@code *.xml} files, most likely a mistyped corpus path).
 * The empty-list case is deliberately a hard error rather than a silent {@code 0}: a CI gate that
 * only checks the exit code must not see "all valid" for a run that validated nothing.
 */
public final class ValidationRunner {

  private static final String USAGE =
      "Aufruf: " + ValidationRunner.class.getSimpleName() + " <datei-oder-verzeichnis>...";

  /** Exit code: every validated file is valid. */
  static final int EXIT_ALL_VALID = 0;

  /** Exit code: at least one validated file is invalid. */
  static final int EXIT_AT_LEAST_ONE_INVALID = 1;

  /** Exit code: usage error or I/O failure — no validation verdict was produced. */
  static final int EXIT_USAGE_OR_IO_ERROR = 2;

  private ValidationRunner() {}

  /**
   * Process entry point: runs {@link #run(String[], PrintStream, PrintStream)} against the real
   * console streams and exits the JVM with its result.
   */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * Validates every file named or discovered under {@code args} and writes a German-first report
   * per file to {@code out}. This is the testable seam: no {@link System#exit}, no fixed streams.
   *
   * @param args one or more file or directory paths; empty is a usage error
   * @param out where per-file reports are written
   * @param err where the usage message or a CLI-level I/O error is written (German only; see {@link
   *     #printError(PrintStream, String)})
   * @return {@link #EXIT_ALL_VALID}, {@link #EXIT_AT_LEAST_ONE_INVALID}, or {@link
   *     #EXIT_USAGE_OR_IO_ERROR}
   */
  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0) {
      err.println(USAGE);
      return EXIT_USAGE_OR_IO_ERROR;
    }

    List<Path> files;
    try {
      files = resolveFiles(args);
    } catch (IllegalArgumentException | UncheckedIOException e) {
      printError(err, e.getMessage());
      return EXIT_USAGE_OR_IO_ERROR;
    }

    if (files.isEmpty()) {
      printError(err, "Keine XML-Dateien gefunden unter: " + String.join(", ", args));
      return EXIT_USAGE_OR_IO_ERROR;
    }

    InvoiceValidator validator = new InvoiceValidator();
    boolean allValid = true;
    for (Path file : files) {
      byte[] content;
      try {
        content = Files.readAllBytes(file);
      } catch (IOException e) {
        printError(err, "Datei konnte nicht gelesen werden: " + file + " (" + e.getMessage() + ")");
        return EXIT_USAGE_OR_IO_ERROR;
      }
      ValidationReport report = validator.validate(content);
      printReport(out, file, report);
      allValid = allValid && report.isValid();
    }

    return allValid ? EXIT_ALL_VALID : EXIT_AT_LEAST_ONE_INVALID;
  }

  /**
   * Resolves the positional arguments to a flat, ordered list of files to validate: a file argument
   * is taken as given, a directory argument is expanded to its {@code *.xml} files (see {@link
   * #xmlFilesUnder(Path)}). Arguments are processed left to right; a directory's files are sorted
   * among themselves but the overall list still follows argument order.
   *
   * @throws IllegalArgumentException if an argument names a path that does not exist
   */
  private static List<Path> resolveFiles(String[] args) {
    List<Path> result = new ArrayList<>();
    for (String arg : args) {
      Path path = Path.of(arg);
      if (!Files.exists(path)) {
        throw new IllegalArgumentException("Pfad nicht gefunden: " + arg);
      }
      if (Files.isDirectory(path)) {
        result.addAll(xmlFilesUnder(path));
      } else {
        result.add(path);
      }
    }
    return result;
  }

  /** Every {@code *.xml} regular file under {@code directory}, recursive, sorted by path string. */
  private static List<Path> xmlFilesUnder(Path directory) {
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(Files::isRegularFile)
          .filter(ValidationRunner::isXmlFile)
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Verzeichnis konnte nicht gelesen werden: " + directory, e);
    }
  }

  /**
   * Whether {@code path} names an {@code *.xml} file — the extension filter the directory walk
   * applies. A named method rather than an inline lambda so the discovery rule is directly unit-
   * and mutation-testable. Package-visible for tests.
   */
  static boolean isXmlFile(Path path) {
    return path.getFileName().toString().endsWith(".xml");
  }

  /**
   * Writes one German-first report block for {@code file}'s {@code report} to {@code out}. The
   * severity label and rule id are padded to fixed column widths so the findings line up as a
   * readable table in a CI log even when rule ids differ in length ({@code XML-01} vs {@code
   * AT-B2G-01}); the variable-length location and message follow.
   */
  private static void printReport(PrintStream out, Path file, ValidationReport report) {
    out.println("== " + file + " ==");
    out.println("Format: " + report.sourceFormat() + " · Profil: " + report.profile());
    for (Finding finding : report.findings()) {
      String location = finding.location() == null ? "-" : finding.location();
      out.println(
          "%-7s  %-9s  %s  %s"
              .formatted(
                  germanSeverityLabel(finding.severity()),
                  finding.ruleId(),
                  location,
                  finding.messageDe()));
      out.println("        EN: " + finding.messageEn());
    }
    out.println(
        "Ergebnis: %s — %d Fehler, %d Warnungen, %d Hinweise"
            .formatted(
                report.isValid() ? "GÜLTIG" : "UNGÜLTIG",
                report.countOf(Severity.ERROR),
                report.countOf(Severity.WARN),
                report.countOf(Severity.INFO)));
  }

  /**
   * Prints a CLI-level error to {@code err}. Unlike per-finding lines, these are single German
   * messages: they report on the run itself (a missing path, an unreadable file), not on a
   * validated document, so there is no {@link Finding#messageEn()} counterpart to echo — printing a
   * fake "EN:" line with the same German text would misrepresent it as a translation.
   */
  private static void printError(PrintStream err, String message) {
    err.println(message);
  }

  /** German severity label used as the lead-in of each finding line. Package-visible for tests. */
  static String germanSeverityLabel(Severity severity) {
    return switch (severity) {
      case ERROR -> "FEHLER";
      case WARN -> "WARNUNG";
      case INFO -> "HINWEIS";
    };
  }
}
