package com.stoicera.einvoice.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.report.ReportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container;

/**
 * The backup/restore drill MILESTONES M6 asks for ("Backup/Restore-Probe"), run as a test rather
 * than as a paragraph in a runbook.
 *
 * <p><strong>Why automate a drill.</strong> A restore procedure is only known to work on the day
 * someone runs it, and the day someone runs it is the worst day to discover a flag is wrong. The
 * failure this guards against is specific and quiet: a dump that <em>writes</em> successfully and
 * cannot be <em>read</em> back — a schema the restore flags cannot reproduce, an ownership or
 * privilege clause that fails against a differently named role, a `pg_restore` invocation that
 * reports success while skipping every table.
 *
 * <p>The drill runs {@code pg_dump} and {@code pg_restore} <em>inside the Testcontainers Postgres
 * container</em>, which is what makes it portable: the client binaries are guaranteed present and
 * exactly version-matched to the server, so CI needs no {@code postgresql-client} package and no
 * version-skew caveat. It restores into a <strong>second database</strong> on the same server, so
 * nothing the rest of the suite depends on is dropped — and the shape is the honest one anyway,
 * because that is how a restore is rehearsed against a live system.
 *
 * <p>What it deliberately does not do is invoke {@code scripts/backup.sh}/{@code restore.sh}
 * directly: those are host-side operator scripts with an interactive confirmation, a checksum
 * sidecar and a retention sweep, and running them here would test bash. What must not drift is the
 * set of <em>flags</em> — the two constants below carry them, with the scripts named, so a change
 * in one place is visible as a difference in the other.
 */
@SpringBootTest
class BackupRestoreDrillIT extends AbstractPostgresIT {

  /**
   * The dump flags {@code scripts/backup.sh} uses. Custom format so the archive is compressed and
   * selectively restorable; no owner/privilege clauses so it restores into a differently named
   * role.
   */
  private static final List<String> DUMP_FLAGS =
      List.of("--format=custom", "--compress=9", "--no-owner", "--no-privileges");

  /** The restore flags {@code scripts/restore.sh} uses. */
  private static final List<String> RESTORE_FLAGS =
      List.of("--clean", "--if-exists", "--no-owner", "--no-privileges", "--exit-on-error");

  private static final String DRILL_DATABASE = "einvoice_restore_drill";
  private static final String DUMP_PATH = "/tmp/einvoice-drill.dump";

  private static final List<String> TABLES =
      List.of("tenant", "invoice", "report", "api_key", "audit_event");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private InvoiceService invoices;
  @Autowired private ReportService reports;
  @Autowired private TenantRepository tenants;

  @Test
  @DisplayName(
      "a dump restores into an empty database with every row and the Flyway history intact")
  void dumpAndRestoreRoundTripsEveryTable() throws Exception {
    // Something worth losing: an invoice, its report, an ad-hoc report and the audit events all
    // three produce. Created through the real services, so the drill covers the real schema
    // (JSONB canonical column included) rather than rows a test hand-crafted.
    TenantEntity tenant =
        tenants
            .findByExternalSubject("backup-drill")
            .orElseGet(() -> tenants.save(new TenantEntity("backup-drill", "Backup-Probe")));
    UUID invoiceId =
        invoices
            .create(tenant.getId(), invoiceJson("RE-BACKUP-DRILL").getBytes(StandardCharsets.UTF_8))
            .id();
    reports.validate(fixture("at-b2g-01-missing-order-reference.xml"), Optional.of(tenant.getId()));

    Map<String, Long> before = rowCounts();
    assertThat(before.get("invoice")).isPositive();
    assertThat(before.get("audit_event")).isPositive();

    exec("pg_dump", withConnection(DUMP_FLAGS, POSTGRES.getDatabaseName(), "--file=" + DUMP_PATH));

    // A fresh, empty target. `--clean --if-exists` must cope with objects that do not exist yet;
    // that is the first-run case and the one most likely to be broken by a copied flag list.
    exec("dropdb", List.of("--username=" + POSTGRES.getUsername(), "--if-exists", DRILL_DATABASE));
    exec("createdb", List.of("--username=" + POSTGRES.getUsername(), DRILL_DATABASE));
    exec("pg_restore", withConnection(RESTORE_FLAGS, DRILL_DATABASE, DUMP_PATH));

    for (String table : TABLES) {
      assertThat(countIn(DRILL_DATABASE, table))
          .as("row count of %s after restore", table)
          .isEqualTo(before.get(table));
    }

    // The Flyway history has to survive too, or the restored database looks unmigrated and the
    // application's next start either re-runs V1 against a populated schema or refuses to boot.
    assertThat(countIn(DRILL_DATABASE, "flyway_schema_history"))
        .as("Flyway's own history table must be part of a usable backup")
        .isEqualTo(countIn(POSTGRES.getDatabaseName(), "flyway_schema_history"));

    // Row counts alone would pass on a restore that produced the right number of empty rows, so one
    // value is read back in full: the canonical JSON, the largest and most structured column in the
    // schema.
    String restoredCanonical =
        single(
            DRILL_DATABASE, "select canonical::text from invoice where id = '" + invoiceId + "'");
    assertThat(restoredCanonical).contains("RE-BACKUP-DRILL").contains("Softwareentwicklung");
  }

