# einvoice-at

[![CI](https://github.com/Stoicera/einvoice_at/actions/workflows/ci.yml/badge.svg)](https://github.com/Stoicera/einvoice_at/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Austrian e-invoicing platform: generate, validate and convert ebInterface 6.1 and Peppol BIS Billing 3.0 (UBL) — with human-readable German validation reports.**

A self-hostable Java 25 / Spring Boot platform built by [Stoicera Software Group](https://stoicera.com) as a production-grade reference system. Austria's federal government only accepts structured e-invoices (ebInterface or Peppol BIS) via e-rechnung.gv.at — and rejected invoices come back with Schematron output that non-technical users cannot read. This platform closes that gap.

> **Status: Milestone M3 — REST API + persistence + security.** On top of M2's ebInterface 6.1
> generation and validation, the `app` module is now a persistent, multi-tenant REST API
> (`/api/v1`): create and list invoices, the public anonymous validator, ebInterface XML output,
> OpenAPI / Swagger UI, RFC 9457 problem+json, PostgreSQL + Flyway, an append-only audit log, and
> Keycloak-backed OAuth2 alongside tenant API keys. The web UI lands in a later milestone. See
> [docs/MILESTONES.md](docs/MILESTONES.md).

## Deutsche Kurzfassung

**einvoice-at** ist eine selbst hostbare Plattform für die österreichische E-Rechnung: Sie **erzeugt** ebInterface 6.1 und Peppol BIS Billing 3.0 (UBL) aus strukturierten Rechnungsdaten, **validiert** hochgeladene XML-Rechnungen gegen XSD, Schematron und österreichische Geschäftsregeln — mit einem menschenlesbaren, deutschen Prüfbericht — und **konvertiert** zwischen beiden Formaten mit dokumentierten Mapping-Grenzen. Optional erklärt ein abschaltbarer KI-Assistent jeden Prüfungsfehler in einfacher Sprache. Aktueller Stand: Milestone M3 (REST-API + Persistenz + Security; die Web-UI folgt in einem späteren Milestone).

## Architecture

Modular monolith, Maven multi-module; boundary rules are enforced by ArchUnit as each module gains code — the `core`-is-JDK-only rule is active since M1, and the cross-module rules (only `app` knows the database; `formats-*`/`mapping` never import Spring) landed with M3. See [ADR-0002](docs/adr/0002-modular-monolith.md).

```
einvoice-at
├── core                  canonical invoice model (EN 16931 core subset), pure Java, zero Spring
├── formats-ebinterface   ebInterface 6.1 read/write/validate (wraps ph-ebinterface)
├── formats-ubl           Peppol BIS 3.0 / UBL 2.1 read/write/validate (wraps ph-ubl) — planned M4
├── mapping               canonical ↔ formats, golden-file tested
├── validation            XSD (phive) + own AT-B2G Schematron + Austrian business rules → ValidationReport
├── rendering             invoice → PDF / HTML print view — planned M4
├── ai-assist             LlmClient port + OpenRouter adapter, feature-flagged, degradable — planned M5
└── app                   Spring Boot app: REST API, security, persistence, audit (live since M3); web UI planned for a later milestone
```

`core`, `formats-ebinterface`, `mapping`, `validation` and `app` are built and tested as of M3; the remaining three rows (`formats-ubl`, `rendering`, `ai-assist`) are `package-info.java`-only stubs today (see the status note above).

Stack: Java 25, Spring Boot 4.1, PostgreSQL 17 + Flyway, Thymeleaf + htmx, Keycloak, Testcontainers, Selenium. Rationale in [ADR-0001](docs/adr/0001-java-spring-boot-stack.md).

## Quickstart

Requires Docker with Compose.

```bash
git clone https://github.com/Stoicera/einvoice_at.git
cd einvoice_at
cp .env.example .env   # then set the required values: POSTGRES_PASSWORD, KEYCLOAK_ADMIN and
                       # KEYCLOAK_ADMIN_PASSWORD (generate the passwords, e.g. openssl rand -base64 24)
docker compose up -d   # starts postgres, keycloak (dev realm auto-imported), mailpit and the app
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

## REST API

The `app` module serves the `/api/v1` REST API (M3). Interactive docs: **Swagger UI at
<http://localhost:8080/swagger-ui.html>** (OpenAPI JSON at `/v3/api-docs`).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/validate` | public | Validate an uploaded ebInterface 6.1 document (multipart `file`) → `ValidationReport`. Anonymous: nothing is persisted; authenticated: the report is persisted and audited. |
| `POST` | `/api/v1/invoices` | JWT or API key | Create an invoice from canonical JSON → `{id, report}` (201, `Location` header). |
| `GET` | `/api/v1/invoices` | JWT or API key | The tenant's invoices, newest first, paginated (`page`, `size`). |
| `GET` | `/api/v1/invoices/{id}` | JWT or API key | The stored canonical JSON. |
| `GET` | `/api/v1/invoices/{id}/ebinterface` | JWT or API key | The ebInterface 6.1 XML, regenerated on demand. |
| `GET` | `/api/v1/reports` | JWT or API key | The tenant's validation reports, paginated. |
| `GET` | `/api/v1/reports/{id}` | JWT or API key | One stored report, findings included. |
| `POST` | `/api/v1/api-keys` | JWT only | Mint an API key; the plaintext is returned exactly once. |
| `GET` | `/api/v1/api-keys` | JWT only | The tenant's keys (active and revoked), without secrets. |
| `DELETE` | `/api/v1/api-keys/{id}` | JWT only | Revoke a key (soft: `revokedAt` stamped, row retained). |

**Auth modes.** Two mechanisms, exactly one per request: an OAuth2 **bearer JWT** from Keycloak
(`Authorization: Bearer <token>`) for interactive logins, or a tenant **API key**
(`X-Api-Key: eiv_…`) for machines. Presenting both is refused with 400 (RFC 6750 §3.1) rather than
letting filter order decide which tenant the request runs as. `POST /api/v1/validate` is public (no
credential). API-key management (`/api/v1/api-keys`) is JWT-only — an API key can neither mint nor
revoke keys. Full auth design and honest known limits:
[ADR-0006](docs/adr/0006-auth-and-api-security.md).

**Limits.** Request bodies are capped at 2 MB (`MAX_REQUEST_BODY_SIZE`) for multipart uploads and
plain bodies alike, both answering 413. Anonymous `POST /validate` is rate-limited per IP. A tenant
holds at most 25 active API keys (`API_KEYS_MAX_ACTIVE_PER_TENANT`); revoked keys keep their rows
for the audit trail and do not count. `OAUTH2_AUDIENCE` optionally requires every token's `aud` to
name this API — off by default for the single-audience dev realm, recommended for any shared one.
`API_DOCS_ENABLED=false` removes the OpenAPI document and Swagger UI entirely.

**Response envelope.** `POST /invoices` and `POST /validate` both answer with a two-field envelope
`{"id", "report"}`: the persisted row's id (`null` for an anonymous `validate`, which persists
nothing) plus the `ValidationReport`.

**Errors.** Every error is RFC 9457 `application/problem+json`; each `type` is a stable URI under
`https://einvoice-at.stoicera.com/problems/`.

```bash
# 1. Fetch a dev access token from the compose Keycloak (dev-realm client, password grant).
#    Requires jq; the client id/secret and testuser are dev-only, shipped in keycloak/dev-realm.json.
TOKEN=$(curl -s \
  -d grant_type=password \
  -d client_id=einvoice-api \
  -d client_secret=dev-einvoice-api-secret-not-for-production \
  -d username=testuser -d password=testpass -d scope=openid \
  http://localhost:8081/realms/einvoice/protocol/openid-connect/token | jq -r .access_token)

# 2. Create an invoice from canonical JSON (JWT or X-Api-Key both work here).
curl -s -X POST http://localhost:8080/api/v1/invoices \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @invoice.json

# 3. Validate a document anonymously — no credential, nothing persisted.
curl -s -X POST http://localhost:8080/api/v1/validate \
  -F file=@invoice.xml

# 4. Fetch the regenerated ebInterface 6.1 XML for a stored invoice.
curl -s http://localhost:8080/api/v1/invoices/<id>/ebinterface \
  -H "Authorization: Bearer $TOKEN"
```

## Development

Requires JDK 25+ (build targets 25). Maven comes via the committed wrapper.

```bash
./mvnw verify                 # full build: Spotless check + unit + integration tests
./mvnw test -pl core          # fast domain feedback loop
./mvnw spotless:apply         # format before committing
docker compose up -d          # local stack (app, postgres, keycloak, mailpit)
```

`./mvnw verify` runs the `app` module's integration tests, which spin up PostgreSQL and Keycloak via
Testcontainers, so a running Docker daemon is required for the full build. `./mvnw test -pl core`
(and the other modules' unit tests) need no Docker.

Formatting is google-java-format, enforced by Spotless in every build and in CI.

## Testing

JUnit 5 + AssertJ + Mockito for unit tests, ArchUnit for module-boundary rules, Testcontainers for integration tests, Selenium WebDriver for E2E — built out milestone by milestone per [docs/ENGINEERING_STANDARDS.md](docs/ENGINEERING_STANDARDS.md).

**Domain modules.** `core` sits at 99.54 % line / 98.09 % branch coverage (JaCoCo gate 95/90), including a [jqwik](https://jqwik.net) property suite for money/VAT arithmetic and an ArchUnit rule pinning `core` to JDK-only dependencies. The M2 modules carry the same discipline — `formats-ebinterface` and `validation` gate at 90 % line / 85 % branch, `mapping` at 95/90. Mutation testing ([PIT](https://pitest.org)) gates all four in CI — `core` at 90 %, the rest at 85 % — so the coverage numbers have teeth, not just line reach. The security-critical `validation` module (the untrusted-input boundary) is gated deliberately: its surviving mutants are documented equivalent/defensive ones, not shape-asserting gaps.

**`app` module.** 95.24 % line / 83.33 % branch (JaCoCo gate 90/78, measured across unit *and* integration runs merged — most of this module's behaviour is only observable end to end). 48 unit tests and 70 integration tests across 15 IT classes, the latter against real PostgreSQL and real Keycloak via Testcontainers:

- **Auth matrix** (`AuthMatrixIT`) — both directions of every mechanism: anonymous, unknown key, revoked key, valid key, valid JWT, a bearer header that is not a JWT, an `alg=none` token, a genuine Keycloak token with a rewritten payload, and a request presenting two competing credentials.
- **Token validation** (`JwtDecoderTest`) — a throwaway JWKS over loopback and self-minted tokens, so wrong issuer, expired `exp`, a foreign signing key, and a foreign key impersonating the real `kid` can each be varied one at a time.
- **Tenant isolation** — for invoices, reports *and* API keys: one tenant can never read, revoke or list another's rows.
- **Transactional guarantees** (`ApiKeyServiceTransactionIT`) — a failing audit write rolls the key write back with it.
- **The rest** — Flyway migration and index assertions, repository round-trips through JSONB/`char`/`numeric` columns, rate limiting, request-body caps, security headers, the OpenAPI document and its off-switch, and the ArchUnit cross-module rules.

PIT is deliberately *not* applied to `app`: mutating a module whose tests each boot a Spring context and two containers costs minutes per mutant for little signal, and its genuinely algorithmic parts are covered by fast unit tests.

## Validation pipeline

`EbInterface61Validator` (module `validation`) validates an uploaded ebInterface 6.1 document against the Austrian B2G profile and returns a `ValidationReport` of German-first `Finding`s. The pipeline is staged with hard gating — a document must clear one stage before the next runs, and each stage stops the pipeline when continuing would be meaningless:

0. **Size guard** — an upload over 20 MB is rejected as `XML-02` before a single byte is parsed: a defensive, module-level cap that protects the validator independently of any caller. This is separate from — and looser than — the stricter 2 MB application-layer cap SPEC §4 places in front of the HTTP endpoint (`POST /api/v1/validate`, live since M3).
1. **Secure parse** — `SecureXml` parses the upload with an XXE-hardened, namespace-aware `DocumentBuilderFactory` (`disallow-doctype-decl`, external entities/DTD off). Not well-formed XML or a bare `DOCTYPE` → `XML-01`, pipeline stops.
2. **Format detection** — the root namespace is resolved against known ebInterface versions. An unrecognised namespace → `FORMAT-01`; a recognised but unsupported version (e.g. ebInterface 6.0) → `FORMAT-02`. Either stops the pipeline.
3. **XSD** — the bundled ebInterface 6.1 validation-executor-set (`VID_EBI_61`) runs via [phive](https://github.com/phax/phive). This VES is **XSD-only** — AUSTRIAPRO publishes no Schematron for ebInterface — so schema violations become `XSD-01` findings, each genuinely bilingual (the stage validates the document twice, once per `Locale`, because the underlying Xerces diagnostic text is baked in at validation time; see [ADR-0004](docs/adr/0004-validation-pipeline-and-xsd-messages.md)). An XSD-invalid document stops the pipeline: Schematron and business rules assume a structurally valid tree.
4. **Own AT-B2G Schematron** — runs only once the document is XSD-valid. `AT-B2G-01` checks the Auftragsreferenz is present; `AT-B2G-03` checks the Biller carries a contact e-mail address (`Address/Email`); `AT-B2G-04` checks the Biller carries a Lieferantennummer (`InvoiceRecipientsBillerID`); `AT-B2G-05` checks a `PaymentMethod` (`UniversalBankTransaction` or `NoPayment`) is present — all four are required for invoices to Austrian federal bodies.
5. **Java business rule** — runs alongside Schematron gating. `AT-B2G-02` checks every `IBAN` present under the payment method against the core `Iban` mod-97 checksum (the XSD only bounds an IBAN's length). The finding never echoes the IBAN itself — only its 1-based position in the document.

Every rule id the pipeline can produce is centralised in `RuleIds` (module `validation`) — the single registry the corpus and the CLI both depend on (the corpus asserts against it, the CLI only prints it), so the id scheme (`PREFIX-NN`) stays uniform as new rules land:

| Rule id | Stage | Meaning |
|---|---|---|
| `XML-01` | secure parse | upload is not well-formed XML |
| `XML-02` | size guard | upload exceeds the 20 MB defensive input-size cap |
| `FORMAT-01` | format detection | namespace matches no supported format |
| `FORMAT-02` | format detection | recognised ebInterface, unsupported version |
| `XSD-01` | XSD | document violates the ebInterface 6.1 schema |
| `AT-B2G-01` | own Schematron | Auftragsreferenz missing |
| `AT-B2G-02` | Java business rule | an IBAN present fails the mod-97 checksum |
| `AT-B2G-03` | own Schematron | Biller e-mail address (`Address/Email`) missing |
| `AT-B2G-04` | own Schematron | Lieferantennummer (`InvoiceRecipientsBillerID`) missing |
| `AT-B2G-05` | own Schematron | no `PaymentMethod` (`UniversalBankTransaction` or `NoPayment`) present |

**Golden-file corpus.** [`validation/src/test/resources/corpus/`](validation/src/test/resources/corpus/) is the pipeline's executable specification: `valid/` documents must clear every stage cleanly, `invalid/` documents each isolate exactly one deliberate defect against one rule id. `CorpusTest` runs every file through the real validator; the corpus's own README documents the file-by-file provenance. A reported mapping/validation bug is always added here as a new failing golden file before it is fixed in code.

**CLI.** `ValidationRunner` runs the pipeline over one or more files or directories and prints a German-first report per file (English mirror per finding). Run it over the golden-file corpus:

```bash
./mvnw -q -pl validation -am compile exec:java -Dexec.args="validation/src/test/resources/corpus"
```

Exit codes: `0` every validated file is valid, `1` at least one is invalid, `2` a usage or I/O error occurred — including no arguments, a nonexistent path, or a resolved file list that came back empty (e.g. a mistyped corpus path with no `*.xml` files), so a CI gate reading only the exit code can never mistake "found nothing" for "all valid".

## Deployment

Target: single Hetzner VPS via Dokploy (Traefik/TLS). Deployment documentation lands with milestone M6 in `docs/deployment.md`.

## Standards artefacts & credits

XSD and Schematron handling for ebInterface and Peppol builds on the excellent open-source work of [Philip Helger](https://github.com/phax): [ph-ebinterface](https://github.com/phax/ph-ebinterface), [ph-ubl](https://github.com/phax/ph-ubl), [phive](https://github.com/phax/phive) and [phive-rules](https://github.com/phax/phive-rules) (Apache-2.0/MIT). ebInterface is a standard of [AUSTRIAPRO](https://www.austriapro.at/); Peppol BIS Billing 3.0 is maintained by [OpenPeppol](https://peppol.org/). The AT-B2G Schematron rules (`validation/src/main/resources/schematron/`) are original to this repository — ebInterface ships no official Schematron at all, so there is no AUSTRIAPRO artefact to build them on.

## License

[Apache-2.0](LICENSE) © Stoicera Software Group — Raphael Lugmayr & Sebastian Kern GesbR
