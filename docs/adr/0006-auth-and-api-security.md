# ADR-0006 — Authentication and API security: Keycloak resource server, API keys, tenant model

Date: 2026-07-24 · Status: accepted · Amended 2026-07-25 (M3 hostile-review fix wave: audience validation, credential exclusivity, default bounds, api-docs switch, problem-vocabulary and logging corrections)

## Kontext

M3 opens the `app` module's REST API (`/api/v1`, SPEC §4) to two very different callers: an
interactive dashboard user who logs in through a browser, and a machine — a tenant's CI job or ERP
connector — driving the API headless. SPEC §1/§4 fix the shape of the answer (Spring Security as the
framework, Keycloak as the IdP, `X-Api-Key` for machine access, RFC 9457 problem+json for every
error) and Engineering Standards §9 mandates an ADR that records *why* that shape and, just as
importantly, what it deliberately does not yet defend. ADR-0005 owns the persistence baseline and
the rate limiter's single-instance honesty; this ADR is the security companion the `SecurityConfig`
CSRF comment points at.

## Entscheidung

**Keycloak is the IdP; Spring Security runs as an OAuth2 Resource Server.** The two are not
alternatives — they answer different questions. Keycloak is the external identity provider that
authenticates humans and *issues and signs* the access tokens; the app never sees a password. Spring
Security is the in-process framework that *enforces* on every request: `SecurityConfig` configures an
`oauth2ResourceServer` whose `JwtDecoder` is built from the realm JWKS endpoint (keys fetched lazily
on first token validation, so the context boots with no IdP round-trip and token-free tests need no
IdP at all). When an issuer is configured, a token must additionally carry a matching `iss`. This is
the "do we need Keycloak?" question §9 asks answered concretely: yes, as the IdP, with Spring
Security as the resource server.

**Two authentication mechanisms sit side by side, one per request.** `ApiKeyAuthFilter` resolves an
`X-Api-Key` header ahead of the bearer-token filter; a request presents a JWT *or* a key, never both
(enforced — see "Exactly one credential per request" below).
Authorization lives in the rule set, not scattered through controllers: `POST /api/v1/validate` is
`permitAll` (the public validator), `/api/v1/api-keys/**` requires a JWT login (`ROLE_USER`), and
everything else under `/api/**` is `authenticated`. A JWT login is granted `ROLE_USER`; an API key
carries `ROLE_API_KEY`. That single distinction is what the `/api-keys` rule keys on, so "an API key
may never mint or revoke API keys" is enforced in the security layer itself rather than by trusting
controller code to re-check.

**API keys: hashed at rest, plaintext shown once.** A key is `eiv_` followed by 32 bytes of URL-safe
`SecureRandom` (~43 chars). Only its SHA-256 (64 lowercase hex) is persisted (`api_key.key_hash`,
unique); the plaintext is returned to the caller exactly once at creation and is unrecoverable
afterwards. A non-secret 8-character display prefix (`eiv_` + four chars) is kept alongside the hash
so a key is recognisable in a listing without being usable. Minting is OAuth2-only (above).
Revocation is soft: `revoked_at` is stamped and the row retained, and the authenticating finder
(`findByKeyHashAndRevokedAtIsNull`) filters on it, so a revoked key stops working immediately while
its audit trail survives. Creation and revocation each run in one transaction with their audit write
(ADR-0005), so a key is never left persisted without its `API_KEY_CREATED`/`API_KEY_REVOKED` event.

**Tenant model: the Keycloak `sub`, provisioned on first sight.** A tenant (Mandant) is identified by
the stable Keycloak `sub` claim (`tenant.external_subject`, unique); `preferred_username`, falling
back to `sub`, becomes the display name. There is no separate signup step: the first authenticated
request for a new subject provisions the tenant row lazily (`TenantProvisioningService`). Two
concurrent first requests would both find no row and both try to insert; the database is the arbiter,
not application locking — the `unique(external_subject)` constraint lets exactly one insert win, and
the loser catches the integrity violation and adopts the winner's row (`saveAndFlush`, deliberately
not `@Transactional`, so the failed insert rolls back on its own and the re-read sees the committed
winner). An API-key request needs no provisioning: the key row already carries its tenant id.

**Anonymous validation persists nothing.** `POST /api/v1/validate` is the one public endpoint. An
anonymous caller gets the `ValidationReport` back and *zero* database rows are written — no invoice,
no report, no audit event (GDPR stance, SPEC §8). The only trace an anonymous caller leaves anywhere
is a transient in-memory rate-limit bucket keyed by IP (ADR-0005), which is never persisted. An
authenticated caller (JWT or API key) additionally gets the report persisted (`report.invoice_id`
null — an ad-hoc run is not tied to a stored invoice) and a `VALIDATION_RUN` audit event recorded,
and the response's `id` then carries that persisted report's id instead of `null`.

**CSRF is disabled, by design.** The API is stateless (`SessionCreationPolicy.STATELESS`): no HTTP
session is created and every request re-authenticates from its bearer token or key. CSRF is an attack
on ambient cookie-session credentials that a browser attaches automatically; this API keeps no cookie
session and reads its credential from a header a cross-site form cannot set, so CSRF protection would
guard nothing here while breaking non-browser clients. Spring's default security response headers are
left in place. (When the M3+ browser dashboard introduces a cookie-backed session, that surface will
need its own CSRF stance — out of scope for this stateless API.)

