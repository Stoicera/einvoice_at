# Worklog — einvoice-at

## 2026-07-26 — M5 (part 2): dashboard, GDPR erasure, retention, explain API, browser E2E — M5 complete

**Status: M5 is complete.** All five items the part-1 entry listed as open are done, plus three defects
that part 1 had shipped without noticing — one of which stopped the whole application from booting.

**The first thing this session did was the thing part 1 said to do first, and it found a showstopper**

Part 1 closed with "Not verified in a browser or in compose. A compose smoke run and a look at the
rendered pages are the first thing the next session should do." That was the right instruction and it
paid immediately: **the compose stack could not start at all.**

`docker-compose.yml` set `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI` alongside the
four explicit endpoint URLs. Spring Boot treats a provider `issuer-uri` as a request to perform OIDC
**discovery at startup**, so the app fetched `<issuer>/.well-known/openid-configuration` while building
`clientRegistrationRepository`. The issuer had to be the browser-facing `localhost:8081`, and inside
the container that is the container's own loopback: connection refused, bean creation failed, and the
application crash-looped. Not "the login is broken" — **everything**, public validator included. The
block's own comment described the correct solution ("the four URLs are given explicitly so discovery
cannot be needed") and the line underneath defeated it.

Fixing it surfaced a second, quieter bug in the same area. `start-dev` derives every URL from the
request's `Host` header, **including the `iss` claim it stamps into tokens**, so the realm called itself
`localhost:8081` to the browser and `keycloak:8080` to the app. A browser login's id_token is minted
over the back channel and would have carried `keycloak:8080`, while `OAUTH2_ISSUER_URI` expects
`localhost:8081`. Measured rather than assumed: before pinning, the two discovery documents reported two
different issuers; after `KC_HOSTNAME` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, the same issuer on both
channels with the token endpoint still app-facing.

**What shipped**

- **The authenticated dashboard.** Overview with per-tenant counts; invoice list and detail (verdict,
  report history, canonical JSON, three downloads); report list and detail rendering the *same*
  fragment as the public validator; the four-step server-rendered create-invoice wizard; the API-key
  page; and a **Konto** page. German route segments throughout, matching `/validator/pruefen`.
- **GDPR Art. 17 + Art. 5(1)(e)** — `TenantErasureService` (dashboard danger zone + `DELETE
  /api/v1/tenant`) and `RetentionService` (nightly purge). ADR-0011, which ADR-0009 had already
  forward-referenced by name.
- **`POST /api/v1/reports/{id}/explain`** — the last endpoint SPEC §4 carried as "remains M5".
- **The `e2e` module** — Selenium/Chrome for Upload → Report → Erklären, and the Gatling validator
  scenario. New `e2e` CI job.
- **Three defects part 1 shipped:** the compose boot failure above; a `/favicon.ico` 404 on every page
  load; and `ContentDisposition.attachment().filename(...).toString()` on the **builder**, which put
  `org.springframework.http.ContentDisposition$BuilderImpl@9336bc5` in the header of every download.

**Decisions**

- **The dashboard's downloads are `/app` routes, not links into `/api/v1`.** The three formats are
  already exposed by the API and linking there is the obvious move; it would not work, and the failure
  would have been a login redirect inside a download tab rather than anything a reviewer would notice.
  `/api/**` is the stateless chain and never reads the session cookie. The service is the shared part;
  the transport is not, and cannot be. A test asserts each download is served *to a session*.
- **The wizard's last step calls the same `InvoiceService.create` as the API.** Same duplicate
  detection, same generated-and-validated report, same audit event. A wizard-specific creation path
  would have been a second place for invoice creation to drift — the same argument the public upload
  already settled for validation.
- **The draft lives in the session, not in hidden fields.** Step 3 collects a *list*, and re-serialising
  a growing list through hidden inputs on every step is more markup and more ways to lose a line. The
  session already exists for the login and for CSRF, so this adds no mechanism. A test asserts a second
  session cannot see the draft.
- **The wizard validates presence only; `core` owns the rules.** IBAN checksum, VAT-category
  consistency, totals — all caught at creation and rendered as a message. Re-implementing them in the
  controller would give the platform a second, drifting definition of a valid invoice. The one
  exception is "at least one line", checked when leaving step 3, because discovering it two steps later
  would point the user at the wrong page.
- **The retention job never deletes an invoice, and that is the point of ADR-0011.** Expiring invoices
  alongside reports is the symmetrical implementation and would be actively harmful: § 132 BAO obliges
  an Austrian business to keep invoices for **seven years**. The load-bearing test is therefore a
  negative one — a ten-year-old invoice survives a purge. Erasure on request *does* delete them,
  because the person asked.
- **Erasure deletes the audit trail too.** Keeping it is defensible in the abstract (Art. 5(2)
  accountability), and impossible in practice: `audit_event.tenant_id` is a foreign key to the row
  holding the Keycloak subject and display name, so "keep the audit trail" means "keep the tenant row"
  means not honouring the request. What survives is one log line with the tenant **UUID** — a surrogate
  this platform minted — and the row counts.
- **`0` is the retention off switch, not a second `enabled` flag.** Two mechanisms can disagree; one
  cannot. Default **on** with generous windows, because a retention scheme that must be switched on is
  off on every installation nobody configured, and those are why Art. 5 exists.
- **A typed `LÖSCHEN` in the browser, no confirmation parameter on the API.** A dialog needs JavaScript
  and is dismissible by a stray Enter; a typed word is not produced by a misclick. An API caller
  issuing `DELETE /tenant` with that tenant's own credential has already stated intent unambiguously,
  and `?confirm=yes` is ceremony every client hard-codes on its first run. What protects there is that
  the credential *scopes* the deletion: no tenant id in the request, so it cannot be aimed at anyone.
- **The explain endpoint answers 503 in two distinguishable ways.** Flag off →
  `ai-explanations-disabled`; provider produced nothing for anything requested →
  `ai-explanation-unavailable`. A 200 whose every `aiExplanation` is null is indistinguishable from
  "nothing to explain", so a total outage would look like success and the caller would retry nothing.
  Partial success stays 200, because that body is honest.
- **The capability is checked before the arguments.** With AI off, even an unknown report id answers
  503 rather than 404. Written expecting 404 and changed after watching it fail: a precondition is
  checked before its arguments, a disabled deployment does no database work for a route it cannot
  serve, and the answer does not vary with whether the id exists.
- **`spring-security-test` + MockMvc for the dashboard, a real browser for the public flow.** The
  dashboard sits on the browser chain, which authenticates a cookie session — it deliberately accepts
  none of the bearer tokens the other 20 ITs use. Driving Keycloak's login form over HttpClient to
  reach a page assertion would be testing Keycloak; `oauth2Login()` injects the authentication the real
  flow would have produced. Boot 4.1's module split moved `@AutoConfigureMockMvc` to
  `org.springframework.boot.webmvc.test.autoconfigure` in its own artifact, which is why there are two
  new test dependencies rather than one.
- **`spring-boot-maven-plugin` now writes the executable jar under the `exec` classifier.** Without it
  `repackage` replaces `app.jar` with the fat jar, whose classes live under `BOOT-INF/classes` — so the
  `e2e` module resolved `app` and could not see a single class in it, while classes from the *test*-jar
  resolved fine. The error points at the source file, not at the packaging. The Dockerfile copies
  `app-exec.jar` now.
- **`WebExceptionHandler`, scoped to `..app.web..` with highest precedence.** `ApiExceptionHandler` is
  an unrestricted `@RestControllerAdvice`, so it applied to the Thymeleaf controllers too: a mistyped
  invoice id gave a correct 404 whose body was `application/problem+json` — raw JSON in a browser, in
  English, from a German UI. Only the not-found family is handled; a genuine 500 still falls through to
  the catch-all that logs it in full.
- **The `e2e` module's tests compile always and run only under a profile.** Same shape as the existing
  `mutation` and `security` profiles. The consequence is stated in three places rather than implied: a
  green plain `./mvnw verify` does **not** mean the browser flow works.

**Two bugs the tests caught before the code shipped, worth recording**

- **`targetsFor` selected findings by value and mapped back with `List.indexOf`.** A rule fires once per
  offending line, so identical findings are normal — three of them would have returned position 0 three
  times, explaining the first repeatedly and the others never. Found by inspection, then pinned; the
  unit test was verified by reintroducing the bug and watching it fail.
- **The Gatling assertions caught a configuration gap, not a performance problem.** The first run showed
  36 of 50 requests rate-limited despite `RATE_LIMIT_VALIDATE_CAPACITY` being raised. Cause:
  **seven env vars documented in `.env.example` were never passed through in `docker-compose.yml`** —
  the whole `RATE_LIMIT_*` family, `MAX_REQUEST_BODY_SIZE`, `API_KEYS_MAX_ACTIVE_PER_TENANT`. Setting
  them in `.env` did nothing, silently, since M3. A CI step now asserts the two lists agree.

**Verification**

- `./mvnw clean verify` green across all 11 modules. **1017 tests**, up from 898.
  Per module: `core` 220, `formats-api` 7, `formats-ebinterface` 14, `formats-ubl` 31, `mapping` 201,
  `validation` 125, `rendering` 36, `ai-assist` 107, `app` 90 unit + 186 integration (27 IT classes).
- `app` coverage **94.5 % line / 81.0 % branch** against the unchanged 90/78 gate. The gate **failed**
  mid-session at 74.8 % branch; closed by unit-testing `InvoiceDraft` and `FindingView` and adding four
  wizard cases — never by touching the threshold (CLAUDE.md).
- `./mvnw -pl e2e verify -Pe2e` green: 3 browser tests, 15 s. `gatling:test -Pload` green against the
  compose stack: 50/50 requests, p95 18 ms, max 637 ms (first-request Schematron warm-up).
- **Verified in a real browser this time**, which is what part 1 asked for: landing page and validator
  screenshotted, an upload driven through Chrome with the fragment swap observed, and the console
  checked — which is how the favicon 404 was found.
- Compose stack verified booting from a rebuilt image, `/` 200, `/app` → 302 → Keycloak with PKCE.
- Every command in the new `e2e` CI job was executed locally and passes. The job itself first runs on
  push; nothing about it is asserted here beyond that.

**Honest gaps**

- **No automated test completes a real authorization-code login.** The dashboard ITs inject the
  authentication a finished flow would have produced; `OAuth2ClientWiringIT` proves the client is wired
  without discovery and that `/app` redirects into the authorization endpoint; the full round trip was
  verified by hand against the compose Keycloak. What is missing is a browser driving Keycloak's login
  form. It needs the browser and the app to agree on a Keycloak URL whose `iss` matches what the app
  validates — the same dual-URL problem the compose fix above solves — and the shape is known: both
  containers on one network, `KC_HOSTNAME` pinned to the browser-facing alias, explicit endpoints. Not
  built, because MILESTONES names the *public* flow for E2E and this would be scope beyond it.