  // ----------------------------------------------------------------------------------- helpers

  private static List<String> withConnection(List<String> flags, String database, String... extra) {
    return java.util.stream.Stream.of(
            List.of("--username=" + POSTGRES.getUsername(), "--dbname=" + database),
            flags,
            List.of(extra))
        .flatMap(List::stream)
        .toList();
  }

  /** Runs a Postgres client binary inside the container and fails the test on a non-zero exit. */
  private static void exec(String binary, List<String> arguments) throws Exception {
    String[] command =
        java.util.stream.Stream.concat(java.util.stream.Stream.of(binary), arguments.stream())
            .toArray(String[]::new);
    Container.ExecResult result = POSTGRES.execInContainer(command);
    assertThat(result.getExitCode())
        .as(
            "%s exited %s%nstdout: %s%nstderr: %s",
            binary, result.getExitCode(), result.getStdout(), result.getStderr())
        .isZero();
  }

  private Map<String, Long> rowCounts() {
    return TABLES.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                table -> table,
                table -> jdbc.queryForObject("select count(*) from " + table, Long.class)));
  }

  private static long countIn(String database, String table) throws Exception {
    return Long.parseLong(single(database, "select count(*) from " + table));
  }

  private static String single(String database, String sql) throws Exception {
    Container.ExecResult result =
        POSTGRES.execInContainer(
            "psql",
            "--username=" + POSTGRES.getUsername(),
            "--dbname=" + database,
            "--tuples-only",
            "--no-align",
            "--command=" + sql);
    assertThat(result.getExitCode()).as("psql stderr: %s", result.getStderr()).isZero();
    return result.getStdout().trim();
  }

  private static byte[] fixture(String name) throws Exception {
    try (var in = BackupRestoreDrillIT.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Fixture not found on classpath: " + name);
      }
      return in.readAllBytes();
    }
  }

  private static String invoiceJson(String number) {
    return """
        {
          "invoiceNumber": "%s",
          "type": "INVOICE",
          "issueDate": "2026-07-27",
          "currency": "EUR",
          "orderReference": "BBG-2026-4711",
          "supplierNumber": "L-100234",
          "seller": { "name": "Stoicera Software GesbR", "vatId": "ATU12345678", "email": "office@stoicera-software.at",
            "address": { "street": "Hauptplatz 1", "city": "Linz", "postalCode": "4020", "countryCode": "AT" } },
          "buyer": { "name": "Bundesbeschaffung GmbH", "vatId": "ATU87654321",
            "address": { "street": "Lassallestraße 9b", "city": "Wien", "postalCode": "1020", "countryCode": "AT" } },
          "lines": [
            { "id": "1", "description": "Softwareentwicklung", "quantity": "80", "unitCode": "HUR", "unitPrice": "120.00", "vatCategory": "STANDARD", "vatPercent": "20" }
          ],
          "paymentMeans": { "iban": "AT611904300234573201", "bic": "BKAUATWW" },
          "paymentTerms": "Zahlbar innerhalb von 30 Tagen ohne Abzug"
        }
        """
        .formatted(number);
  }
}
