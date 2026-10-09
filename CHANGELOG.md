# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`v0.1.0` is the first tagged release, per
[ENGINEERING_STANDARDS §2](docs/ENGINEERING_STANDARDS.md) and
[MILESTONES M6](docs/MILESTONES.md). Everything before it was developed milestone by milestone on
`main`; the entries below reconstruct that history from the milestone record in
[docs/worklog.md](docs/worklog.md), which remains the session-by-session detail.

## [Unreleased]

Nothing yet.

## [0.2.0] — 2026-10-09

Maintenance and launch preparation since `v0.1.0`: the Peppol rule set 2026.5, a CVE dependency
update, two privacy and security fixes, and problem `type` URIs that finally resolve.

### Changed — API-visible

- **Problem `type` URIs** now live under `https://einvoice.sebastiankern.net/problems/` instead of
  `https://einvoice-at.stoicera.com/problems/`, a host that never resolved. The slugs are
  unchanged, so a client that branches on the slug is unaffected; one that compares the full URI
  must update it. The base is configurable (`PROBLEM_TYPE_BASE_URI`), and every
  `/problems/<slug>` on the demo redirects to [docs/problems.md](docs/problems.md), which lists
  all slugs. (#43)

### Added

- **Peppol BIS Billing 3.0 rule set 2026.5**, adopted ahead of OpenPeppol's 2026-08-17 date. (#26)
- **Off-site copy of database dumps**, with a check that the copy is readable; the host backup
  scripts are now the ones in this repository. (#25, #35)
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, a question issue template and `llms.txt`. (#44)

### Fixed

- **Anonymous uploads stay in memory.** The public validator promised an upload never touches a
  disk; with Boot's default multipart threshold it was briefly written to a temp file. (#29)
- **ReDoS in the PII scrubber.** The e-mail pattern backtracked quadratically: 40,000 characters of
  crafted text took about 7 s. Bounded to RFC 5321 lengths; CodeQL alert closed. (#42)
- The OpenAPI description called UBL support future work although it had shipped (#28); the API
  docs links disappear on deployments that switch the docs off (#27).

### Security and dependencies

- Spring Boot 4.1.1, Tomcat 11.0.26, Netty 4.2.19 (CVE fix). (#38)
- phive-rules 4.4.2, jaxb-runtime 4.0.9, the `eclipse-temurin` base image, Spotless 3.9.0,
  ArchUnit 1.5.0, and the `docker/login-action`, `actions/setup-java` and
  `docker/setup-buildx-action` workflow actions. (#13, #16, #33, #15, #32, #12, #23, #34)
- The OWASP Dependency-Check job is bounded to 90 minutes. (#41)
- `SECURITY.md`: the disclosure address had no DNS; reports now go through GitHub private
  vulnerability reporting or office@stoicera.com. (#40)

## [0.1.0] — 2026-07-27

The first release. A self-hostable platform that generates, validates and converts Austrian
e-invoices, with a German-language public validator, an authenticated dashboard, an optional AI
explanation per finding, and the operational scaffolding to run it.

### Added — invoice model and formats

- **Canonical invoice model** (`core`): the EN 16931 core subset as pure Java with zero
  dependencies, money as `BigDecimal` with explicit scale and rounding, Austrian VAT logic
  (20/13/10/0 %, reverse charge, exemption), and invariants checked at construction. Property-based
  tests (jqwik) for the arithmetic. ([ADR-0003](docs/adr/0003-canonical-model.md))
- **ebInterface 6.1** read and write (`formats-ebinterface`, wrapping ph-ebinterface), and the
  canonical ↔ ebInterface mapping in both directions.
- **Peppol BIS Billing 3.0 / UBL 2.1** read and write (`formats-ubl`, wrapping ph-ubl), including
  `ubl:Invoice` and `ubl:CreditNote` behind a sealed type, and both mapping directions.
  ([ADR-0007](docs/adr/0007-ubl-peppol-and-conversion.md))
- **Conversion between the two formats**, always through the canonical model so the invoice is
  understood once, with a per-document report of exactly which fields the target could not carry.
- **PDF print view** (`rendering`, Apache PDFBox): a German A4 invoice with the VAT breakdown as its
  own table, because § 11 UStG requires tax per rate. ([ADR-0008](docs/adr/0008-pdf-rendering.md))

### Added — validation

- **Two pipelines behind one entry point.** ebInterface: XSD → this project's own AT-B2G Schematron
  → hand-written business rules. UBL: the **official OpenPeppol rule set, executed unmodified** at a
  version pinned in code. ([ADR-0004](docs/adr/0004-validation-pipeline-and-xsd-messages.md))
- **XXE-hardened parsing** of every untrusted document, refusing a document merely for declaring a
  `DOCTYPE`, with an independent 20 MB module-level size guard.
- **German findings.** Every finding carries a German message; English second. ~80 Peppol rules an
  Austrian filer realistically trips are translated from the rule set's own assertion texts.
- **A golden-file corpus** of valid and systematically broken documents, one per rule.

### Added — API, persistence and security

- `POST /invoices`, `POST /validate` (anonymous, rate-limited, **never stored**),
  `POST /convert`, `POST /reports/{id}/explain`, `DELETE /tenant`, per-invoice `ebinterface`/`ubl`/
  `pdf` outputs, and tenant-scoped listings. RFC 9457 problem+json throughout; OpenAPI via springdoc
  with an off switch.
- **PostgreSQL + Flyway** from `V1`, five tables, canonical JSON stored as JSONB and no XML ever
  persisted. ([ADR-0005](docs/adr/0005-persistence-baseline.md))
- **OAuth2 / Keycloak plus API keys**, exactly one per request; keys stored as hashes only and
  refused the ability to manage keys or delete the tenant. Audit log of who did what and when,
  recording the payload's SHA-256 rather than the payload.
  ([ADR-0006](docs/adr/0006-auth-and-api-security.md))
- **Rate limiting** on the endpoints that cost CPU or money, with a different policy per endpoint and
  a stated reason for each difference.

### Added — web UI and AI

- **Public validator** (German-first, SEO meta, an explicit DSGVO notice) sharing one code path with
  the anonymous API so "an anonymous upload is never stored" has a single implementation.
- **Authenticated dashboard**: invoice list and detail with all three downloads, report history, a
  four-step create-invoice wizard, API-key management and a Konto page. Server-rendered Thymeleaf
  with **no CSS or JavaScript build step**; every page works with JavaScript disabled.
  ([ADR-0009](docs/adr/0009-web-ui.md))
- **AI explanations** behind `features.ai-explanations`, off by default, degrading to a friendly
  notice when the provider is unreachable. PII is scrubbed before any call and no document fragment
  is ever sent. ([ADR-0010](docs/adr/0010-ai-assist.md), [docs/privacy.md](docs/privacy.md))

### Added — GDPR

- **Art. 17 erasure**: every invoice, report, key and audit event of the calling tenant, in one
  transaction, scoped by the credential so it cannot be aimed at anyone else.
- **Art. 5(1)(e) retention**: a nightly purge of reports and audit events — and **never invoices**,
  because § 132 BAO obliges an Austrian business to keep them for seven years.
  ([ADR-0011](docs/adr/0011-retention-and-erasure.md))

### Added — operations (M6)

- **OpenTelemetry across the pipeline stages.** Traces and metrics over OTLP; the validation stages
  reach the tracer through a plain-Java port so `validation` stays free of Spring. An
  `observability` compose profile brings Prometheus, Tempo and Grafana with datasources and a
  dashboard provisioned. ([ADR-0012](docs/adr/0012-observability.md))
- **`/actuator/info`** naming the commit and build time — and nothing about the people who wrote the
  code.
- **`docs/deployment.md`** for Hetzner + Dokploy, plus `scripts/backup.sh` / `scripts/restore.sh` and
  an automated backup/restore drill that runs on every build.
- **`SECURITY.md`** with a STRIDE-light threat model and a disclosure policy.
- **`SERVER_FORWARD_HEADERS_STRATEGY`** so the rate limiter sees real client addresses behind a
  trusted proxy — and ignores forged ones without one. Behind a proxy the value is `native`, which
  reads the `X-Forwarded-For` chain from the end a proxy writes rather than the end a caller writes.
  All three directions tested.
- **Instance election for the retention job** via a PostgreSQL advisory lock, so several replicas
  against one database no longer all purge.
- **A real authorization-code login driven through Keycloak's own form** in a browser test, closing
  the gap M5 recorded by name.
- **Lighthouse ≥ 95 asserted in CI** on both public pages; measured 100 in all four categories.
- CI publishes to GHCR and triggers a Dokploy deploy webhook, both skipping loudly without their
  secrets.

### Known limits

Stated here as well as in [SECURITY.md](SECURITY.md), because a release note that lists only
successes is marketing:

- The rate limiter is **in-memory and per instance**; two replicas mean two allowances.
- An Art. 17 erasure deletes the audit trail with the rest of the tenant's data — the alternative is
  not honouring the request.
- Logout is **local**: it ends this application's session, not the Keycloak SSO session.
- Peppol finding messages are English where the rule set publishes only English; the German message
  is a German frame around the official wording, except for the ~80 translated rules.
- This platform is **not a certified Peppol Access Point** and sends invoices nowhere.
- No ZUGFeRD/Factur-X hybrid PDF.

[Unreleased]: https://github.com/Stoicera/einvoice_at/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Stoicera/einvoice_at/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Stoicera/einvoice_at/releases/tag/v0.1.0