- **Lighthouse ≥ 95 is not measured.** MILESTONES schedules it at M6; the pages are built for it.
- The retention job runs in every instance. Several instances against one database all purge — the
  deletes are idempotent so this is correct but wasteful. Instance election belongs to M6.

**Next**

- **M5 is complete; the next step is the M5 hostile review on this branch before merge**, the
  per-milestone pattern (audit → prioritised findings → fix all, test-first). This entry's "honest
  gaps" and the three part-1 defects are the obvious places to point it first.
- One hard date unchanged: the Peppol rule-set upgrade to 2026.5 **before 2026-08-17** (ADR-0007
  Entscheidung 8), including re-checking the 78 German translations against 2026.5's assertion texts.

## 2026-07-26 — M5 (part 1): ai-assist, public web validator, German Peppol messages

**Status: M5 is NOT complete.** What shipped is the public browser surface and the AI feature, both
tested and green; the dashboard, the GDPR endpoints, Selenium and Gatling are open. The honest
accounting is at the end of this entry rather than implied by omission.

**What shipped**

- **`ai-assist` filled in** — it had been a `package-info.java` stub since M0. `LlmClient` port (one
  method: no streaming, tools or conversation state nobody calls), `OpenRouterLlmClient` on the JDK's
  own `HttpClient`, `PiiScrubber`, versioned prompts under `src/main/resources/prompts/*.st`,
  `FindingExplainer` with a bounded LRU cache, and `LlmUsageListener` so `app` can bridge cost/token
  metrics to Micrometer without this module importing Spring or a metrics library. 107 tests, JaCoCo
  97.3 / 97.8 (gate 90/85), PIT 86/95 = 91 % (gate 85), now in the CI mutation job.
- **German Peppol messages** (`PeppolMessagesDe`, 78 assertions) — the gap M4 recorded honestly and
  handed to M5 by name. Every `PEPPOL-EN16931-R*` rule plus the EN 16931 rules an Austrian filer
  realistically trips, including the four VAT-category families for 20/13/10/0 %, Übergang der
  Steuerschuld and Steuerbefreiung.
- **The public browser surface** — landing page, `/validator` with the DSGVO notice MILESTONES names
  by name, and a report view grouping findings by severity, German first. Two security filter chains
  (ADR-0009). `app`: 53 unit + 104 integration tests (was 52 + 83), coverage 93.4 / 78.7 against the
  unchanged 90/78 gate.
- **AI explanations wired** behind `features.ai-explanations`, default off, with the "Erklären" button
  per finding and a friendly notice when the provider is unreachable.
- **Docs:** ADR-0009 (web UI), ADR-0010 (AI assist), `docs/privacy.md`, README (status, module map,
  two new sections, testing), SPEC §1/§5/§6/§8 sync, glossary M5 section, `.env.example`,
  compose, dev realm.

**Decisions**

- **The rule text was read off the artefacts, not recalled.** The 78 German messages are translations
  of the assertion texts in `CEN-EN16931-UBL.xslt` and `PEPPOL-EN16931-UBL.xslt` as shipped by
  phive-rules-peppol 4.4.1 (OpenPeppol 2025.11), extracted from the jar to translate against. A
  translation written from memory would have been the same amount of typing and worth much less.
  `messageEn` is never touched: this project executes those rules unmodified (ADR-0007), and that
  principle extends to their wording.
- **No `temperature`, `top_p` or `top_k` is sent — and this was nearly a bug.** The current Anthropic
  models reject a non-default value for any of the three with HTTP 400 (removed with Opus 4.7 /
  Sonnet 5), and OpenRouter forwards the body it is given. A well-meaning `temperature: 0.2` in the
  adapter would therefore have failed *every* request against this platform's own default model, and
  would have looked entirely reasonable in review. Two tests pin the absence — one on the adapter, one
  on the bytes the wired application actually sends. SPEC §6's model id was stale for the same
  underlying reason and is corrected to `anthropic/claude-sonnet-5`.
- **Two filter chains, not one widened one.** An API client wants 401 + problem+json where a browser
  wants a login redirect; an API request must create no session where a form needs one; CSRF matters
  for a cookie session and is meaningless for a bearer call. One chain would have meant a condition per
  difference. `/api/**` is byte-for-byte M4's policy; the browser chain is new. The order is
  load-bearing and asserted, not assumed.
- **`oauth2Login` is applied only when a `ClientRegistrationRepository` exists.** Calling it
  unconditionally fails context startup wherever no OAuth2 client is configured — which is every
  persistence and API integration test. This is the same mistake the M3 fix wave made once with the JWT
  validator list, and the same fix: ask whether the collaborator is there.
- **A browser login and an API token for one person are ONE tenant.** Both resolve through the
  Keycloak `sub`. Keying them separately would have been easy and would have quietly partitioned each
  user's data in two — invoices created through the API invisible in the dashboard.
- **The public upload runs through the same service as the anonymous API**, with the same empty
  `Optional` that means "write nothing". A UI-specific validation path would have been a second place
  for the DSGVO promise to break; a test asserts the `report` row count does not move.
- **No XML fragment is sent to the LLM, and it cannot be.** SPEC §6 described ~40 lines around the
  location. The public validator retains no upload and stored invoices hold no XML, so at click time
  there is no document to quote. Building it would mean keeping uploads — a worse trade than a less
  specific explanation. This does not weaken the scrubber: a Schematron message quotes the offending
  value verbatim, which is exactly what it masks.
- **The cache key is the *scrubbed* text, with a consequence worth being deliberate about.** Two
  documents violating `AT-B2G-02` with different IBANs both scrub to `IBAN [IBAN] ungültig` and share
  one explanation. That is right, not a leak — the model saw neither IBAN, so its answer cannot depend
  on which it was — and it is one fewer paid call. A test pins it so it is not later "fixed".
  My first version of that test asserted the opposite and was wrong.
- **No Tailwind and no htmx** (ADR-0009). Tailwind's standalone CLI is a ~100 MB platform-specific
  binary downloaded in every build, for a handful of server-rendered pages; htmx is a fine library but
  every page here works with JavaScript disabled and the two fragment swaps needed are 40 lines of
  first-party script whose markup contract is a subset of htmx's own. Both are deviations from SPEC §1,
  both recorded with what they cost, and both reversible in one file.
- **CSRF is enforced on the browser chain and the tests drive it properly** — fetching the page,
  harvesting the token, posting it back, and asserting a token-less post is refused. Excluding the
  public routes from CSRF would have been three lines and would have made the token decoration.
- **`Texts.safeEcho` promoted out of `core.internal`**, closing a carry item open since M1. The note
  said "once `app` starts consuming it directly"; the trigger had actually fired at M2, at `mapping`.
- **The security scan and the M4 review's own guard both earned their keep.** `everyLibModuleIsListed`
  failed the moment `ai-assist` gained its first class — exactly the drift M4 added it to catch.

**Decisions taken at the owner's request, closing open items (2026-07-26)**

The owner delegated four decisions that had been sitting open. All four are now recorded in the ADRs
rather than in a conversation:

- **ADR-0009: both web-UI deviations stay, with numeric triggers.** Hand-authored CSS until `app.css`
  passes 700 lines or a second person works on it regularly (currently ~430, one). No htmx until an
  interaction genuinely *needs* partial updates. Walking through the dashboard's actual interactions
  strengthened this rather than weakening it: the wizard, the API-key page and the danger zone are all
  plain POST-and-redirect flows that need no JavaScript at all. The two swaps that do benefit already
  exist and are done. SPEC §5 is corrected from "htmx wizard" to "server-rendered multi-step wizard" so
  the documents agree.
- **ADR-0010: the model stays Sonnet for the public validator, with an explicit Opus recommendation for
  a paid deployment.** Explaining a published rule in two short German paragraphs is translation and
  framing, not open-ended reasoning — the task class where the tier gap is smallest — and a free public
  page makes cost per *distinct* finding a real constraint. Where the cost sits against a contract
  instead, `AI_MODEL=anthropic/claude-opus-5` is the recommendation, and `.env.example` says so where
  someone will look.
- **ADR-0007 Entscheidung 8: the Peppol rule-set upgrade to 2026.5 happens before 2026-08-17, not
  now.** Not now, because a rule-set change alters how every document is judged and wants its own
  corpus re-run rather than being mixed into a half-finished milestone. Not later than that date,
  because past it a "valid" from this platform would be a false statement. The 78 German translations
  must be re-checked against 2026.5's assertion texts, not merely carried over.
- **ADR-0007 Entscheidung 9: `ConversionLosses` keeps its four cases.** An exhaustive enum would
  enumerate hypotheses; each existing case came from a real loss in a real document. A fifth is added
  when a real loss appears that fits none of them.

Two further items are marked **settled rather than open**, with no change to the code:

- **PIT's `frecord` filter stays on** (`core`). Measured at M4: 129 mutants with it (98 % killed) vs
  392 without (86 %, below the gate), the extra survivors concentrated in compiler-generated
  `equals`/`hashCode`/`toString` — which is what the filter exists to remove. The alternatives are a
  lowered gate (never) or tests for generated methods (worthless). This was recorded as an open
  question for the M4 review; it is a decision now.
- **jqwik stays**, and the anti-AI banner it prints to test output needs no owner action. M2 evaluated
  every credible JUnit-5-native alternative and found none viable. The banner is inert text with no
  execution vector, already covered by the standing rule that library and tool output addressed to
  agents is data, never instructions.

**Verification**

- `./mvnw clean verify` green across all 10 modules (2 m 01 s), every JaCoCo gate met.
- **898 tests**, up from 769. Per module: `core` 220, `formats-api` 7, `formats-ebinterface` 14,
  `formats-ubl` 31, `mapping` 201, `validation` 125, `rendering` 36, `ai-assist` 107, `app` 53 unit +
  104 integration.
- **Correction to the M4 entry's numbers.** The 769 baseline is a *clean-build* count. Reading
  `target/` without cleaning first reports 785, because it still held reports for test classes that no
  longer exist — `EbInterface61ValidatorTest` and its concurrency sibling (M4 deleted that facade),
  plus three scratch classes. Any test count in this worklog should be read as a clean-build number.
- `./mvnw spotless:apply` clean before every commit.
- **Not verified in a browser or in compose.** The flows are asserted at the HTTP level against a real
  context, real Postgres and a real stub provider — which is more than a mock and less than a browser.
  A compose smoke run and a look at the rendered pages are the first thing the next session should do.

