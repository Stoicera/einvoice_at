package com.stoicera.einvoice.app;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every integration test that needs the real database: it boots a single PostgreSQL
 * container, shared across the whole test run, and lets Spring Boot bind the application datasource
 * to it via {@link ServiceConnection}.
 *
 * <p>The container is a static singleton started once in a class initializer and never stopped —
 * the JVM shutdown and the Testcontainers reaper clean it up. Because the field is inherited,
 * Boot's service-connection support discovers it on every subclass, so subclasses only declare
 * their own {@code @SpringBootTest} (with whatever web environment they need); Flyway then applies
 * {@code V1} and Hibernate validates the mapping against the migrated schema.
 *
 * <p>The image is pinned to the exact tag <em>and</em> digest used by {@code docker-compose.yml},
 * so tests and the local stack run byte-identical Postgres.
 */
public abstract class AbstractPostgresIT {

  // Pinned to the exact same image bytes as docker-compose.yml. Testcontainers' DockerImageName
  // parser rejects the combined tag@digest form, so the pin is expressed by digest alone — which is
  // the content-addressed, byte-exact pin (the tag is only human sugar once a digest is present).
  // Digest copied verbatim from docker-compose.yml (postgres:17-alpine@sha256:742f40ea…193).
  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse(
              "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193")
          .asCompatibleSubstituteFor("postgres");

  @ServiceConnection
  protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

  static {
    POSTGRES.start();
  }
}
