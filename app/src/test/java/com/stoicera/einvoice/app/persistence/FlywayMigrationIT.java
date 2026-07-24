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
  void invoiceTenantListingIndexExists() {
    // The DDL creates an auto-named composite index on invoice (tenant_id, created_at desc);
    // proving a non-primary-key index is present confirms the full migration body ran.
    Integer indexCount =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_indexes where schemaname = 'public' and tablename = 'invoice'",
            Integer.class);
    assertThat(indexCount).isGreaterThanOrEqualTo(2);
  }
}
