# Worklog — einvoice-at

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
- **XSD stage validates twice, once per locale, for genuinely bilingual `EBI61-XSD` text.** Xerces
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
  - `validation`: 66 tests; JaCoCo 94.67 % / 92.59 % (gate 90/85).
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
- M2 Task 12: `./mvnw verify`, push `feat/m2-ebinterface`, open PR #2, watch CI green including the
  mutation job; merge is Sebastian's call (M1 precedent).
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
