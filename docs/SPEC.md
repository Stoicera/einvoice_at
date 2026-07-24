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
| Web UI | **Thymeleaf + htmx + Tailwind (standalone CLI)** | Java-pure server-rendered UI: exactly what enterprise Java shops respect; no SPA build complexity |
| AuthN/Z | Spring Security (OAuth2 Resource Server) + **Keycloak** as IdP (docker compose); API keys for machine access (hashed at rest) | Answers the "do we need Keycloak?" question: Spring Security is the framework, Keycloak the IdP — document this in an ADR |
| AI | `LlmClient` abstraction → **OpenRouter** default (OpenAI-compatible API), model configurable; feature-flagged, degrades gracefully | Explain validation errors in plain German |
| Observability | OpenTelemetry (Micrometer bridge), Actuator health/readiness, JSON logs (Logback) | Standard |
| Testing | JUnit 5, AssertJ, Mockito, ArchUnit, Testcontainers (Postgres, Keycloak), **Selenium WebDriver** for E2E UI, Gatling (one scenario) | Selenium deliberately (JKU signal) |
| CI/CD | GitHub Actions → GHCR → Dokploy webhook (Hetzner VPS) | Per Engineering Standards |

## 2. Architecture

**Style: Modular monolith** (Maven multi-module; boundary rules ArchUnit-enforced as the modules gain code — see §2 Key rules). Twelve microservices without a business reason are on our anti-list; a well-modularised monolith is the honest senior choice for this domain. Write ADR-0002 explaining exactly this.

```
einvoice-at/
├── pom.xml                       (parent)
├── core/                         # canonical invoice model (EN 16931 core subset), pure Java, zero Spring deps
├── formats-ebinterface/          # ebInterface 6.1 read/write/validate (wraps ph-ebinterface); version-strategy interface for 7.0
├── formats-ubl/                  # UBL BIS 3.0 read/write/validate
├── mapping/                      # canonical ↔ formats; MapStruct evaluated but unrealized as of M2 (hand-written, semantic mapping); golden-file tests
├── validation/                   # orchestrates XSD + Schematron (phive) + own business rules; produces ValidationReport
├── rendering/                    # invoice → PDF / HTML print view
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

Security: public endpoints = `/`, `/validator`, `POST /validate` (rate-limited, max 2 MB, upload discarded after processing). Everything else: OAuth2 (Keycloak) or `X-Api-Key`. Audit log entries for create/validate/convert with tenant, timestamp, hash of payload (not payload itself).

## 5. Web UI (Thymeleaf + htmx)

Pages: Landing/"Prüfer" (public upload → report, German-first, SEO meta), Report view (findings grouped by severity, "Fehler erklären" button per finding when AI enabled), Dashboard (login): invoice list, create-invoice form (htmx wizard), API-key management. Design: clean, Stoicera-adjacent (dark/gold accents), no framework bloat; Lighthouse ≥ 95 on public pages.

## 6. AI assist (module `ai-assist`)

- Port: `LlmClient.complete(prompt, opts)`; adapter: OpenRouter (OpenAI-compatible HTTP), model default `anthropic/claude-sonnet-4.5` (configurable via env), timeout 15 s, retries 1.
- Service: `FindingExplainer` — input: ruleId, rule text, XML fragment (max ~40 lines around location, PII-scrubbed: names/IBANs masked), output: German explanation + concrete fix suggestion. Cache by (ruleId, fragmentHash).
- Prompts under `ai-assist/src/main/resources/prompts/*.st`, versioned; token/cost counters exported as OTel metrics.
- Degradation: provider down → report still fully usable, button shows friendly notice. Document data-flow + opt-in in `docs/privacy.md`.

## 7. Validation pipeline (module `validation`)

`secure parse (XXE-hardened DOM, XML-01) → detectFormat → XSD → Schematron (profile per format/version; AT-B2G-01 order-reference-present, AT-B2G-03 Biller e-mail present, AT-B2G-04 Lieferantennummer present and AT-B2G-05 PaymentMethod present are Schematron rules, not business rules, M3) → business rules (AT-B2G-02 IBAN valid; tax rates plausible, KZ totals unimplemented — out of scope, see [ADR-0004](adr/0004-validation-pipeline-and-xsd-messages.md) Entscheidung 9) → aggregate ValidationReport`. Each stage independent + testable; golden test corpus in `validation/src/test/resources/corpus/` (valid + systematically broken samples per rule). This corpus is a portfolio asset in itself — document it. For ebInterface, the Schematron stage runs project-own AT-B2G rules (see [ADR-0004](adr/0004-validation-pipeline-and-xsd-messages.md)) because AUSTRIAPRO publishes no official Schematron for ebInterface; Peppol BIS 3.0's official Schematron rule sets arrive unmodified with M4.

## 8. Data & persistence

Postgres schemas: `tenant`, `invoice` (canonical JSONB + extracted columns), `report`, `audit_event`, `api_key` (hash only). Flyway migrations from V1. Retention job: anonymous validation artefacts never persisted; tenant data delete endpoint (GDPR).

## 9. Deployment

- `docker-compose.yml`: app, postgres, keycloak (with realm import `dev-realm.json`), mailpit, optional `observability` profile (grafana+prometheus+tempo).
- Prod: single Hetzner VPS via Dokploy (Traefik TLS). `docs/deployment.md` covers provisioning, env vars, backup cron (`pg_dump`), restore drill.
- Version endpoint `/actuator/info` exposes git sha + build time.

## 10. Risks & honest limits (document in README)

- ebInterface 7.0 lands Q4 2026 → `FormatVersionStrategy` interface ready; adding a version must not touch `core`.
- Schematron rule sets evolve → rule-set versions pinned + documented; update procedure in docs.
- We are not a certified Peppol Access Point; sending via Peppol/USP is out of MVP scope (extension point documented in ADR-0006).
- Mapping ebInterface↔UBL is lossy in edge cases → conversion report lists exactly which fields.
