# Technical Specification — einvoice-at

Java/Spring Boot platform for generating, validating and converting Austrian e-invoices (ebInterface 6.1, Peppol BIS Billing 3.0 UBL).

Status: v1.0 · 2026-07-23 · Language of repo: English (domain terms stay German where legally precise)

---

## 1. Stack (pinned at kickoff, verify latest patch versions)

| Concern | Choice | Rationale |
|---|---|---|
| Language / Runtime | **Java 25 (LTS)** | Current LTS; signals up-to-date Java practice |
| Framework | **Spring Boot 4.1.x** (Spring Framework 7) | Current OSS line; 3.5 went EOL 2026-06-30 |
| Build | **Maven** (multi-module) | Enterprise default in AT; wrapper committed |
| Persistence | PostgreSQL 17 + Spring Data JPA (Hibernate) + **Flyway** | Standard, migration-first |
| e-invoice libs | **ph-ebinterface** (ebInterface XSD/model), **phive** + phive-rules (Schematron validation for ebInterface & Peppol), **ph-ubl** (UBL 2.1) — all MIT/Apache, actively maintained by Philip Helger; if a rule set is missing, fall back to official AUSTRIAPRO/OpenPeppol artefacts executed via ph-schematron | Don't reinvent validated standards artefacts; credit upstream in README |
| Mapping | Dedicated `mapping` module: internal canonical model (EN 16931 core) ↔ ebInterface 6.1 ↔ UBL BIS 3.0. Hand-written and heavily tested (MapStruct evaluated but unrealized as of M2; all mapping turned out semantic) | The canonical model is the heart of the system |
| PDF | OpenPDF or Apache PDFBox via a `rendering` module (HTML→PDF acceptable: openhtmltopdf) | Print view of invoice |
| Web UI | **Thymeleaf**, server-rendered; hand-authored CSS and ~20 lines of first-party JS instead of Tailwind CLI / htmx (M5 sync, [ADR-0009](adr/0009-web-ui.md)) | Java-pure server-rendered UI: exactly what enterprise Java shops respect; no SPA build complexity — and, as realized, no CSS or JS build step at all |
| AuthN/Z | Spring Security (OAuth2 Resource Server) + **Keycloak** as IdP (docker compose); API keys for machine access (hashed at rest) | Answers the "do we need Keycloak?" question: Spring Security is the framework, Keycloak the IdP — document this in an ADR |
| AI | `LlmClient` abstraction → **OpenRouter** default (OpenAI-compatible API), model configurable; feature-flagged, degrades gracefully | Explain validation errors in plain German |
| Observability | OpenTelemetry (Micrometer bridge) for traces **and** metrics, both over OTLP; Actuator health/readiness/info, JSON logs (Logback, ECS) — see §9a and [ADR-0012](adr/0012-observability.md) | Standard |
| Testing | JUnit 5, AssertJ, Mockito, ArchUnit, Testcontainers (Postgres, Keycloak), **Selenium WebDriver** for E2E UI, Gatling (one scenario) | Selenium deliberately (JKU signal) |
| CI/CD | GitHub Actions → GHCR → Dokploy webhook (Hetzner VPS) | Per Engineering Standards |

## 2. Architecture

**Style: Modular monolith** (Maven multi-module; boundary rules ArchUnit-enforced as the modules gain code — see §2 Key rules). Twelve microservices without a business reason are on our anti-list; a well-modularised monolith is the honest senior choice for this domain. Write ADR-0002 explaining exactly this.

```
einvoice-at/
├── pom.xml                       (parent)
├── core/                         # canonical invoice model (EN 16931 core subset), pure Java, zero Spring deps
├── formats-ebinterface/          # ebInterface 6.1 read/write/validate (wraps ph-ebinterface); version-strategy interface for 7.0
├── formats-api/                  # the format adapters' shared vocabulary (M4)
├── formats-ubl/                  # UBL BIS 3.0 read/write
├── mapping/                      # canonical ↔ formats; MapStruct evaluated but unrealized as of M2 (hand-written, semantic mapping); golden-file tests
├── validation/                   # orchestrates XSD + Schematron (phive) + own business rules; produces ValidationReport
├── rendering/                    # invoice → PDF print view (PDFBox; HTML view not built — ADR-0008)
├── ai-assist/                    # LlmClient port + OpenRouter adapter; error-explanation service; prompt templates versioned
├── app/                          # Spring Boot app: REST API, web UI, security, persistence, audit, rate limiting
└── docs/                         # PRD, SPEC, MILESTONES, ADRs, glossary, deployment
```

