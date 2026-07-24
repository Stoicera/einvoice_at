package com.stoicera.einvoice.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the schema baseline is real: the context boots only if Flyway applied {@code V1} against
 * the Testcontainers database and Hibernate ({@code ddl-auto=validate}) then confirmed every entity
 * maps to it. On top of that implicit guarantee, this test asserts the migration is recorded as a
 * successful V1 and that each baseline table and the invoice listing index physically exist.
 */
@SpringBootTest
class FlywayMigrationIT extends AbstractPostgresIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v1MigrationIsRecordedAsSuccessful() {
    Boolean success =
        jdbcTemplate.queryForObject(
            "select success from flyway_schema_history where version = '1'", Boolean.class);
    assertThat(success).isTrue();
  }

  @Test
  void allBaselineTablesExist() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'"
                + " and table_type = 'BASE TABLE'",
            String.class);
    assertThat(tables)
        .contains("tenant", "invoice", "report", "api_key", "audit_event", "flyway_schema_history");
  }

  @Test
  void v2MigrationIsRecordedAsSuccessful() {
    Boolean success =
        jdbcTemplate.queryForObject(
            "select success from flyway_schema_history where version = '2'", Boolean.class);
    assertThat(success).isTrue();
  }

  @Test
  void everyIndexTheListingPathsRelyOnExists() {
    // Previously this asserted only that `invoice` carried at least two indexes — enough to show
    // the V1 body ran, but it would not have noticed the missing report(invoice_id) index the M3
    // hostile review found (F9). Each listing query's supporting index is now named individually,
    // by the columns it covers, so dropping any one of them fails here.
    assertThat(indexedColumnsFor("invoice"))
        .as("GET /api/v1/invoices: the tenant's invoices, newest first")
        .anyMatch(
            definition -> definition.contains("tenant_id") && definition.contains("created_at"));
    assertThat(indexedColumnsFor("report"))
        .as("GET /api/v1/reports: the tenant's reports, newest first")
        .anyMatch(
            definition -> definition.contains("tenant_id") && definition.contains("created_at"));
    assertThat(indexedColumnsFor("audit_event"))
        .as("audit trail reads, newest first")
        .anyMatch(
            definition -> definition.contains("tenant_id") && definition.contains("occurred_at"));
    assertThat(indexedColumnsFor("report"))
        .as("V2: the invoice listing's report join (ReportRepository.findByInvoiceIdIn)")
        .anyMatch(definition -> definition.contains("(invoice_id)"));
  }

  /** The {@code CREATE INDEX} definitions Postgres reports for one table. */
  private List<String> indexedColumnsFor(String table) {
    return jdbcTemplate.queryForList(
        "select indexdef from pg_indexes where schemaname = 'public' and tablename = ?",
        String.class,
        table);
  }
}
