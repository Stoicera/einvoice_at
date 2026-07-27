# Security Policy

`einvoice-at` handles invoice documents — commercial data with named parties, VAT identification
numbers and bank details. This document states what it defends, how, and what it does **not** defend,
because a threat model that only lists successes is marketing.

Status: 2026-07-27 · Milestone M6 · Applies to `main`

---

## Reporting a vulnerability

Report privately, not as a public issue:

- **E-mail:** security@stoicera-software.at
- Include: affected version or commit, reproduction steps, and what an attacker gains.
- We acknowledge within **72 hours** and aim to have a fix or a stated position within **14 days**.
- Please give us those 14 days before publishing. If a fix takes longer, we will say so and why.

There is no bug bounty. This is a portfolio and demonstration project; we will credit you in the
release notes unless you ask us not to.

### Out of scope

- Findings against the **local development stack** (`docker-compose.yml`): the Keycloak dev realm,
  the Grafana instance in the `observability` profile and Mailpit deliberately have weak or no
  authentication. Every credential in `keycloak/dev-realm.json` is committed on purpose and is
  labelled `-not-for-production`. See "Deployment expectations" below.
- Missing hardening headers on endpoints that serve no HTML.
- Reports produced solely by a scanner, with no demonstrated impact.

---

## What this system is

A modular monolith (`docs/adr/0002-modular-monolith.md`) with one deployed process:

| Trust boundary | What crosses it |
|---|---|
| Internet → app | Anonymous invoice uploads (public validator), authenticated REST and browser traffic |
| app → PostgreSQL | Tenant data: canonical invoice JSON, validation reports, audit events, API-key hashes |
| app → Keycloak | OIDC authorization-code login (browser) and JWT validation (API) |
| app → LLM provider | Validation **finding text**, PII-scrubbed, only when `FEATURES_AI_EXPLANATIONS=true` |
| app → OTLP collector | Traces and metrics, only when `OTEL_ENABLED=true` |

Data at rest lives in exactly five tables (`docs/adr/0005-persistence-baseline.md`). No XML is ever
stored: every format output is regenerated from the canonical JSON on demand.

---

## STRIDE (light)

### S — Spoofing

| Threat | Control | Where |
|---|---|---|
| Forged bearer token | Signature, issuer and optional audience validated against Keycloak's JWKS. No permissive fallback: a wrong issuer, an expired token, an unknown key, `alg=none` and a foreign key impersonating the real `kid` are each refused, each with its own test | `SecurityConfig`, `JwtDecoderTest`, `AuthMatrixIT` |
| Stolen API key | Keys are stored as **hashes only**; the plaintext is shown once, at creation, through a flash attribute so a page reload cannot re-show it | `ApiKeys`, `ApiKeyService` |
| API key used to manage API keys | Key management is OAuth2-only: a key can neither mint nor revoke keys, and cannot delete the tenant | `SecurityConfig` (`hasRole("USER")`) |
| Two credentials in one request | A request presenting both an API key and a bearer token is refused with 400 rather than silently running as whichever filter went last (RFC 6750 §3.1) | `ApiKeyAuthFilter` |
| Forged client IP to escape rate limiting | `X-Forwarded-For` is ignored unless the deployment explicitly declares a trusted proxy (`SERVER_FORWARD_HEADERS_STRATEGY=native`). When it does, the chain is read **right to left** past the internal proxies, so an entry the caller prepended is discarded rather than believed — the `framework` strategy reads the opposite, caller-controlled end and is documented as not-to-be-used. All three directions are tested | `ForwardedHeadersUntrustedIT`, `ForwardedHeadersTrustedIT` |
| CSRF against a logged-in browser session | Enforced on the browser filter chain; the public routes are **not** excluded, and the tests drive the real token flow | `SecurityConfig`, `PublicWebIT` |

### T — Tampering

| Threat | Control | Where |
|---|---|---|
| XXE / external entity in an uploaded document | Every untrusted document is parsed exactly once, through an XXE-hardened `DocumentBuilder` that refuses a document merely for **declaring** a `DOCTYPE`. Conversion detects the format through that same parser *before* the bytes reach a format adapter's own JAXB reader | `SecureXml`, `ConversionService.read`, `ConvertApiIT` |
| XML entity-expansion bomb | Same control: the `DOCTYPE` is refused before any entity is expanded | `SecureXmlTest` (billion-laughs fixture) |
| SQL injection | No dynamic SQL anywhere. JPA/Spring Data with bound parameters; the two hand-written statements (the advisory lock, the audit hash) are parameterised | — |
| Cross-tenant read or write | Every repository read is tenant-scoped by query, not by a post-fetch filter. A resource belonging to another tenant is indistinguishable from one that does not exist | `InvoiceService`, `ReportService`, `ApiKeyEndpointIT` |
| A conversion silently changing an amount | Conversion goes through the canonical model, which re-derives and re-verifies every total; a source total that disagrees is reported as `CONV-04`, not adopted | `docs/adr/0007-ubl-peppol-and-conversion.md` |