**Exactly one credential per request — enforced, not assumed.** `ApiKeyAuthFilter` runs before the
bearer-token filter, whose authentication simply overwrites this one's, so "a request presents a JWT
*or* a key" was a property of filter ordering rather than of the system: a request carrying tenant
A's key and tenant B's token silently executed as tenant B. In a multi-tenant billing API, which
tenant owns a write must not be an accident of ordering. Presenting both is now refused with
`400 multiple-credentials`, the answer RFC 6750 §3.1 already prescribes. Only a *bearer*
`Authorization` competes — a `Basic` header is not a credential this API accepts and must not turn an
ordinary API-key request into an error.

**Bounded by default.** Three limits, each configurable, each defaulting to a value that is safe
rather than generous: request bodies at 2 MB (`MAX_REQUEST_BODY_SIZE`), applied to plain bodies as
well as multipart uploads — nothing in Boot or Tomcat bounds an ordinary body, and `POST /invoices`
buffers its own whole, so the cap is enforced by a filter ahead of Spring Security (an oversized body
is refused *before* authentication, never after the server has already buffered it); active API keys
at 25 per tenant (`API_KEYS_MAX_ACTIVE_PER_TENANT`), with revoked rows retained for the audit trail
and not counted; and the per-IP anonymous rate limit on `POST /validate` already described in
ADR-0005.

**The API description is publishable, not published by default-without-thinking.** The OpenAPI
document and Swagger UI are served anonymously, which is right for a local or portfolio instance —
but springdoc warns about exactly that on every boot, and a full API description of a B2G invoicing
system reaching anonymous callers should be a decision. `API_DOCS_ENABLED=false` removes both (one
flag, so the document and the UI can never disagree about being exposed).

**One problem vocabulary.** Every error is RFC 9457 `application/problem+json` with a stable `type`
URI under `https://einvoice-at.stoicera.com/problems/` — one slug per condition (`invalid-json`,
`invalid-invoice`, `invoice-not-found`, `report-not-found`, `api-key-not-found`,
`duplicate-invoice`, `api-key-limit-reached`, `content-too-large`, `multiple-credentials`,
`rate-limited`, …). `ApiExceptionHandler` also stamps this namespace onto Spring MVC's own framework
problems (415, 405, 413, a missing multipart part → 400, …) so the whole API speaks one vocabulary,
and the filters — which run outside MVC dispatch, where `@RestControllerAdvice` never sees them —
write the same shape through the shared `Problems` helper rather than a second copy of the
convention. Services throw **domain** exceptions and never `ResponseStatusException`: that class
exists only to smuggle an HTTP status out of a service, and where it was used (`ApiKeyService`) it
also left the condition speaking the framework's generic `not-found` type. An ArchUnit rule now keeps
it out of everything but `..app.api..`.

The catch-all 500 never leaks an exception message, class name or stack trace **to the caller** — and
never discards it on the server either. It was doing both before the M3 hostile review: a production
500 left no trace anywhere, since the module had no logger at all. Security-relevant events (an
unresolvable API key, a rate-limited caller, an oversized body, two credentials, a provisioned
tenant, an unhandled error) are now logged, with no credential ever written in full — a rejected key
appears only as the same 8-character non-secret display prefix the `api_key` table already stores.

## Konsequenzen — and honest known limits

- Enforcement is centralised and testable rather than sprinkled through controllers: the full auth
  matrix (anonymous / API-key / OAuth2 across every route) is pinned by `AuthMatrixIT` against a
  real Keycloak (Testcontainers), not a mock.
- **JWT audience validation is available but off by default** (closed in the M3 hostile-review fix
  wave; the entry previously recorded it as an open limit). The decoder always runs Spring's default
  validators plus an issuer validator when an issuer is configured: signature, expiry and `iss`.
  Those prove a token is *genuine*, not that it was minted *for this API* — without an audience
  check, any client in the same realm can obtain a signature-valid, correctly-issued token and it
  authenticates as `ROLE_USER`. Setting `app.oauth2.audience` (env `OAUTH2_AUDIENCE`, e.g. the API's
  client id) adds a validator requiring `aud` to contain that value. It stays **opt-in** on purpose:
  switching it on unconditionally would reject the tokens of a single-audience dev realm, whose
  `aud` is Keycloak's default `account`. A shared or production realm should set it. Every branch —
  accepted, wrong audience, wrong issuer, expired, foreign signature, and a foreign key
  impersonating the real `kid` — is pinned by `JwtDecoderTest`, which publishes a throwaway JWKS
  over loopback and mints its own tokens, so it can vary exactly one thing at a time in a way an IT
  against a real Keycloak cannot.
- **The rate limiter is single-instance, in-memory, keyed by `remoteAddr`.** It does not parse
  `X-Forwarded-For` (spoofable with no trusted proxy in front) and its buckets are per-replica state.
  Both are revisited at M6, when Traefik terminates TLS in front of the app — the full rationale and
  the M6 to-do (pin the trusted-proxy/forwarded-header contract; decide on a distributed bucket
  store) live in ADR-0005.
- **`DuplicateInvoiceException` embeds the raw invoice number in its exception message**
  (`"Duplicate invoice number for tenant: <number>"`). It is never surfaced today: the 409 problem
  detail is a static, generic string and the app has no logger that writes the exception message
  anywhere. It is latent, not a live leak — the moment a logger is wired onto that path the invoice
  number would land in logs — so it is recorded here rather than rediscovered later.
- Deliberately deferred, tracked so they are not forgotten: the GDPR tenant-data-delete endpoint and
  the anonymous-artefact retention job (SPEC §8) land with the M5 dashboard; OWASP dependency-check in
  CI, `/actuator/info` git-sha + build-time exposure (SPEC §9) and the Traefik forwarded-headers
  contract land at M6 (see also ADR-0005's deferral note).