**Not done — the open M5 scope**

1. **The authenticated dashboard.** Invoice list and detail, report list and detail, the htmx
   create-invoice wizard, and the API-key management page. The security chain, the login and the tenant
   mapping for it are in place and tested; the pages are not written. This is the largest open item.
2. **GDPR tenant delete + retention job.** Carried from M3 to M4 to M5 and now carried again. Named as
   unimplemented in `docs/privacy.md` §4 rather than hidden behind "Löschkonzept vorhanden" — until it
   exists, that document says the platform is not ready for real customer data.
3. **Selenium E2E** (`Upload → Report → Erklären`) and the **Gatling** validator scenario, with the
   `e2e` CI job on `main`. The flow itself is covered by `AiExplanationIT` at the HTTP level; what is
   missing is the browser and the load profile. ENGINEERING_STANDARDS §3 requires both.
4. **`POST /api/v1/reports/{id}/explain`** — the REST counterpart of the UI's explain route (SPEC §4).
   The UI route and the service behind it exist; the API endpoint does not.
5. **Lighthouse ≥ 95** on the public pages — MILESTONES schedules the measurement at M6, so this is
   not overdue, but the pages are built for it (tiny CSS, deferred script, no external requests).

**Next**

- Finish the five items above, in that order; 1 and 2 are what "M5 complete" hinges on.
- No open owner decisions. The four that were pending are decided above and recorded in ADR-0007,
  ADR-0009 and ADR-0010; the next session starts from settled ground.
- One hard date to respect: the Peppol rule-set upgrade to 2026.5 **before 2026-08-17** (ADR-0007
  Entscheidung 8), including a re-check of the 78 German translations against the new assertion texts.

## 2026-07-25 — M4 hostile-review fix wave: 17 findings closed

**What**

A hostile review of the M4 branch before merge, in the per-milestone pattern (audit → prioritised
findings → fix all, test-first). Findings recorded in `.superpowers/m4-hostile-review-findings.md`;
all 17 closed on the same branch. Test count 736 → 764, `./mvnw verify` green, every coverage gate
met.

**The three that mattered**

- **F1 — CI was red, and the diagnosis was wrong.** The security stage failed and both the worklog
  and the README blamed the missing `NVD_API_KEY`. The real cause: `dependency-check-maven` sat in
  the root POM's `<build><plugins>`, which Maven inherits into every child, so `-Psecurity verify`
  ran the `aggregate` goal in **all ten** reactor projects instead of once at the root. They share
  one CveDB; the first execution closes it and the rest fail per CVE with `connectionPool is null`.
  Measured before and after (`10` bindings → `1`), fixed with `<inherited>false</inherited>`, and
  the CI job now asserts the binding count itself — with the scan skipped, so the check costs
  seconds and needs no NVD data. The POM's own comment had claimed "produced once at the root
  rather than nine times" the whole time.
- **F2 — `POST /convert` answered 500 on a hostile document.** Both reverse mappers passed an
  upload-supplied currency code straight to `Currency.getInstance`, which throws a raw
  `IllegalArgumentException` for anything non-ISO — unmapped, so 500 plus a stack trace per
  request, and the JDK's message echoes the offending value *unbounded*. Every other bad value on
  that path is a 422. The same hazard was already handled one module over in
  `InvoiceJsonReader.toCurrency`, so this was a regression of a guard the codebase had.
- **F3 — the milestone's own acceptance criterion was unmet**, and closing it found a real bug.
  MILESTONES asks by name for "Golden-Files für Roundtrips (ebInterface→UBL→ebInterface,
  dokumentierte Abweichungen)"; what shipped were two *same-format* property suites. Writing
  `CrossFormatRoundTripTest` immediately exposed **F3a**: the exemption `Comment` grew by one
  category code on every conversion (`E |` → `E | E |` → `E | E | E |`), unboundedly, in a
  persisted field — invisible to every existing test because they compare canonical models and the
  corruption lived in the emitted XML.

**Decisions**

- **The VATEX code is recoverable, and pretending otherwise was the bug.** The reverse ebInterface
  mapper declined to parse the code back out of `Tax/TaxItem/Comment` "because parsing it back out
  of prose would be guesswork". It is not prose — the forward mapper writes
  `lead-in + category + " | " + code + " | " + text`, a delimited field list of this project's own
  design. Declining to read back what we ourselves wrote both discarded a recoverable value and
  caused F3a. It is now parsed structurally, with every field cross-checked against something known
  independently, so a genuinely foreign comment still falls through to text-with-a-loss-note.
  Consequence: ebInterface → UBL → ebInterface is now **byte-for-byte lossless** for every valid
  document in the corpus.
- **A skipped security scan beats a permanently red one.** Making the absent `NVD_API_KEY` a hard
  failure would have left the stage red until the owner acts — and a stage that is always red
  teaches everyone to ignore red, which is worse than the gap. The scan is skipped with a warning
  annotation *and* a job-summary block, becomes a real gate the moment the secret exists, and the
  job still does real work meanwhile (the F1 binding assertion).
- **`/convert` is rate-limited per credential, not per IP, and authenticated callers are not
  exempt** (F9). The endpoint admits no anonymous callers, so inheriting the validator's
  authenticated-bypass would have produced a limit covering nobody. Keying by IP would punish every
  tenant behind a shared egress and let one tenant multiply their allowance across addresses.
- **`@Transactional` removed from `ConversionService.convert`** (F8): it held a HikariCP connection
  across a full Peppol XSLT run to protect one audit INSERT that already has its own transaction.
- **`everyLibModuleIsListed()`** (F4). M4 added three modules and extended none of the cross-module
  ArchUnit rules, because doing so was a step someone had to remember. It is now a failing test —
  verified by unlisting `rendering` and watching it fail with an actionable message.
- **Two removals, no replacements.** `TargetFormat.id()` had zero call sites and a Javadoc claiming
  a role a different type filled; `ConversionReport.plus()`/`lossless()` were called only by their
  own unit test. DoD §1 forbids dead paths, and a convenience with no caller is a reader's false
  lead.

**Next**

- Merge to `main`. The `NVD_API_KEY` secret is the one outstanding owner action; adding it turns
  the security stage from skip-with-warning into a live gate with no workflow change.

## 2026-07-25 — M4: UBL BIS 3.0 + Konvertierung + PDF: complete

**What**

- Milestone M4 delivered on `feat/m4-ubl-convert-pdf`. The platform now speaks both Austrian
  e-invoice formats, converts between them through the canonical model with a per-document loss
  report, and renders a German PDF print view. Two new modules (`formats-api`, and `formats-ubl` /
  `rendering` filled in from stubs), one deleted class, ten commits.
- **The headline result is external, not self-asserted.** An invoice generated by this platform —
  canonical JSON → `InvoiceToUblMapper` → UBL strategy → `InvoiceValidator` — is judged **clean by
  the official OpenPeppol rule set**, for both the invoice and the credit-note rule sets, in CI on
  every run (`UblEndToEndGenerationTest`, `PeppolRoundTripTest`). For ebInterface the validator
  applies rules *this project wrote* (AUSTRIAPRO publishes none); for UBL it applies rules
  OpenPeppol publishes and this project only executes. That makes the UBL acceptance the strongest
  automated claim in the repository — the closest thing to the manual portal Abnahme that can run
  unattended.
- **`formats-api` (new module):** the shared adapter vocabulary — `ReadResult` and
  `InvoiceFormatStrategy`. `ReadResult` could not simply move to `core`, because
  `FormatsEbInterfaceArchitectureTest` forbids a `formats-*` module any dependency on `core` and
  that rule is not up for weakening; the answer is a dependency-free module, the same shape ph-ubl
  itself uses with `ph-ubl-api`. This closes ADR-0004 Entscheidung 10, which deferred a genuinely
  polymorphic seam to M4.
- **`formats-ubl`:** `UblDocumentKind`, `UblNamespaces`, `Ubl21InvoiceStrategy`,
  `Ubl21CreditNoteStrategy` over ph-ubl 10.2.0, plus `UblRootElement` — see Decisions for why the
  last one exists.
- **`mapping`:** `InvoiceToUblMapper` (canonical → Peppol BIS 3.0 UBL, 380 → `ubl:Invoice`,
  381 → `ubl:CreditNote` behind a sealed `UblDocument`), and both reverse mappers
  (`EbInterface61ToInvoiceMapper`, `UblToInvoiceMapper`). New `conversion` package: `ConversionNotes`
  (`CONV-01..04`), `ConversionReport`, `ConversionLosses`, `CanonicalResult`.
- **`core`:** `ElectronicAddress` (BT-34/BT-49 with its mandatory EAS scheme BT-34-1/BT-49-1) and an
  optional `Party.electronicAddress`. Both prior `Party` constructors still compile. The
  canonical-JSON boundary reads it; `samples/invoice-b2g-sample.json` carries it, which is what makes
  the sample Peppol-complete.
- **`validation`:** `DocumentFormat` (the dispatch seam), UBL namespace detection,
  `PeppolValidationStage` running the official OpenPeppol VES at a pinned version, and a new
  `InvoiceValidator` facade that dispatches by detected format. `EbInterface61Validator` deleted
  rather than kept alongside. Corpus gained three UBL files.
- **`rendering`:** `InvoicePdfRenderer` on Apache PDFBox 3.0.8 — German A4 print view with sender
  and recipient blocks, metadata, line items, the VAT breakdown as its own table (§ 11 UStG requires
  tax per rate), totals and payment details. `PdfCanvas` holds the layout mechanics; `PrintableText`
  the encoding safety.
- **`app`:** `POST /api/v1/convert?from&to` (authenticated, audited `CONVERSION_RUN`),
  `GET /invoices/{id}/ubl`, `GET /invoices/{id}/pdf`. `POST /validate` auto-detects UBL through the
  same generalized validator.
- **CI:** OWASP Dependency-Check as its own gated stage (ENGINEERING_STANDARDS §4/§6);
  `formats-ubl` joined the mutation job, which now runs five modules.
- **Docs:** ADR-0007 (UBL/Peppol + conversion, including the rule-set upgrade procedure), ADR-0008
  (PDF rendering), README (status, module map, API table, a new Conversion and PDF section, testing,
  credits), SPEC §2/§4/§7/§10 sync, glossary M4 section, `samples/README.md`, corpus README.

**Decisions**