Key rules (ArchUnit-enforced as the involved modules gain code; the core rule is active since M1):
- `core` depends on nothing but the JDK.
- `formats-*` and `mapping` never import Spring.
- Only `app` knows the database.
- `ai-assist` is only called from `app` behind the feature flag `features.ai-explanations`.

## 3. Domain model (canonical, module `core`)

`Invoice` (invoiceNumber, type *(BT-3: 380 invoice / 381 credit note)*, issueDate, dueDate, currency=EUR default, orderReference *(Auftragsreferenz — mandatory for AT federal B2G)*, supplierNumber *(Lieferantennummer)*, seller, buyer, lines[], vatBreakdown[] *(BG-23; exemption reason BT-120/BT-121 mandatory for categories AE/E)*, paymentMeans (IBAN/BIC), paymentTerms, totals) — validated invariants: line math, tax math (AT rates 20/13/10/0 % + reverse charge/exempt), totals consistency, non-negative payable. No persistence `id` — the domain model is identity-free until the persistence layer (M3, see ADR-0003). Money as `BigDecimal` with scale rules; never floats. Write property-based tests (jqwik) for the arithmetic.

`ValidationReport`: source format, profile, list of `Finding` (severity ERROR/WARN/INFO, ruleId, location/XPath, messageDe, messageEn, aiExplanation?). Serialisable to JSON and rendered as German HTML report + downloadable PDF.

## 4. API design (module `app`, prefix `/api/v1`, OpenAPI via springdoc)

- `POST /invoices` — JSON in → creates invoice, returns id + validation result. `Accept: application/xml` variants: `GET /invoices/{id}/ebinterface`, `GET /invoices/{id}/ubl`, `GET /invoices/{id}/pdf`.
- `POST /validate` — multipart XML upload (ebInterface or UBL, auto-detected) → `ValidationReport` JSON. Anonymous allowed (public validator uses this) with rate limit; authenticated calls get persisted reports.
- `POST /convert?from=ebinterface&to=ubl` (and reverse) → converted XML + report of lossy fields.
- `POST /reports/{id}/explain` — AI explanations for findings (auth required, feature-flagged).
- `GET /invoices`, `GET /reports` — per-tenant listing, pagination.
- Errors: RFC 9457 problem+json everywhere.

Security: public endpoints = `/`, `/validator`, `POST /validate` (rate-limited, max 2 MB, upload discarded after processing). Everything else: OAuth2 (Keycloak) or `X-Api-Key` — exactly one of the two per request, a request presenting both being refused with 400 (RFC 6750 §3.1). Audit log entries for create/validate/convert with tenant, timestamp, hash of payload (not payload itself).

_M3 sync (2026-07-24):_ Realized under `/api/v1` as built. `POST /invoices` and `POST /validate` each answer with a two-field envelope `{"id", "report"}` — the persisted row's id (`null` for an anonymous `validate`, which persists nothing) plus the `ValidationReport`. The invoice format output shipped this milestone is `GET /invoices/{id}/ebinterface` (the `ubl`/`pdf` variants arrive with their modules, M4). API-key management (`POST`/`GET`/`DELETE /api/v1/api-keys`) is added and restricted to OAuth2 (JWT) logins, so an API key can neither mint nor revoke keys. Every problem+json `type` is a stable URI under `https://einvoice-at.stoicera.com/problems/`. Auth design and honest known limits: [ADR-0006](adr/0006-auth-and-api-security.md). `POST /convert` and `POST /reports/{id}/explain` remain later milestones (M4/M5).