### R — Repudiation

| Threat | Control | Where |
|---|---|---|
| "I never created that invoice" | Append-only `audit_event` rows for invoice creation, validation, conversion and erasure: tenant, action, timestamp and the **SHA-256 of the payload** — never the payload | `AuditService` |
| Losing the trail to the retention job | The audit window (default 730 days) is separate from and longer than the report window, and `0` means keep forever | `RetentionService` |

**Honest limit:** the audit trail is deleted by an Art. 17 erasure request, because
`audit_event.tenant_id` is a foreign key to the row holding the Keycloak subject. What survives is
one log line with the tenant UUID and the row counts. `docs/adr/0011-retention-and-erasure.md`
argues why.

### I — Information disclosure

| Threat | Control | Where |
|---|---|---|
| An anonymous upload being retained | The public validator and the anonymous API run the **same** code path with an empty `Optional` meaning "write nothing"; a test asserts the report row count does not move | `ReportService.validate`, `PublicWebIT` |
| Customer data reaching the LLM provider | Off by default. When on: no XML fragment is sent at all (there is none to send — nothing stores XML), and the finding text is scrubbed of IBANs, VAT ids, e-mail addresses and long digit runs first, case-insensitively | `PiiScrubber`, `docs/privacy.md` |
| A stack trace or an echoed document value in an error body | RFC 9457 problem+json everywhere with fixed `type` URIs; foreign parser/SVRL text is length-bounded before it can reach a finding | `ApiExceptionHandler`, `BoundedText` |
| An operator's deployment details on a public endpoint | `/actuator/**` is authenticated except the health probes. `/actuator/info` publishes the commit id and build time and nothing about the people who wrote the code — no branch, no committer, no commit message, no environment | `SecurityConfig`, `BuildInfoEndpointIT` |
| The full API description handed to anyone | `API_DOCS_ENABLED=false` removes the OpenAPI document and Swagger UI | `application.yml` |
| Credentials in logs | No credential is ever logged in full; rejected keys are logged by prefix | `ApiKeyAuthFilter` |

### D — Denial of service

| Threat | Control | Where |
|---|---|---|
| Oversized upload exhausting the heap | 2 MB on **every** request body, not only multipart — enforced by a filter that checks `Content-Length` *and* counts bytes as they are read, so a chunked request cannot sidestep it, and that runs ahead of Spring Security | `RequestBodySizeLimitFilter` |
| Hostile document exhausting the validator | An independent 20 MB module-level guard, so the validator defends itself regardless of its caller | `InvoiceValidator.MAX_INPUT_BYTES` |
| Abuse of the free, CPU-heavy public validator | Per-IP token bucket; the browser page and the API endpoint share **one** bucket so the UI is not an unlimited detour around a limited endpoint | `RateLimitFilter` |
| Abuse of the expensive conversion endpoint | Per-credential bucket, applied to authenticated callers too — the endpoint admits no anonymous ones, so an authenticated exemption would leave a limit covering nobody | `RateLimitFilter` |
| Running up the operator's LLM bill | A separate, tighter per-credential bucket for both explain routes; plus `AI_MAX_FINDINGS_PER_REQUEST` bounding a single request. The two are different questions and both are answered | `RateLimitFilter`, `ReportExplanationService` |
| Unbounded memory in the limiter itself | The bucket map is capped; crossing the cap sweeps the least-recently-seen quarter | `RateLimitFilter.MAX_TRACKED_CLIENTS` |
| A connection pool held across a Peppol XSLT run | `ConversionService` is deliberately not `@Transactional`; the only write carries its own transaction | `ConversionService` |
| Unbounded API keys per tenant | 25 active keys; revoked rows are retained for the trail and do not count | `API_KEYS_MAX_ACTIVE_PER_TENANT` |

**Honest limit:** the rate limiter is **in-memory and per instance**. Two replicas mean two
allowances. A horizontally scaled deployment needs a distributed bucket store (bucket4j ships one).
This is stated in `RateLimitFilter`'s own Javadoc and is not fixed in M6.

### E — Elevation of privilege