- **The Peppol rule sets are executed, never reimplemented.** M2 had to write its own ebInterface
  Schematron because AUSTRIAPRO publishes none; Peppol publishes complete rule sets, so they are run
  unmodified. Consequence: the UBL pipeline has *one* stage where ebInterface has three — the VES
  already sequences XSD, EN 16931 and BIS internally, and splitting it would be exactly what
  "unmodified" means not doing. Findings carry the rule set's **own** assertion ids
  (`PEPPOL-EN16931-R010`, `UBL-CR-412`), not a flat project-local code, so a reader can look the rule
  up directly.
- **The rule-set version is pinned in code (2025.11), not taken from a library default.**
  `initStandard` registers four versions at phive-rules 4.4.1; picking "whatever is current" would
  let a dependency bump silently change which rules an invoice is judged by. 2026.5 is published and
  becomes mandatory 2026-08-17 — both read off the artefacts (`PeppolValidation2026_05.VALID_PER`),
  not off a website. A test asserts the pin still resolves. Upgrade procedure in ADR-0007.
- **Conversion goes through the canonical model, never syntax to syntax.** A direct transformation
  would be a second, independent understanding of both standards. Through `core` the invoice is
  understood once, by the model that already derives and re-verifies every amount, so a conversion
  cannot silently change a total.
- **A source total that disagrees with the derived one is reported, not adopted and not discarded.**
  `CONV-04` at ERROR severity; the derived value wins. `ConversionReport.isLossless()` and
  `isTrustworthy()` are deliberately different questions — dropping a field the target has no concept
  of is normal, a changed amount is not.
- **BT-34/BT-49 were added to the model rather than synthesised from the VAT id.** Synthesising was
  the tempting option (both often carry the same number) and would route a real document to a wrong
  or non-existent mailbox. An electronic address is a mailbox on a network; a VAT id is not one.
- **`UblRootElement` exists because of a hole a test found.** JAXB unmarshals by declared type and
  ph-ubl offers no way to demand a root element, so with schema validation off a `ubl:CreditNote`
  handed to the invoice marshaller is unmarshalled into an `InvoiceType` **without a single
  diagnostic** — the two share the same `cbc:`/`cac:` child vocabulary. Silently dropping
  `CreditedQuantity` and keeping the wrong document kind is exactly what a converter must not do, so
  the strategies check the root element themselves via a StAX peek. The ebInterface adapter needs no
  equivalent and does not get one.
- **PDFBox, not OpenPDF, and not HTML→PDF.** Licence decides: PDFBox is Apache-2.0, the same as this
  repository, so a reviewer reading the dependency list meets no licence question at all. OpenPDF's
  LGPL/MPL is legally fine and still worse than "no question". HTML→PDF would drag a rendering stack
  and a second description language in for one fixed page, and openhtmltopdf itself depends on PDFBox
  2.x. Full comparison in ADR-0008.
- **`EbInterface61Validator` deleted, not kept.** Two facades where one supersedes the other is a
  dead path. One deliberate contract change falls out: a document whose format could not be
  determined now reports profile `none` instead of `at-b2g` — claiming an Austrian B2G profile for a
  document nobody could identify was always slightly wrong, and with two profiles it would be plainly
  wrong.
- **`validation`'s mutation score is one point above its gate, and the gate stays.** 126/147 = 86 %
  against a gate of 85, down from 89 % at M2: the Peppol stage added 31 mutants, and its registry,
  holder and dispatch paths are largely unreachable from anything short of a full Peppol validation
  run. Lowering the bar would hide exactly the signal the number is giving — that this module's next
  change needs killing tests written with it. Recorded in the pom and handed to the hostile review.
- **The `frecord` finding (recorded, not fixed).** PIT's `frecord` feature filters mutants in
  compiler-generated record code, and it removes **every** mutant in `Party`'s and
  `ElectronicAddress`'s hand-written compact constructors — where nearly all of this domain model's
  invariants live. Measured: `core` yields 129 mutants with the filter on (98 % killed) and 392 with
  it off (86 % killed, i.e. below the 90 gate), with the extra survivors concentrated in generated
  `equals`/`hashCode`/`toString`, which is what the filter exists to remove. Pre-existing, not
  introduced by M4, and not changed here: flipping the feature would force either a lowered gate
  (never) or tests for compiler-generated methods (worthless). Handed to the M4 hostile review.

**Verification**

- Full `./mvnw verify` green across all 10 reactor modules. Measured this session (module, tests,
  JaCoCo line/branch, gate):
  - `core`: 220 tests; 99.5 % / 98 % (gate 95/90); PIT 126/129 = 98 % (gate 90).
  - `formats-api`: 7 tests; 100 % / 100 % (gate 100/100); no PIT profile, deliberately.
  - `formats-ebinterface`: 14 tests; 100 % / 100 % (gate 90/85, was 96.15/87.50); PIT 12/12 = 100 %.
  - `formats-ubl`: 31 tests; 98.57 % / 96.30 % (gate 90/85); PIT 27/29 = 93 %.
  - `mapping`: 187 tests; 98.95 % / 91.12 % (gate 95/90); PIT 390/396 = 98 %.
  - `validation`: 112 tests; 95.13 % / 91.94 % (gate 90/85); PIT 126/147 = 86 % (gate 85) — see
    below.
  - `rendering`: 36 tests; 95.60 % / 88.75 % (gate 90/85).
  - `app`: 48 unit + 81 integration tests (from 48 + 70); gates 90/78 unchanged.
- `./mvnw spotless:apply` clean before every commit.
- **Peppol acceptance, run in CI:** the sample invoice and its credit-note counterpart both come back
  with **zero findings** from the official OpenPeppol rule set. The negative case
  (`invoice-without-electronic-addresses`) is rejected with `PEPPOL-EN16931-R020` and `R010` — one
  defect, reported per party — proving the rules genuinely execute rather than passing vacuously.
- **ebInterface portal Abnahme re-confirmed by the owner on 2026-07-25** on the exact committed
  bytes: *"Diese Datei ist gültig gemäß ebInterface Standard ebInterface 6.1"*. M4 added the
  electronic addresses to the sample JSON, which does **not** change the ebInterface twin's bytes
  (ebInterface has no element for a network address), so that Abnahme still describes the file as
  committed. `samples/README.md` carries the dated history.
- **The PDF was rendered and looked at**, not only asserted on. That is how two things were found
  that no assertion would have caught: four helper methods were never called by anything (deleted
  rather than left as dead paths), and the description column sat a hair from the right-aligned
  quantity (gutter widened).
- **Compose smoke (full stack, live):** `docker compose up -d` → postgres + keycloak + mailpit + app
  healthy; `GET /actuator/health` UP. Against a real Keycloak token: `POST /invoices` on the sample
  JSON → 201; `GET /{id}/ubl` → the Peppol document; `GET /{id}/pdf` → `application/pdf`, `inline`
  disposition, opened and read (Abnahme: *"PDF sieht nach Rechnung aus"* — it does).
  **`POST /convert` both ways, which is the milestone's Abnahme in one command:**
  - `ebinterface → ubl` on the sample twin → 200, two `CONV-01` losses (the plain-text country name,
    one per party), and the Peppol validation of the result reports `PEPPOL-EN16931-R020` and
    `R010`: the missing electronic addresses, because the ebInterface source cannot carry them. The
    conversion report and the validation report tell one coherent story rather than two.
  - `ubl → ebinterface` on the UBL twin → 200, one `CONV-01` (the electronic address is dropped —
    ebInterface has no element for it), and the resulting ebInterface document is fully AT-B2G valid.
  Anonymous `POST /validate` auto-detects both formats (`ubl-invoice-2.1`/`peppol-bis-billing-3.0`
  and `ebinterface-6.1`/`at-b2g`), zero findings each, `id:null` (nothing persisted). `/v3/api-docs`
  lists the three new paths. `audit_event` carried `INVOICE_CREATED` ×1 and `CONVERSION_RUN` ×2.
- **Security scan: wired, NOT green — and deliberately reported as such.** dependency-check-maven
  12.2.2 resolves, runs and reaches the NVD, then fails with **HTTP 429**: the NVD rate-limits
  unauthenticated clients. That is a property of the NVD 2.0 API, not of the configuration, and it is
  the exact failure the pom comments and the CI pre-flight warning describe. No green security scan
  is being claimed.

**Not done, and why**

- **`NVD_API_KEY` is an owner action.** Request a free key at
  <https://nvd.nist.gov/developers/request-an-api-key> and add it as the repository secret. Until
  then the CI security stage warns actionably and the scan is unreliable.
- **Peppol finding messages are English.** The rule set ships English text only; translating several
  hundred rules would be a maintenance liability and a fresh source of error, so the German message
  is honestly a German frame around the official English wording. Translating the rules that actually
  affect Austrian filers is deliberate work for M5, alongside the AI explanation feature that exists
  for exactly this problem.
- **No ZUGFeRD/Factur-X hybrid.** No XML is embedded in the PDF. A hybrid is a different artefact
  with its own conformance rules (PDF/A-3 among them); claiming one without meeting them would be
  worse than not offering it.

**Next**

- Sebastian: merge decision on this branch; add the `NVD_API_KEY` repository secret.
- M4 hostile review (per the standing per-milestone pattern), which should start from: the PIT
  `frecord` finding above; whether `ConversionLosses` should be exhaustive rather than the four cases
  it covers; and the Peppol rule-set upgrade due 2026-08-17.
- M5 — Web-UI + öffentlicher Validator + KI-Erklärungen: Thymeleaf + htmx, the public validator page,
  report view, dashboard, `ai-assist` with the OpenRouter adapter and PII scrubbing, Selenium E2E,
  Gatling. The German-message gap above is the natural first customer of the AI explanation feature.
- Carried: GDPR tenant-delete endpoint + retention job (M5); OTel traces/metrics, Traefik forwarded
  headers, `/actuator/info` git-sha (M6); `Texts.safeEcho` promotion out of `core.internal` (open
  since M1).

## 2026-07-25 — M3 hostile-review fix wave: complete

**What**

- Hostile due-diligence review of M3 (`72b9014..3f03b3b`) from the standpoint of a Viennese
  enterprise buyer's engineer, then every finding fixed on `fix/m3-hostile-review`. Fifteen
  findings: four P1, eight P2, three P3. Regression test first wherever the finding was a defect
  rather than a missing test.
- **P1 — unbounded request body.** `POST /api/v1/invoices` declares its body as `byte[]`, which
  Spring buffers whole. Multipart was capped at 2 MB; nothing capped an ordinary body
  (`server.tomcat.max-http-form-post-size` is form-encoded only, verified against Boot 4.1's
  configuration metadata), so one authenticated caller could exhaust the heap and have the same
  unbounded string written into `invoice.canonical`. `RequestBodySizeLimitFilter` checks
  `Content-Length` and additionally counts bytes as they are read, so a chunked request cannot
  sidestep it, and runs ahead of Spring Security so an oversized body is refused *before*
  authentication.