_M4 sync (2026-07-25):_ `POST /convert?from=&to=` is realized as a multipart upload with the two
formats named `ebinterface` and `ubl`, answering `{"conversion", "xml", "report"}` — the conversion
report (what the trip cost), the converted document, and a validation report **of the result**
against the target format's own profile. `GET /invoices/{id}/ubl` and `GET /invoices/{id}/pdf` are
live; both regenerate from the stored canonical JSON, as `…/ebinterface` already did. `POST
/validate` now auto-detects UBL as well as ebInterface, as this section always described. Conversion
is authenticated and audited (`CONVERSION_RUN`); only `/validate` stays public. Design and honest
limits: [ADR-0007](adr/0007-ubl-peppol-and-conversion.md), [ADR-0008](adr/0008-pdf-rendering.md).
`POST /reports/{id}/explain` remains M5.

_M5 sync, part 2 (2026-07-26):_ `POST /reports/{id}/explain` **is live**, closing the last endpoint this
section had been carrying as "remains M5". It takes a **stored** report id and reads the findings from
the row — the caller cannot choose the text to be explained, unlike the public page's route, which has
to post the finding to itself because an anonymous report was never stored. Errors first, bounded by
`app.ai.max-findings-per-request` (default 10) per call; `?findingIndex=N` explains exactly one.
Nothing is persisted: the stored report keeps the validator's own verdict. With the feature flag off it
answers **503** `ai-explanations-disabled`, not 404 — the route is correct and the capability is absent,
and an operator must be able to tell those apart. A total provider outage is **503**
`ai-explanation-unavailable` rather than a 200 whose explanations are all null, which would be
indistinguishable from "nothing to explain".

`DELETE /tenant` is **added** (not previously in this section): erasure on request, GDPR Art. 17 —
every invoice, report, API key and audit event of the calling tenant, plus the tenant row, in one
transaction. Scoped by the credential, so it carries no tenant id and cannot be aimed at anyone else;
the API key used for the call is erased by it. See [ADR-0011](adr/0011-retention-and-erasure.md).

_M3 hostile-review fix wave (2026-07-25):_ The 2 MB cap now covers **every** request body, not only multipart uploads — no Boot or Tomcat property bounds an ordinary body, and `POST /invoices` buffers its own whole, so an application-layer filter ahead of Spring Security enforces it and answers the same 413 `content-too-large` the multipart cap does. Three further bounds/switches, all documented in `.env.example`: `OAUTH2_AUDIENCE` (optional `aud` validation, closing the limit ADR-0006 had recorded as open), `API_KEYS_MAX_ACTIVE_PER_TENANT` (default 25 active keys, revoked rows retained and uncounted), and `API_DOCS_ENABLED` (default on; `false` removes the OpenAPI document and Swagger UI).

## 5. Web UI (Thymeleaf, server-rendered)

Pages: Landing/"Prüfer" (public upload → report, German-first, SEO meta), Report view (findings grouped by severity, "Fehler erklären" button per finding when AI enabled), Dashboard (login): invoice list, create-invoice form (server-rendered multi-step wizard — corrected from "htmx wizard", see ADR-0009 Entscheidung 5), API-key management. Design: clean, Stoicera-adjacent (dark/gold accents), no framework bloat; Lighthouse ≥ 95 on public pages.

_M5 hostile-review sync (2026-07-26):_ the section heading said "Thymeleaf + htmx" while
[ADR-0009](adr/0009-web-ui.md) Entscheidung 5 had decided against htmx and the repository ships none.
Corrected here, and in ADR-0009's own title and Entscheidung 2, which still described htmx as
vendored in a directory that never existed (F11). Two counts in the same family were also wrong and
are corrected wherever they appear: `app.js` is 66 lines (about twenty of logic), not 40, and
`app.css` is 625, not the "~430" ADR-0009 measured itself against its own 700-line trigger (F12).