| Threat | Control | Where |
|---|---|---|
| Reaching an endpoint no rule covers | The API chain ends in `.anyRequest().authenticated()`. The M5 review found `DELETE /api/v1/tenant` falling through to exactly that — reachable by an API key, the longest-lived credential class there is, for the platform's most destructive operation. It is now `hasRole("USER")` | `SecurityConfig`, `TenantErasureApiIT` |
| A browser route served by the API chain, or vice versa | Two filter chains in a load-bearing order, asserted rather than assumed: the API chain creates no session even on its error path, and the CSRF contrast between the chains is pinned | `SecurityChainRoutingIT` |
| A module reaching past its boundary | ArchUnit rules, enforced in CI — `core` depends on nothing but the JDK, `formats-*`/`mapping`/`validation` never import Spring, only `app` knows the database. A rule is never weakened to make a test pass | `ArchitectureTest` |

---

## Supply chain

- **Dependency scanning:** OWASP Dependency-Check runs as its own gated CI stage and fails the build
  on CVSS ≥ 7.0. Dependabot is configured for Maven, GitHub Actions and Docker.
- **Suppressions** live in `.owasp-suppressions.xml` and each one carries a reason. Suppressing a
  genuine finding to get a green build is forbidden by that file's own policy; the response to a real
  advisory is a version bump, and the root POM carries such overrides with the date they were
  verified.
- **Standards artefacts** (ph-ebinterface, phive, ph-ubl) are consumed as published, never
  hand-copied into this repository, and their rule-set versions are pinned in code so a dependency
  bump cannot silently change how a document is judged.
- **Container images** in `docker-compose.yml` are pinned by tag **and** digest.
- **GitHub Actions** are pinned by commit SHA.

> **The scan is a live gate.** The `NVD_API_KEY` repository secret is configured, so CI runs the
> real scan and fails the build on CVSS ≥ 7 — the two version overrides in the root POM are that
> gate's own verdicts, not precautionary bumps.
>
> A **fork or clone** without that secret gets the scan *skipped* instead — with a warning annotation
> and a job-summary block — rather than a doomed run: the NVD rate-limits unauthenticated clients
> hard enough that the sync stalls and fails with an unrelated-looking error, and a stage that is
> permanently red for an external reason teaches everyone to ignore red. The job is never a no-op
> either way; it still asserts the scan binds exactly once, at the root.

## Deployment expectations

The local `docker-compose.yml` is a development stack and is **not** a hardened deployment. A real
installation (see `docs/deployment.md`) must:

1. Set every credential in `.env` to a generated value; none of the committed dev values.
2. Terminate TLS in front of the application (Traefik via Dokploy) and set
   `SERVER_FORWARD_HEADERS_STRATEGY=native` so the rate limiter sees real client addresses. **Not
   `framework`** — it reads the leftmost `X-Forwarded-For` entry, which is the end the caller
   controls; `docs/deployment.md` §4 has the chain diagram.
3. Run Keycloak in production mode with its own database and a real hostname — not `start-dev`.
4. Set `SPRING_PROFILES_ACTIVE=prod` for structured JSON logs.
5. Consider `API_DOCS_ENABLED=false`.
6. **Not** expose the `observability` profile's Grafana: it runs with anonymous admin access,
   correctly for a laptop and wrongly for anything else.
7. Set `OAUTH2_AUDIENCE` on any shared realm, so a token minted for a different client in the same
   realm is not accepted.

## Cryptography

- Passwords: none. Authentication is delegated to Keycloak entirely.
- API keys: generated from `SecureRandom` and stored as SHA-256 hashes. A presented key is hashed
  and **looked up by hash** — there is no plaintext comparison anywhere, so there is no string
  compare to be timed. (The remaining signal is the database's own index lookup, which is not a
  practical oracle for a 256-bit random value.)
- Payload identity in the audit trail: SHA-256 of the request body.
- Transport: TLS is terminated by the reverse proxy; the application itself speaks plain HTTP inside
  the deployment network.

## Known limits, collected

These are stated here rather than left to be discovered:

1. The rate limiter is per instance, not distributed.
2. An Art. 17 erasure deletes the audit trail with the rest of the tenant's data.
3. Peppol finding messages are English where the rule set publishes only English; the German message
   is a German frame around the official wording. The ~80 rules an Austrian filer realistically trips
   are translated.
4. This platform is **not** a certified Peppol Access Point and does not send invoices anywhere.
5. Anonymous access to the OpenAPI document is on by default (portfolio instance) and has an
   off switch.
