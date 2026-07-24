# ADR-0005 — Persistence baseline: single schema, Flyway, JSONB canonical

Date: 2026-07-24 · Status: accepted

## Kontext

M3 turns the `app` module from a walking skeleton into a persistent REST API. The domain model in
`core` is deliberately identity-free (ADR-0003): identity, storage and infrastructure concerns live
only in `app`, the one module that knows the database (ADR-0002). SPEC §8 sketches the data layer as
Postgres "schemas" — `tenant`, `invoice` (canonical JSONB + extracted columns), `report`,
`audit_event`, `api_key` (hash only) — with Flyway migrations from V1 and the promise that anonymous
validation artefacts are never persisted. This ADR records how that sketch is realized.

## Entscheidung

**Single `public` schema, tables not SQL schemas.** SPEC §8's "schemas" are a conceptual grouping of
the data, not a directive to use five separate PostgreSQL `SCHEMA` objects. They are realized as five
tables (`tenant`, `invoice`, `report`, `api_key`, `audit_event`) in the default `public` schema. One
schema keeps migrations, connection setup and tenant-scoped queries boring; multi-schema isolation
would buy nothing at this scale and complicate every join and Flyway run.

**Flyway owns the schema; Hibernate only validates.** The schema is defined exclusively by versioned
Flyway migrations starting at `V1__baseline_schema.sql`. JPA runs with `spring.jpa.hibernate.ddl-auto:
validate`, so Hibernate confirms the entity mapping matches the migrated schema and never mutates it.
Migration-first is the standard, review-friendly path for production databases.

**Application-assigned identity, explicit temporal type.** Primary keys are `UUID`s assigned in the
entity constructor via `UUID.randomUUID()` — no database sequences, no round-trip to obtain an id,
and ids are stable the moment an object exists (which is what entity `equals`/`hashCode` rely on).
Timestamps are `java.time.Instant` mapped to `timestamptz`.

**Canonical JSON is the source of truth; XML is never stored.** The full canonical invoice is stored
as a `jsonb` column (`invoice.canonical`), bound through Hibernate's native
`@JdbcTypeCode(SqlTypes.JSON)` on a `String` field — no third-party `hibernate-types` dependency.
Scalar columns (`invoice_number`, `type_code`, `currency`, `payable_amount`, party names, dates) are
extracted projections for listing, filtering and the `(tenant_id, invoice_number)` uniqueness
guarantee. ebInterface / UBL XML is always regenerated from the canonical form, never persisted.
Validation `report.findings` is stored the same JSONB way.

**Fixed-length text where the domain is fixed-length; hashes only.** `currency` is `char(3)` (ISO
4217) and the SHA-256 columns (`api_key.key_hash`, `audit_event.payload_sha256`) are `char(64)` hex,
each bound as SQL `CHAR` via `@JdbcTypeCode(SqlTypes.CHAR)` so `ddl-auto: validate` matches the
column type. Plaintext API keys and audit payloads are never stored — only their hashes; a
non-secret `prefix` is kept for display. API-key revocation is a soft state (`revoked_at` stamped,
row retained) and the authentication finder filters on it.

**Tenant scoping via FK columns + scoped finders.** Every non-tenant row carries a plain `tenant_id`
`UUID` foreign key (not a mapped `@ManyToOne` — rows are always accessed tenant-scoped and never need
the parent hydrated). Repositories expose tenant-scoped finders
(`findByTenantId(UUID, Pageable)`, `findByIdAndTenantId(UUID, UUID)`) so the boundary check lives in
the query, not in caller code.

**Integration tests on real Postgres.** ITs extend `AbstractPostgresIT`, a singleton Testcontainers
Postgres wired to the Spring datasource via `@ServiceConnection`. The image is pinned to the exact
digest used by `docker-compose.yml` so tests and the local stack run byte-identical Postgres.

**Rate limiting is in-memory and single-instance, on purpose (T8).** `POST /api/v1/validate`'s
anonymous side needs a limit (SPEC §4) with nothing else in front of it yet, so `RateLimitFilter`
(`app/.../security`) keys a `com.bucket4j:bucket4j-core` token bucket per `HttpServletRequest
.getRemoteAddr()` in a bounded, evicting `ConcurrentHashMap` — no external store. Two consequences
worth recording rather than rediscovering later: (1) it does **not** read `X-Forwarded-For` or any
other forwarded-header — with today's one instance and no reverse proxy in front of it, honoring a
client-supplied header would just let an anonymous caller spoof a fresh bucket for free; (2) it is
therefore inherently per-instance state — a second replica would track its own independent buckets,
silently doubling the effective limit. Neither is a defect today. Both need revisiting together at
M6, when Traefik starts terminating in front of the app: pin down Traefik's forwarded-header
contract (which hop is trusted, which header it sets) before trusting anything the proxy forwards,
and decide then whether a shared/distributed bucket store is warranted or whether per-instance
limiting behind a proxy that itself rate-limits is good enough.

## Konsequenzen

- The schema is reproducible and auditable: `V1` is the single, reviewed source, and a drift between
  entities and schema fails the context at startup (`validate`), caught by CI, not in production.
- No ORM-generated DDL, no surprise columns; adding a field is a new migration plus a mapping change,
  reviewed together.
- Boot 4 packaging note (for future maintainers): Boot 4 split each technology's auto-configuration
  into its own module. A bare `flyway-core` does **not** activate Flyway — `spring-boot-starter-flyway`
  is required to pull `FlywayAutoConfiguration`. Likewise Boot 4.1 ships Testcontainers **2.x**, whose
  Maven modules are `testcontainers-postgresql` / `testcontainers-junit-jupiter` (renamed from the 1.x
  `postgresql` / `junit-jupiter`), and whose `DockerImageName` parser accepts a digest pin
  (`postgres@sha256:…`) but not the combined `tag@sha256:…` form.
- Later M3 infrastructure library choices (OpenAPI, rate limiting, the Keycloak test container) are
  recorded as they land — see the M3 worklog entries and any follow-up ADR.