_M5 sync (2026-07-26):_ **The landing page, the public validator and the report view are realized;
the authenticated dashboard is not yet** — see `docs/worklog.md` for exactly what is open. Security is
now **two filter chains**: `/api/**` keeps M3/M4's stateless policy unchanged, and the browser surface
gets its own chain with a session, **CSRF enforcement** (the ENGINEERING_STANDARDS §4 requirement that
had nothing to protect while the app was API-only) and `oauth2Login` against Keycloak. The public
upload runs through the *same* `ReportService.validate(bytes, Optional.empty())` as the anonymous REST
endpoint, so "an anonymous upload is never stored" has one implementation, and the same rate-limit
bucket, so the page cannot be an unlimited detour around a limited endpoint.

_M5 sync, part 2 (2026-07-26):_ **The authenticated dashboard is realized.** Overview with per-tenant
counts, invoice list and detail, report list and detail (the same report fragment the public validator
renders, so the German wording and severity grouping cannot drift between the two surfaces), the
four-step server-rendered create-invoice wizard, the API-key page, and a **Konto** page carrying the
GDPR danger zone. German route segments throughout (`/app/rechnungen`, `/app/berichte`,
`/app/api-schluessel`, `/app/konto`), matching `/validator/pruefen`.

Three details worth stating because each was a decision:

- **The document downloads are `/app/...` routes, not links into `/api/v1`.** The three formats are
  already exposed by the API, and linking a dashboard button there would *not work*: `/api/**` is the
  stateless chain and never reads the browser's session cookie, so the click lands on a 401. The
  dashboard serves them from the same `InvoiceService` methods behind the session chain.
- **The wizard's last step calls the same `InvoiceService.create` as `POST /api/v1/invoices`** — same
  duplicate detection, same generated-and-validated report, same audit event. The draft lives in the
  HTTP session (per session, asserted) because step 3 collects a *list* of lines.
- **A freshly minted API key travels as a flash attribute** through a POST-redirect-GET, so a reload of
  the page cannot re-show the secret. Only its hash is stored, so the page has exactly one chance.

`WebExceptionHandler` renders the browser surface's 404s as an HTML page: `ApiExceptionHandler` is an
unrestricted `@RestControllerAdvice` and applies to Thymeleaf controllers too, so a mistyped invoice id
used to answer a correct 404 whose body was `application/problem+json` — raw JSON in a browser, in
English, from a German UI.

**Browser E2E and load testing live in a new `e2e` module** (Selenium against Chrome in a container for
Upload → Report → Erklären, plus a Gatling scenario for the public validator). Its tests are always
compiled but only *run* under `-Pe2e` / `-Pload`, in their own CI job — the same shape the `mutation`
and `security` profiles already use. The consequence is stated rather than implied: a green plain
`./mvnw verify` does not mean the browser flow works; the `e2e` job is what means that.