- **P1 — no negative OAuth2 leg in the auth matrix.** The suite proved a real Keycloak token
  authenticates and never that any token is refused; the decoder could have regressed to a
  permissive one with everything still green. `JwtDecoderTest` publishes a throwaway JWKS over
  loopback and mints its own tokens, so wrong issuer / expired / unknown key / a foreign key
  impersonating the real `kid` can each be varied one at a time. `AuthMatrixIT` gained the
  end-to-end equivalents (non-JWT bearer, `alg=none`, a genuine token with a rewritten payload).
- **P1 — a 500 left no trace.** `handleUnexpected` discarded the exception and the module had no
  logger at all. Now logged with its stack trace, alongside the other security events (rejected
  key, rate limit, oversized body, two credentials, provisioned tenant), with no credential ever
  written in full. Structured ECS JSON logging under the `prod` profile.
- **P1 — no coverage gate on `app`.** The other four modules gate; the module holding all the
  security code did not. JaCoCo now measures unit *and* integration runs merged (Failsafe needs
  `@{argLine}` late binding — `${...}` silently drops the agent, which is what happened first
  time). Measured 95.24 % line / 83.33 % branch, gated 90/78. The two real gaps got tests worth
  having rather than a lowered bar: `EntityIdentityTest` and the problem-vocabulary branches.
- **P2** — API-key 404 now has its own slug and a domain exception instead of Spring Web's
  `ResponseStatusException` (ArchUnit rule added to prevent a repeat); cross-tenant isolation
  tested for api-keys; a request presenting both an API key and a bearer token refused with 400
  instead of silently running as whichever filter went last; `API_DOCS_ENABLED` off-switch for
  the anonymous OpenAPI/Swagger surface; `report(invoice_id)` index (V2) with per-index
  assertions replacing a bare index count; `SecurityHeadersIT`; optional `OAUTH2_AUDIENCE`
  validation closing ADR-0006's own recorded limit; a 25-key cap per tenant.