**Two deviations from §1, both deliberate and both recorded in [ADR-0009](adr/0009-web-ui.md):** no
Tailwind standalone CLI (a ~100 MB platform-specific binary downloaded in every build, for a handful
of pages) and no htmx (every page works with JavaScript disabled; the two fragment swaps this UI needs
are ~20 lines of first-party script whose markup contract is a subset of htmx's own). Lighthouse ≥ 95
is measured at M6, where MILESTONES schedules it.

## 6. AI assist (module `ai-assist`)

- Port: `LlmClient.complete(prompt, opts)`; adapter: OpenRouter (OpenAI-compatible HTTP), model default `anthropic/claude-sonnet-4.5` (configurable via env), timeout 15 s, retries 1.
- Service: `FindingExplainer` — input: ruleId, rule text, XML fragment (max ~40 lines around location, PII-scrubbed: names/IBANs masked), output: German explanation + concrete fix suggestion. Cache by (ruleId, fragmentHash).
- Prompts under `ai-assist/src/main/resources/prompts/*.st`, versioned; token/cost counters exported as OTel metrics.
- Degradation: provider down → report still fully usable, button shows friendly notice. Document data-flow + opt-in in `docs/privacy.md`.

_M5 sync (2026-07-26):_ Realized, with three corrections to this section and one addition. Design and
honest limits: [ADR-0010](adr/0010-ai-assist.md); data flow: [docs/privacy.md](privacy.md).

1. **The model default is `anthropic/claude-sonnet-5`.** The id named above has a successor; corrected
   rather than left to fail on the first real call. The tier is unchanged.
2. **No sampling parameter is sent at all.** The current Anthropic models reject a non-default
   `temperature`/`top_p`/`top_k` with HTTP 400, and OpenRouter forwards the body as given — so the
   port deliberately has no such field, and a test asserts none reaches the wire. Tone comes from the
   prompt template.
3. **No XML fragment is sent — this is not built, and cannot be without breaking a stronger promise.**
   The public validator retains no upload and stored invoices hold no XML, so at the moment a user
   clicks "Erklären" there is no document to quote 40 lines of. PII scrubbing is undiminished by this:
   a Schematron message quotes the offending document value verbatim, so IBAN/e-mail/UID/long-number
   masking has real work to do on the finding text itself.
4. **Added:** the usage/cost numbers leave the module through an `LlmUsageListener` port that `app`
   bridges to Micrometer, so `ai-assist` stays Spring-free (SPEC §2) while meeting
   ENGINEERING_STANDARDS §8's token/cost requirement. Cost is the provider's own reported figure, never
   a local price table.

## 7. Validation pipeline (module `validation`)

`secure parse (XXE-hardened DOM, XML-01) → detectFormat → XSD → Schematron (profile per format/version; AT-B2G-01 order-reference-present, AT-B2G-03 Biller e-mail present, AT-B2G-04 Lieferantennummer present and AT-B2G-05 PaymentMethod present are Schematron rules, not business rules, M3) → business rules (AT-B2G-02 IBAN valid; tax rates plausible, KZ totals unimplemented — out of scope, see [ADR-0004](adr/0004-validation-pipeline-and-xsd-messages.md) Entscheidung 9) → aggregate ValidationReport`. Each stage independent + testable; golden test corpus in `validation/src/test/resources/corpus/` (valid + systematically broken samples per rule). This corpus is a portfolio asset in itself — document it. For ebInterface, the Schematron stage runs project-own AT-B2G rules (see [ADR-0004](adr/0004-validation-pipeline-and-xsd-messages.md)) because AUSTRIAPRO publishes no official Schematron for ebInterface.

_M4 sync (2026-07-25):_ **Peppol BIS 3.0's official Schematron rule sets arrived unmodified, as promised.** A UBL document takes a different route through the same entry point: one stage running the OpenPeppol VES (XSD + EN 16931 + BIS, already sequenced by the rule set) at a version pinned in code — 2026.5 since 2026-08-07 (2025.11 as M4 shipped). Findings carry the rule set's own assertion ids rather than project-local ones. The dispatch between the two pipelines is `DocumentFormat`, keyed on the root namespace, which is also what closed ADR-0004's deferred polymorphism seam. See [ADR-0007](adr/0007-ubl-peppol-and-conversion.md), including the rule-set upgrade procedure.

## 8. Data & persistence

Postgres schemas: `tenant`, `invoice` (canonical JSONB + extracted columns), `report`, `audit_event`, `api_key` (hash only). Flyway migrations from V1. Retention job: anonymous validation artefacts never persisted; tenant data delete endpoint (GDPR).

_M3 sync (2026-07-24):_ The five "schemas" above are realized as five tables in the single `public` schema (not separate SQL schemas), Flyway from `V1__baseline_schema.sql` — rationale in [ADR-0005](adr/0005-persistence-baseline.md). The "anonymous artefacts never persisted" promise is met and enforced today ([ADR-0006](adr/0006-auth-and-api-security.md)); the tenant-data-delete endpoint and the retention job are deferred to M5 (with the dashboard).

_M5 sync (2026-07-26):_ The anonymous promise now holds for the **web UI too**, through the same code
path rather than a second one, and `PublicWebIT` asserts the row count does not move. **The
tenant-delete endpoint and the retention job are still open** — carried again rather than quietly
dropped, and named as unimplemented in [docs/privacy.md](privacy.md) §4 rather than hidden behind a
phrase like "Löschkonzept vorhanden".

## 9. Deployment

- `docker-compose.yml`: app, postgres, keycloak (with realm import `dev-realm.json`), mailpit, optional `observability` profile (grafana+prometheus+tempo).
- Prod: single Hetzner VPS via Dokploy (Traefik TLS). `docs/deployment.md` covers provisioning, env vars, backup cron (`pg_dump`), restore drill.
- Version endpoint `/actuator/info` exposes git sha + build time.

_M6 sync (2026-07-27):_ **All three are realized**, with two corrections and one addition worth
stating.

1. **The observability profile exists and is what this section describes** — Prometheus, Tempo and
   Grafana, datasources and a dashboard provisioned so it needs no clicking. It is *not* deployed:
   its Grafana runs with anonymous admin access, which is right for a laptop and wrong for anything
   reachable, and `docs/deployment.md` says so.
2. **The profile does not switch observability on by itself**, and the compose file says so rather
   than implying otherwise. A profile decides which *services* start; it cannot set an environment
   variable on a service. The command is `OTEL_ENABLED=true docker compose --profile observability
   up -d`. Both exporters default to **off**, because Boot's own defaults are "enabled, pointing at
   localhost:4318" and would have every installation posting into a socket nobody listens on.
3. **`/actuator/info` publishes the commit id and the build time, and deliberately nothing else** —
   no branch, no committer name or e-mail address, no commit message, no environment. It is
   authenticated, and "authenticated" is not a reason to publish the people who wrote the code.
   `.git` is part of the Docker build context so the deployed artefact is not the one artefact
   unable to identify itself.

**Added:** `SERVER_FORWARD_HEADERS_STRATEGY` (default `none`). Behind Traefik it must be `native`,
or every anonymous caller shares the proxy's address and the per-IP rate limit becomes one global
bucket; without a proxy it must stay `none`, or `X-Forwarded-For` is caller-supplied text and the
limit is free to bypass. It must **not** be `framework`: that strategy resolves the client from the
leftmost `X-Forwarded-For` entry, which is the end a caller writes, so the limit stays bypassable
behind the proxy too (M6 hostile review, F1). Design and honest limits:
[ADR-0012](adr/0012-observability.md), [docs/deployment.md](deployment.md), [SECURITY.md](../SECURITY.md).

## 9a. Observability (M6)

Traces **and** metrics are exported over **OTLP**; there is deliberately no `/actuator/prometheus`.
Both are one mechanism to configure, and a scrape endpoint would have needed either a credential for
Prometheus or a hole in the rule that authenticates all of `/actuator/**` except the health probes.
ENGINEERING_STANDARDS §5 names OTLP export as an acceptable shape; Prometheus 3 ingests it directly.

MILESTONES M6 asks for traces **across the pipeline stages**, and the stages live in `validation`,
which §2 keeps free of Spring. They reach Micrometer through `ValidationObserver` — a plain-Java
port, the same shape `ai-assist` uses for its token and cost numbers. `app` implements it in one
adapter. Two observation families result, each one name plus one low-cardinality tag drawn from a
closed set (`einvoice.validation.stage{stage}`, `einvoice.pipeline{step}`).

Log-to-trace correlation falls out of having both features on: micrometer-tracing writes
`traceId`/`spanId` into the MDC and Boot's ECS format (profile `prod`) writes the MDC out as
top-level fields.

The one non-obvious requirement: **percentile histograms must be switched on** for any meter a
latency panel is drawn from. A Micrometer timer publishes count, sum and max by default and no bucket
boundaries at all, so `histogram_quantile` answers `NaN` for every quantile — which is what the first
version of the Grafana dashboard displayed.

## 10. Risks & honest limits (document in README)

- ebInterface 7.0 lands Q4 2026 → `FormatVersionStrategy` interface ready; adding a version must not touch `core`.
- Schematron rule sets evolve → rule-set versions pinned + documented; update procedure in docs.
- We are not a certified Peppol Access Point; sending via Peppol/USP is out of MVP scope (extension point to be captured in a dedicated ADR when Peppol sending is scoped, M4+ — the ADR-0006 slot this line originally reserved was taken by auth & API security).
- Mapping ebInterface↔UBL is lossy in edge cases → conversion report lists exactly which fields.