- **P3** — internal task-plan vocabulary ("T6", "T7", "task 10") removed from production Javadoc;
  `PROTECTED_VALIDATE` renamed to `PUBLIC_VALIDATE` (it names the milestone's one public route);
  stale "arrives in T6" comments deleted; README testing section rewritten (it still ended at
  M0's "smoke test on the health endpoint"); worklog ordering normalised to newest-first.

**Decisions**

- Audience validation is **opt-in**, not on by default: enabling it unconditionally would reject
  the dev realm's tokens, whose `aud` is Keycloak's default `account`. Documented as recommended
  for any shared realm.
- Two credentials in one request is a **400**, per RFC 6750 §3.1 — not "last filter wins", and not
  a silent preference for either mechanism.
- The API-key cap is a soft anti-runaway bound, explicitly *not* a security boundary: two truly
  simultaneous mints could both observe limit-1 under READ COMMITTED. Said so in the code rather
  than implying a guarantee the isolation level does not give.
- PIT is deliberately not extended to `app`: minutes per mutant against a module whose tests each
  boot a Spring context and two containers, for little signal.
- A bug this branch introduced was caught and fixed inside it: building the JWT validator list
  unconditionally broke every context with no issuer configured, because
  `JwtValidators.createDefaultWithValidators` rejects an empty list. The commit that introduced it
  had only been verified against Keycloak-backed ITs.

**Not fixed, and why**

- OWASP dependency-check in CI: ENGINEERING_STANDARDS §4/§6 require it, MILESTONES M6 schedules it.
  The two documents disagree; MILESTONES governs, and pulling M6 work into an M3 fix wave would be
  scope creep. Recorded here so the conflict is visible rather than rediscovered.
- OTel traces/metrics: M6. Structured logging was *not* deferred and landed above.
- The single-instance, `remoteAddr`-keyed rate limiter: honestly argued in ADR-0005/0006 and tied
  to the M6 Traefik work.

**Verification**

- `./mvnw verify` green across all modules; `app` at 48 unit + 70 integration tests (from 22 + 50
  before the wave), 95.24 % line / 83.33 % branch coverage.
- Each regression test verified to fail against the pre-fix behaviour before the fix landed (body
  cap re-run with the limit at 100 MB; audience validator removed; api-key slug asserted against
  the old generic type).

**Next**

- Sebastian: merge decision on this branch; portal-Abnahme re-confirmation for M3 still open.
- M4 — UBL BIS 3.0 + Konvertierung + PDF: `formats-ubl`, two-way mapping with lossy report,
  `POST /convert`, `rendering` (PDF print view), roundtrip golden files.

## 2026-07-24 — M3: REST API + persistence + security: complete

**What**

- Milestone M3 delivered across 20 commits on `feat/m3-rest-api` (`72b9014..e1118e6`), executed as a
  twelve-task subagent-driven plan (`.superpowers/sdd/m3/PLAN.md`, session-local) with a per-task
  spec+quality review gate and a final whole-branch review. `app` grew from a health-only skeleton
  into a secured, persistent REST API.
- **Carried model work landed before the contract froze.** `core` gained delivery date /
  service period (BT-72 / BG-14, mutually exclusive, both optional) and an optional `Party` email
  (BT-43/BT-58 — corrected from an initial BG-14 mis-citation caught in review); `mapping` flows all
  three through to ebInterface `Delivery` and `Address/Email`; the `samples` twin and the byte-identical
  validation corpus `b2g-full.xml` were regenerated through the real chain, invalid corpus files
  re-synced as one-defect diffs.
- **Three AT-B2G federal-MUST Schematron rules** (`AT-B2G-03` biller e-mail, `AT-B2G-04`
  Lieferantennummer, `AT-B2G-05` payment-method completeness) close the gaps ADR-0004 Entscheidung 9
  had named as documented-but-unimplemented; German-first messages, `RuleIds` registry entries,
  three new invalid corpus cases.
- **Persistence:** Flyway `V1` baseline (tenant, invoice, report, api_key, audit_event), JPA entities
  with application-assigned UUIDs and `@JdbcTypeCode(SqlTypes.JSON)` JSONB (no third-party
  hibernate-types), `ddl-auto: validate`, tenant-scoped repositories; Testcontainers Postgres IT base.
  The app module moved to a Failsafe `*IT` split so `./mvnw test -pl app` is Docker-free and ITs run
  at `verify`.
- **Security:** OAuth2 resource server (Keycloak) or `X-Api-Key`; API keys are `eiv_`-prefixed,
  SHA-256-at-rest, plaintext-once, revocable, and OAuth2-only to mint/revoke (enforced in the security
  layer via `ROLE_USER` vs `ROLE_API_KEY`, not controller logic); tenant auto-provisioning keyed on the
  JWT `sub` with unique-constraint race handling; the anonymous-authentication trap is avoided by an
  explicit `instanceof` allow-list everywhere it matters. Compose gained Keycloak (dev-realm import,
  pinned tag+digest) and Mailpit.
- **APIs:** `POST /api/v1/invoices` (create → persist canonical JSONB + generate & validate ebInterface
  + audit, report attached but non-gating), `GET` list/detail/`{id}/ebinterface`; `POST /api/v1/validate`
  (public multipart, 2 MB cap, anonymous persists **zero** rows — GDPR — authenticated persists report +
  audit); `GET /api/v1/reports` list/detail; `/api/v1/api-keys` CRUD. RFC 9457 problem+json throughout,
  cross-tenant reads collapse to an identical 404 (no existence oracle), pagination clamped.
- **Rate limiting:** per-IP bucket4j token bucket on anonymous `/validate` only, 429 + problem+json +
  `Retry-After`; single-instance in-memory, honestly documented.
- **OpenAPI** via springdoc (Swagger UI reachable anonymously — the Abnahme item), **first cross-module
  ArchUnit rules** (libs never depend on `app`; DB tech confined to `..app.persistence..` with a
  type-based, precisely-whitelisted rule set; forced the extraction of `ApiKeyService` so controllers
  never touch repositories), and a full documentation wave (new ADR-0006 auth/security with an honest
  known-limits section, ADR-0005 touch-ups, README REST-API section with fact-checked curl examples,
  SPEC/glossary sync).

**Decisions**

- **Failsafe `*IT` split, not Surefire-widened includes.** The T4 persistence work first ran ITs under
  Surefire; on review this was migrated to maven-failsafe (`*IT` at `verify`), keeping a Docker-free
  `mvn test` unit lane — the idiomatic enterprise convention and a conscious call, since `app` is the
  first module with integration tests.
- **ADR numbering:** persistence baseline is ADR-0005 (created T4), auth/API security is a new ADR-0006
  (a pre-existing SPEC §10 forward-reference to "ADR-0006" for Peppol was retargeted to a future ADR).
- **Canonical JSON is JSONB-normalized, not byte-verbatim.** The `invoice.canonical` column normalizes
  whitespace/key order; byte-exactness for audit is preserved by the SHA-256 of the raw request body,
  not the stored document. Javadoc and ADR-0005 reconciled to say so.
- **`dasniko/testcontainers-keycloak` rejected** (drags a beta shrinkwrap + keycloak-admin-client + a
  second Jackson onto the test classpath); a ~40-line hand-rolled `GenericContainer` on the same pinned
  Keycloak image does the job with zero new deps.
- **springdoc 3.0.3 on Boot 4.1:** no springdoc release yet targets Boot 4.1 specifically; 3.0.3's parent
  pins Framework 7.0.6 vs this repo's 7.0.8 (patch-only delta), verified empirically by a live
  `OpenApiIT` against the real Boot 4.1 context. Re-check Central for a Boot-4.1-targeted release later.
- **Deliberately deferred (documented, not dropped):** GDPR tenant-delete endpoint + retention job → M5;
  OWASP dependency-check CI, `/actuator/info` git-sha, Traefik forwarded-headers (rate-limit keying) → M6;
  JWT audience/`azp` validation is a framework-default gap recorded in ADR-0006 known-limits.

**Verification**

- Full `./mvnw verify` green across all 9 reactor modules (57.7 s). Test counts this session:
  `core` 209, `formats-ebinterface` 17, `mapping` 83, `validation` 95, `app` 22 unit (Docker-free
  Surefire lane) + 50 integration (Failsafe, Testcontainers Postgres + Keycloak); `formats-ubl` /
  `rendering` / `ai-assist` still `package-info.java` only. `./mvnw spotless:apply` clean.
- **Compose smoke (full stack, fresh DB):** `docker compose up -d --build` → postgres + keycloak +
  mailpit + app all healthy; `GET /actuator/health` UP; Flyway `V1` applied (`flyway_schema_history`
  success); `/v3/api-docs` 200 exposing all 8 resource paths and `/swagger-ui/index.html` 200
  (Abnahme: OpenAPI-UI nutzbar); anonymous `POST /validate` on the valid sample → `id:null`,
  `valid:true`, 0 findings, zero rows persisted; anonymous `GET /invoices` → 401; real Keycloak
  password-grant token → authenticated `POST /invoices` → 201 with a valid report, `GET
  /{id}/ebinterface` regenerated the XML, `audit_event` carried an `INVOICE_CREATED` row (Abnahme:
  Auth-Matrix + Audit-Einträge nachweisbar). The rate-limiter path-encoding fix confirmed live:
  14× anonymous `POST /api/v1/%76alidate` returned exactly ten 200s then four 429s (capacity 10) —
  the percent-encoded variant is now charged to the same bucket, closing the bypass.
- Final whole-branch review verdict "with fixes": the one Important finding — the rate limiter was
  bypassable via a percent-encoded path (`/api/v1/%76alidate`) because `shouldNotFilter` compared the
  raw `getRequestURI()` while authorization/routing match the decoded path — was closed by matching
  through the same `PathPatternRequestMatcher` the security layer uses, with a `RateLimitIT` regression
  case that forces the encoded request to consume the bucket's last token (proved on the wire with a
  raw-socket capture). The matrix-param variant is rejected by Spring's `StrictHttpFirewall` (400) ahead
  of the filter and is guarded as such. Two "verbatim" Javadocs reconciled to ADR-0005 in the same commit.
- Golden-file integrity re-verified by SHA-256: the samples twin, validation corpus `b2g-full.xml`, and
  the app test fixture are byte-identical.

**Next**

- Owner: re-run the official portal Abnahme on the M3 sample twin (delivery date + biller e-mail were
  added, so the bytes changed again since the last portal check — still schema-valid, more conformant).
- M4 — UBL BIS 3.0 + conversion + PDF (`formats-ubl`, `rendering`): bidirectional mapping with a lossy
  report, `POST /convert`, PDF print view, roundtrip golden files; the `jaxb-runtime` opt-in pattern and
  the version-strategy seam are ready for it.
- Carried code-quality minors accepted at final review (see `.superpowers/sdd/progress.md` M3 section):
  `InvoiceSummary.valid` getOrDefault(false) conflates unknown validity; `findByInvoiceIdIn` not
  tenant-scoped (defense-in-depth); `DuplicateInvoiceException` embeds a raw invoice number in its
  message (never surfaced today — static 409 detail, no app logger — latent if a logger arrives);
  a mocked-repository unit test for `ApiKeyService`.

## 2026-07-24 — M2 hostile-review fix wave: complete

**What**

- A six-agent hostile due-diligence review of the M2 merge (`790d024..230fe72`, PR #3) — dimensions
  architecture/domain, security, tests, naming/style, docs, and a jqwik keep-or-replace evaluation —
  produced 4 P1, 8 P2 and roughly 20 P3 findings (`.superpowers/sdd/m2-hostile/FINDINGS.md`,
  session-local, not a committed repo artifact). This entry closes the fix wave: every finding maps
  to a fix commit or a documented, dated decision; none were silently dropped.
- `mapping`: the Austrian no-UID convention (`ATU00000000`) for a party without a VAT id, sourced and
  cited from e-rechnung.gv.at (closes the schema-invalid-output gap a null `vatId` used to produce
  silently); the AE reverse-charge exemption comment now leads with "Übergang der Steuerschuld: "
  instead of the legally misleading "Steuerbefreiung: " (category E keeps the exemption wording); the
  dead, unreachable `C62` unit-code fallback deleted; German ISO→display-name `Country` element text
  (`Österreich`, keeping the ISO code on `@CountryCode`); a `CREDIT_NOTE` without payment means now
  emits `PaymentMethod/NoPayment` instead of omitting the block, per the e-rechnung.gv.at Gutschrift
  recommendation; new jqwik value-preservation properties (breakdown entries, lines, parties mapped
  verbatim, not just schema-valid) with widened generators (scale-4 amounts, a null-vatId arm,
  AE-with-custom-reason).
- `validation`: every foreign-text→`Finding` seam (XSD parser detail, Schematron SVRL fallback text)
  now bounded through a new `BoundedText` helper before construction, closing a reachable crash where
  an over-long document value overflowed `Finding`'s own length invariants and threw out of
  `validate()`; a new defensive 20 MB input-size cap (`XML-02`) rejects an oversized upload before a
  byte is parsed; the Schematron `SchematronResourcePure` bind is now eager (forced during static
  holder initialisation under the JVM class-init lock), closing an unsynchronized-lazy-init data race
  bytecode analysis confirmed, with a permanent concurrency regression test; dead
  `ValidationContext` accessors (`detectedVersion()`, `xml()`) removed along with their false "every
  later stage needs this" Javadoc; a new `RuleIds` registry class centralises all seven rule-id
  constants (replacing five scattered producer-side constant sets) and the non-conforming XSD-stage
  id is renamed to fit the `PREFIX-NN` scheme every other id already used; ArchUnit gained a positive
  dependency whitelist (matching `mapping`'s shape) and a non-vacuous-import guard on every module's
  architecture test; three new golden-file corpus entries (a credit-memo/reverse-charge document, an
  exempt invoice, a whitespace-only-OrderID AT-B2G-01 case) plus `SecureXmlTest` fixtures for
  billion-laughs entity expansion, a bare `DOCTYPE`, and XInclude local-file-disclosure (the last one
  pins real, load-bearing hardening a prior review pass had incorrectly written off as redundant).
- `validation` and `formats-ebinterface` gained PIT mutation gates and both are now wired into CI
  alongside `core` and `mapping` — all four implemented modules run mutation testing on every CI run,
  closing the inverted risk profile where the two simplest modules had gates and the two most
  security-critical ones did not; targeted killing tests were added for the cheap survivors found in
  security-relevant code (`SecureXml`, `EbInterface61Validator`, `ValidationRunner`), and the
  remaining survivors are documented per-line as genuinely equivalent or defensive, not
  shape-asserting gaps.
- Docs (this entry's own task): README rule-id table and prose renamed to `XSD-01` / `RuleIds`, gained
  the `XML-02` row, and the module map marks the four not-yet-built rows `— planned Mn`;
  `ENGINEERING_STANDARDS.md` §2 releases wording reconciled to M6; ADR-0003 gained the two
  §11-UStG/AT-B2G model gaps (delivery date, Biller e-mail) on the deliberately-absent list, each with
  a landing milestone; ADR-0004 gained the size-cap and bounding-policy decisions, an AT-B2G
  profile-completeness section naming the undocumented federal-MUST gaps, and a corrected (softened)
  version-strategy-seam claim; SPEC §7 now names the secure-parse stage and correctly files
  `AT-B2G-01` as Schematron, not a business rule; glossary gained Bundesdienststellen, Empfängerkonto,
  Übergang der Steuerschuld; `samples/README.md` and the corpus README gained an IBAN/BIC
  canonical-test-value provenance note and the portal-Abnahme re-confirmation note below; four new
  `package-info.java` files (`mapping.json`, `validation.stage`, `validation.cli`,
  `validation.internal`) state each subpackage's boundary contract, matching the existing
  module-root convention.

**Decisions**

- **A1/no-UID convention: confirmed, not fail-fast.** e-rechnung.gv.at's own "Rechnungsinhalte" page
  states the `ATU00000000` convention verbatim (quoted and cited in the mapper's Javadoc); the mapper
  applies it rather than throwing, keeping every canonical invoice state mappable.
- **C3: the `C62` unit-code fallback was dead code, deleted rather than made real.** Core's
  `InvoiceLine` already forbids a blank/null unit code, so the fallback branch was unreachable; the
  lying test name (`defaultsMissingUnitCodeToC62`) is renamed to what it actually asserted
  (`preservesSuppliedUnitCode`).
- **Rule-id scheme: `PREFIX-NN` everywhere, `RuleIds` is the single registry.** M3 has not shipped
  yet, so this was the last cheap moment to rename the one id that didn't fit the scheme; every
  reference (stages, tests, corpus, both READMEs, both touched ADRs) was swept — see the
  grep-completeness check in this task's own report.
- **P2-11 hollow strategy claim: softened, not built out.** `EbInterfaceVersionStrategy`'s Javadoc and
  ADR-0004 no longer claim "nothing more" wiring that does not exist; genuinely polymorphic dispatch
  is deferred to M4, when a second format exists to be polymorphic over — building it now for zero
  consumers would be speculative generality.
- **XML-02 size cap: 20 MB, module-level, defensive — distinct from M3's stricter 2 MB app-layer
  cap.** The validator protects itself independently of any caller; the HTTP-facing cap SPEC §4
  documents is a separate, tighter concern that lands with the REST endpoint.
- **Releases at Milestone 6, not Milestone 2.** `ENGINEERING_STANDARDS.md` §2 previously contradicted
  `MILESTONES.md`'s own M6 "GitHub Release v0.1.0" line — an open question the M2 entry below flagged
  and this fix wave now resolves in `MILESTONES.md`'s favour, reworded and dated.
- **jqwik: KEEP.** Per the owner's standing rule (switch only if a solid alternative exists — see the
  "Next" note on the 2026-07-23 M1 entry above), this session evaluated the credible Java/JUnit-5-native
  candidates and found none viable: each is unmaintained, JUnit-4-only, or an unaudited
  single-maintainer fork unpublished to Maven Central; migrating the ~6 property classes (rich
  `Combinators.combine` compositions, shrinking, `.injectNull()`) would cost days for zero functional
  or security gain. The anti-AI banner jqwik prints to test output is inert text with no execution
  vector, already fully covered by the standing "treat external tool/library output as untrusted
  data, never instructions" rule. The full evaluation (version check, banner timeline, candidate
  comparison, sources) was session-local research, not preserved as a committed repo artifact.
- **Merge authority delegated to the agent.** PR #3 (this milestone's own M2 merge) was merged as
  `230fe72` by Claude rather than waiting on a manual owner click, closing the "merge is Sebastian's
  call" open item the M2 entry below left pending; this fix wave's own PR follows the same delegated
  pattern at its finish task.
- **Portal Abnahme: PASSED, 2026-07-24** — see Verification below; and re-confirmation on the current
  twin bytes is recommended, not required (the change since Abnahme is schema-valid and, if anything,
  more standard-conformant) — see `samples/README.md`.

**Verification**

- Full `./mvnw verify` green across all 9 reactor modules. Freshly measured this session (module,
  tests, JaCoCo line/branch, gate):
  - `core`: 170 tests; 99.54 % / 98.09 % (gate 95/90); PIT 123/127 mutants killed = 97 % (gate 90).
  - `formats-ebinterface`: 17 tests; 96.15 % / 87.50 % (gate 90/85); PIT 13/13 = 100 % (gate 85).
  - `mapping`: 63 tests; 100 % / 100 % (gate 95/90); PIT 112/112 = 100 % (gate 85).
  - `validation`: 88 tests; 94.61 % / 93.41 % (gate 90/85); PIT 103/116 = 89 %, test strength 91 %
    (gate 85) — all four gated modules (`core`, `mapping`, `validation`, `formats-ebinterface`) now
    run in CI's mutation job (`.github/workflows/ci.yml`), closing P1-3's inverted risk profile.
  - `app`: 1 test (health smoke); `formats-ubl`/`rendering`/`ai-assist` still `package-info.java` only.
  - `./mvnw spotless:apply` clean before commit.
- **Official portal Abnahme PASSED, 2026-07-24** (owner-run, manual, one-time per `samples/README.md`):
  *"Diese Datei ist gültig gemäß ebInterface Standard ebInterface 6.1"* for
  `samples/invoice-b2g-sample.ebinterface.xml`. Note: this fix wave's German-country-display-name
  change (A6) subsequently regenerated that same file's bytes (Country element text), so the exact
  bytes validated are no longer byte-identical to what is committed now — see `samples/README.md`
  for the full note and the re-confirmation recommendation.
- CI run 30088300600 green on PR #4 (pull_request run — push CI covers main only): build/lint/test,
  the now four-module mutation job (core, mapping, validation, formats-ebinterface), and Docker image
  build; 2 m 18 s for the mutation job.

**Next**

- M3 — REST API + persistence + security (`app`): `POST /invoices`, `POST /validate`, OpenAPI,
  problem+json, Postgres + Flyway, Keycloak in compose, OAuth2 Resource Server + API keys, rate
  limiting on `/validate`, Testcontainers integration tests, first cross-module ArchUnit rules
  (SPEC §2, unchanged from the M2 entry below).
- M3 backlog carried from this fix wave: add `deliveryDate`/`servicePeriod` (BT-72/BG-14) and a
  Biller e-mail field to the canonical model before the JSON/REST contract freezes (ADR-0003
  Entscheidung 7, findings A5/A2); implement or explicitly re-defer the two AT-B2G federal MUSTs
  ADR-0004 Entscheidung 9 now names (Lieferantennummer presence, payment-method completeness);
  promote `Texts.safeEcho` out of `core.internal` once `app` starts consuming it directly (carried
  from the M1 hostile-review fix-wave entry below, still open).
- Owner: consider re-running the portal Abnahme on the current `samples/invoice-b2g-sample.ebinterface.xml`
  bytes (see Verification above) — cheap, not blocking.

## 2026-07-24 — M2: ebInterface 6.1 generation + validation

**What**

- `core`: closed the five M1-deferred hygiene minors (bounded breakdown-mismatch message echo,
  `VatExemptionReason` trim-before-cap, AE-default wording, ADR-0003 negative-380-import note,
  message-capitalization consistency). New `com.stoicera.einvoice.core.validation` package:
  `Severity` (ERROR/WARN/INFO), `Finding` (record — severity, ruleId, location, messageDe,
  messageEn, aiExplanation; non-blank/length-capped invariants), `ValidationReport` (sourceFormat,
  profile, findings, `isValid()`) — the shared contract `validation` and, later, `app` build on.
- `formats-ebinterface`: `EbInterfaceVersionStrategy<T>` (`namespaceUri()`, `read(byte[])`,
  `read(org.w3c.dom.Node)`, `write(T)`) wraps ph-ebinterface behind a version-strategy seam so
  ebInterface 7.0 can be added later without touching `core`; `EbInterface61Strategy` is the 6.1
  implementation, `ReadResult<T>` the lenient read outcome, `EbInterfaceNamespaces` the
  namespace-to-version lookup.
- `mapping`: `InvoiceToEbInterface61Mapper.map(Invoice) → Ebi61InvoiceType` — hand-written,
  stateless, **no arithmetic** (copies `core`-derived amounts verbatim; ADR-0003
  derive-don't-trust). `InvoiceJsonReader.read(InputStream) → Invoice` — a strict, Jackson-based
  boundary reader for the canonical-invoice JSON shape (`samples/README.md`,
  `samples/invoice-b2g-sample.json`): unknown properties rejected, money/quantity fields must be
  JSON strings (a numeric node is rejected, never silently stringified).
- `validation` module (new): `SecureXml` (XXE-hardened, namespace-aware DOM parse) →
  `FormatDetectionStage` → `XsdValidationStage` (phive VES `VID_EBI_61`, XSD-only) →
  `SchematronStage` (project-own `at-b2g-ebinterface-6.1.sch`) → `BusinessRuleStage` (IBAN mod-97),
  orchestrated by the `EbInterface61Validator` facade with hard gating — semantic stages run only
  on an XSD-valid tree. `ValidationContext.ebiInvoice()` unmarshals the JAXB tree from the already
  hardened DOM via the new `read(Node)` overload, so the untrusted upload is parsed exactly once.
- Golden-file corpus (`validation/src/test/resources/corpus/`, `valid/` + `invalid/`, own README)
  and `CorpusTest`; `EndToEndGenerationTest` runs the full chain JSON → `InvoiceJsonReader` →
  `InvoiceToEbInterface61Mapper` → `EbInterface61Strategy.write` → `EbInterface61Validator` and
  pins the output byte-for-byte against the committed twin `samples/invoice-b2g-sample.ebinterface.xml`
  — the milestone's Abnahme in code.
- `ValidationRunner` CLI: runs the pipeline over files/directories, German-first console report
  (English mirror per finding), exit codes `0`/`1`/`2` (`2` also covers a resolved file list that
  came back empty, fixed in a follow-up commit after review).
- Docs (this entry's own task): ADR-0004 extended with the own-Schematron rationale, the
  Schematron-vs-Java rule-mechanism split, and a rule-id scheme table; new README "Validation
  pipeline" section; glossary gained Schematron/SVRL/Storno/Prüfsumme, Lieferantennummer's entry
  now names its ebInterface mapping target; SPEC §7 gained the own-Schematron honesty sentence;
  two stale comments fixed (`core/pom.xml` PIT-threshold comment, `ReadResult` Javadoc).

**Decisions**

- **Own Schematron, not AUSTRIAPRO's.** ebInterface ships an XSD only — no official Schematron
  exists to consume — so `AT-B2G-01` (Auftragsreferenz) is an original `.sch` file with its
  provenance recorded in the header, distinct from the vendored ebInterface XSD and from the
  official Peppol Schematron/Genericode sets M4 will consume unmodified via phive-rules.
- **XSD stage validates twice, once per locale, for genuinely bilingual XSD-finding text.** Xerces
  bakes its diagnostic into the `SAXParseException` at validation time using whatever `Locale` that
  run was asked for; a second `getErrorText(Locale.ENGLISH)` call on the same `IError` returns the
  same (German) text. Running the XSD executor twice on the identical DOM and pairing errors by
  position is the only way to get a genuine English message without hand-translating Xerces output
  (ADR-0004).
- **Mapper XSD-resolved choices**, each pinned against the bundled ebInterface 6.1 XSD:
  `@Language = "de"` (the 2-letter ISO 639-1 code the XSD's `LanguageType` requires); exemption
  reason (VAT categories AE/E) → `Tax/TaxItem[j]/Comment` (no root-level `Comment` needed);
  `paymentTerms` → `PaymentConditions/Comment`; missing unit code defaults to `"C62"`.
- **JSON reader: shape problems throw `InvoiceJsonException`, domain-invalid content propagates
  `core`'s own `InvariantViolationException` untouched.** Where a JSON field maps onto an
  already-null-checked domain constructor argument, a missing value is deliberately let through as
  `null` so `core` produces the one clear message, rather than the reader duplicating invariant
  logic; only reader-owned translations (enum/date/currency/decimal parsing) throw directly.
- **CLI: an empty resolved file list is a hard exit-2 error, not silent success.** A directory
  argument with no matching `*.xml` files (e.g. a mistyped corpus path) used to return `0` and
  print nothing — a CI gate reading only the exit code would see "all valid" for a run that
  validated nothing. Fixed post-review; both the empty-directory and only-non-XML-files cases are
  covered.
- **PIT is kept as a local gate on `mapping`** (threshold 85, measured 100 %, ~12 s wall) alongside
  `core`'s — cheap enough that dropping it to save CI time isn't justified. **Wiring `mapping`'s PIT
  profile into the CI `mutation` job (which today only runs `-pl core`) is explicitly handed to the
  finish task (M2 Task 12)**, not silently left undocumented.
- **`jaxb-runtime` is declared once, in the parent POM's `dependencyManagement`.** The ph-* stack
  depends on the Jakarta XML Binding *API* only; a runtime provider must be supplied by the
  consuming module, so `jaxb-runtime` is parent-managed (runtime scope) and `formats-ebinterface`
  opts in. `formats-ubl` will need the same opt-in when M4 adds it — noted so that task doesn't
  rediscover the gap.

**Verification**

- Local `./mvnw verify` green across all 9 reactor modules (parent + core, formats-ebinterface,
  formats-ubl, mapping, validation, rendering, ai-assist, app). Measured this session:
  - `core`: 184 tests; JaCoCo 99.54 % line / 98.09 % branch (gate 95/90); PIT 123/127 mutants
    killed = 97 % (gate 90, ~20 s).
  - `formats-ebinterface`: 15 tests; JaCoCo 96.15 % / 87.50 % (gate 90/85).
  - `mapping`: 60 tests; JaCoCo 100 % / 100 % (gate 95/90); PIT 105/105 = 100 % (gate 85, ~12 s).
  - `validation`: 66 tests; JaCoCo coverage (gate 90/85) — **correction (2026-07-24 M2 hostile-review
    fix wave, finding E7):** this entry originally stated "94.67 % / 92.59 %", which drifted from
    PR #3's own body ("93.94 % / 92.59 %"); neither figure was reconciled against the exact historical
    commit, so it is corrected here to the gate reference rather than restated as a guessed precise
    number. See the fix-wave entry above this one for the current, freshly measured figure.
  - `app`: 1 test (health smoke), `formats-ubl`/`rendering`/`ai-assist` still `package-info.java`
    only.
  - CI run 30072775385 green on PR #3 (pull_request run — push CI covers main only): build/
    lint/test, mutation job (core 97 %, mapping 100 % — first run with mapping wired in),
    Docker image build; 1 m 38 s total.

**Next**

- Sebastian: one-time manual official portal check of `samples/invoice-b2g-sample.ebinterface.xml`
  (formvalidation.brz.gv.at / the WKO ebInterface validator) — the milestone's Abnahme step that
  cannot be automated.
- Sebastian: reconcile the GitHub-Release timing — `ENGINEERING_STANDARDS.md` §2 says "GitHub
  Releases mit Changelog ab Milestone 2", but `MILESTONES.md`'s M6 Abnahme line lists "GitHub
  Release v0.1.0". Flagging before M2's PR rather than guessing which document is authoritative.
- M2 Task 12: `./mvnw verify`, push `feat/m2-ebinterface`, open a PR, watch CI green including the
  mutation job; merge is Sebastian's call (M1 precedent). **Correction (2026-07-24 M2 hostile-review
  fix wave, finding E6):** this line originally guessed "PR #2" ahead of pushing; the PR GitHub
  actually assigned was **#3** (merged as `230fe72`) — corrected here rather than left as a stale
  pre-execution guess inside a completed entry.
- M3 — REST API + persistence + security (`app`): `POST /invoices`, `POST /validate`, OpenAPI,
  problem+json, Postgres + Flyway, Keycloak in compose (realm import), OAuth2 Resource Server +
  API keys, rate limiting on `/validate`, Testcontainers (Postgres + Keycloak) integration tests;
  first cross-module ArchUnit rules land here too (SPEC §2).
- Carried from final review: promote `Texts.safeEcho` out of `core.internal` once `app` starts
  rendering exception messages — cross-module use of an internal package is a spreading pattern
  to resolve then.
- Carried from final review: `SecureXml`'s quiet-handler Javadoc says "restores" abort behavior;
  it actually fails closed (stricter than JAXP default on recoverable errors) — one-word precision
  fix at the next touch.

## 2026-07-24 — M1 hostile-review fix wave: complete

**What**

- Closed the P1/P2 findings (plus the cheap P3s) of the M1 hostile due-diligence review in
  16 commits on `feat/m1-canonical-model` (PR #1).
- Domain: `VatExemptionReason` (BT-120/BT-121) — required for categories AE/E, forbidden for
  S/Z (BR-AE-10/BR-E-10/BR-S-10/BR-Z-10), AE defaults to VATEX-EU-AE "Reverse charge", E must
  be explicit; `InvoiceTypeCode` (BT-3, 380/381) with credit direction carried by the type
  code, never by sign — payable amount is now a non-negative hard invariant; BigDecimal
  magnitude bounds (Money 15 integer digits, line quantity/price 7/8) rejecting OOM-class
  inputs before `setScale`; exception hygiene (no IBAN/BIC echo ever, `Texts.safeEcho` for
  all raw-input echoes, scale numbers instead of `toPlainString`, null/zero message split,
  `NumberFormatException` wrapped); defensive length caps on all free-text fields;
  countryCode/BIC trim+uppercase normalization behind pre-normalization length guards.
- Tests: replaced the tautological jqwik properties with an independent plain-BigDecimal
  oracle — falsifiability proven by oracle mutation; multi-currency generation (EUR/USD/CHF/
  GBP/SEK) with a currency-propagation property verified against a deliberate EUR-hardcode;
  ten hand-computed Austrian VAT pins (incl. HALF_UP-vs-HALF_EVEN discriminators);
  deterministic boundary pins (scale-4, VAT 2.5/100 %, checksum-valid over-length IBAN,
  HALF_UP midpoints both signs). `*Properties` classes renamed `*PropertyTest`, so the
  hand-maintained Surefire include-list from the M1 entry above is deleted — superseded by
  the default `**/*Test.java` includes.
- PIT mutation testing gates `core` in CI: measured 95 % kill rate, threshold 90, ~20 s
  (pitest-maven 1.25.8 / pitest-junit5-plugin 1.2.3, verified via maven-metadata.xml — the
  solrsearch API under-reports latest versions).
- Supply chain: all GitHub Actions pinned to verified commit SHAs (tag comments kept), base
  images pinned tag+digest, Dependabot (maven/actions/docker, weekly), Surefire reports
  uploaded on CI failure so jqwik seeds are recoverable. Compose: `POSTGRES_PASSWORD`
  required (no default), Postgres bound to 127.0.0.1, README quickstart gained the
  `.env` copy step.
- Docs: ArchUnit claims scoped to reality (core rule since M1, cross-module rules M2/M3) in
  README/ADR-0002/SPEC; SPEC §3 reconciled with the real field set (no `id`, `vatBreakdown`
  not `taxSummary`, BT-3); ADR-0003 extended (exemption-reason decision, sign convention,
  completed deliberately-absent list: K/G/O/L/M, BR-CO-26, Address vs BG-5); glossary
  gained kaufmännisches Runden and Kleinunternehmer, Kennzahl kept (used as "KZ" in SPEC §7).

**Decisions**

- Coverage claims are measured-honest now: core at 99.46 % line / 97.81 % branch (gate
  95/90) — the two uncovered branches are documented guard paths; mutation testing gives
  the number teeth.
- Final whole-branch review verdict "with fixes": the fixes (BIC echo gap in PaymentMeans,
  breakdown-guard tests, Javadoc sync, honest coverage sentence) landed and were re-reviewed.
- Deferred to an M2 hygiene note (final-review minors): breakdown-mismatch message echoes
  the full supplied list (bounded but linear), VatExemptionReason trims before its length
  cap, "standard-mandated" wording on the AE default, ADR Konsequenzen note on importing
  standard-legal negative 380 invoices, message-style capitalization drift.

**Verification**

- `./mvnw verify` green across all modules (core: 147 tests incl. property + architecture;
  JaCoCo 99.46/97.81, gate 95/90; PIT 95 %, gate 90). CI on PR #1 is a pull_request-triggered
  run (push CI covers main only) — checked green after this push, including the new
  mutation job.

**Next**

- Sebastian: merge decision on PR #1 (M1 + fix wave); jqwik keep-or-replace decision still
  open; enable Dependabot in the GitHub repo settings after merge.
- M2 — ebInterface 6.1 erzeugen + validieren (unchanged from the entry above).
## 2026-07-23 — M1 Kanonisches Rechnungsmodell: complete

**What**

- `core`: Money (scale-2, HALF_UP, single rounding step), Austrian VAT rates (20/13/10/0,
  reverse charge, exempt — EN 16931 categories S/Z/AE/E), Party/Address, Iban (mod-97) +
  PaymentMeans, InvoiceLine, Invoice aggregate with builder-derived + constructor-verified
  VAT breakdown and totals.
- jqwik property suite: money algebra, invoice arithmetic partition/consistency properties,
  IBAN checksum properties. ArchUnit: core = JDK-only. JaCoCo gate 95 % line / 90 % branch.
- ADR-0003 (derive-don't-trust canonical model). Versions verified on Maven Central:
  jqwik 1.10.1, ArchUnit 1.4.2, JaCoCo 0.8.15.

**Decisions**

- Tax on category sums, not per line (BR-CO-17); pinned by property test.
- Allowances/charges, prepaid, BT-114 rounding amount deferred until mapping needs them
  (documented in ADR-0003).
- ArchUnit used via plain `archunit` artifact inside Jupiter tests (no engine coupling to
  JUnit Platform 6); cross-module rules follow in M3 when `app` gains module dependencies.
- `*Properties` test classes are not matched by Surefire's default includes; core/pom.xml
  lists the four default patterns plus `**/*Properties.java` explicitly so property tests
  verifiably execute (discovered when 14 properties compiled but silently did not run).

**Verification**

- `./mvnw verify` green (63 core tests after the final-review fix wave: unit + property +
  architecture; JaCoCo measured 100 % line / 100 % branch on core, gate 95/90).
- CI run 30026798053 green (pull_request run for PR #1 — push CI covers main only); both jobs.

**Next**

- M2 — ebInterface 6.1 erzeugen + validieren: `formats-ebinterface` (ph-ebinterface 8.1.0),
  `mapping` (canonical → ebInterface), validation stages XSD + Schematron (phive) + first
  business rules (Auftragsreferenz, IBAN), golden-file corpus.

## 2026-07-23 — M0 Fundament: complete

**What**

- Verified all pins against Maven Central before use: Spring Boot 4.1.0 (latest 4.1.x), ph-ebinterface 8.1.0, ph-ubl 10.2.0, phive 12.1.0, phive-rules 4.4.1 — the set is mutually compatible (phive-rules-parent 4.4.1 builds against exactly those ph-* versions). Also Spotless 3.8.0, google-java-format 1.35.0, Maven wrapper 3.9.16.
- Maven multi-module skeleton per SPEC §2: parent POM (spring-boot-starter-parent, ph-* pins in dependencyManagement, enforcer, Spotless bound to `validate`) + 8 modules. `app` boots with Actuator health endpoint and a smoke test; library modules carry only `package-info.java` stating their boundary contract.
- Spotless/google-java-format enforced in every build; `.editorconfig`.
- GitHub Actions CI: `verify` job (build + lint + tests, Temurin 25, Maven cache) and Docker image build job (buildx, GHA cache, no push).
- Multi-stage Dockerfile → non-root `eclipse-temurin:25-jre-alpine` runtime with container healthcheck; `docker-compose.yml` with app + postgres:17 (healthchecked, volume); `.env.example` complete.
- README (EN + deutsche Kurzfassung, architecture, quickstart, upstream credits), LICENSE Apache-2.0, ADR-0001 (stack), ADR-0002 (modular monolith), glossary, issue forms + PR template with DoD checklist.
- Repo public at https://github.com/Stoicera/einvoice_at (6 thematic Conventional Commits).

**Decisions**

- Maven wrapper pinned to 3.9.16 (newest stable; Maven 4 still RC).
- Runtime image `eclipse-temurin:25-jre-alpine` instead of distroless: no distroless Java 25 image line; alpine keeps busybox `wget` for the healthcheck. Revisit at M6.
- Compose stays app + postgres per M0 scope; Keycloak/Mailpit join at M3 per milestone plan.
- ArchUnit rules deliberately deferred to M1 — there is no domain code to constrain yet.

**Verification**

- `./mvnw verify` green locally (1 test).
- `docker compose up -d` → both containers healthy; `GET /actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`.
- CI run 30016275283 green on first push (both jobs).

**Next**

- M1 — Kanonisches Rechnungsmodell: EN-16931 core model in `core`, BigDecimal money arithmetic with jqwik property tests, Austrian USt logic, ArchUnit rules active, ADR-0003.

