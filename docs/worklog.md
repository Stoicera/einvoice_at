# Worklog — einvoice-at

## 2026-07-24 — M2 Task 7: own AT-B2G Schematron stage (Auftragsreferenz rule)

**What**

- New original Schematron `validation/src/main/resources/schematron/at-b2g-ebinterface-6.1.sch`
  (queryBinding `xslt`, `eb` bound to `EEbInterfaceVersion.V61.getNamespaceURI()` =
  `http://www.ebinterface.at/schema/6p1/`). Header records provenance: original AT-B2G rules, not
  derived from any AUSTRIAPRO artefact — ebInterface ships no official Schematron. One rule so far:
  `AT-B2G-01`, Auftragsreferenz (`InvoiceRecipient/OrderReference/OrderID` must be non-blank).
- `SchematronStage` runs ph-schematron's pure (XPath) engine against the already-parsed DOM
  (`applySchematronValidationToSVRL(ctx.dom(), null)`); compiled resource cached in a lazy,
  thread-safe holder (mirrors the Task 6 registry holder). `SchematronRuleCatalog` owns the DE/EN
  finding text per rule id (messageDe first); an uncatalogued failed assert never drops — it maps to
  an ERROR with the raw SVRL text in both languages and the id kept as-is.
- Wired into the facade after the XSD stop: Schematron runs only on a schema-valid tree. Facade test
  extended to prove the gating (XSD-broken doc → no `AT-B2G` finding) plus the new business-rule
  path (schema-valid but no order reference → invalid with `AT-B2G-01`).
- TDD: failing tests first (RED = missing `SchematronStage`/`SchematronRuleCatalog` symbols), then
  green. New tests: stage (4), catalog mapper incl. fallback via a synthetic `SVRLFailedAssert` (2),
  facade (+1). Validation module 39 tests.

**Decisions**

- ph-schematron-pure/api stay transitive through the phive stack (phive-xml → ph-schematron-pure
  10.0.0, phive-api → ph-schematron-api 10.0.0) — no new dependency, no free pin. This mirrors the
  established module convention: the XSD stage already consumes ph-diagnostics transitively through
  phive rather than redeclaring helger sub-artifacts; phive is the single governed entry point. A
  provenance comment on the phive-xml dependency records the intent.
- The `.sch` uses `queryBinding="xslt"`, which the pure engine accepts (it registers xslt/xslt2/
  xslt3 alongside xpath* and evaluates XPath either way; Saxon-HE 12.10 on the classpath gives it
  XPath 2.0+, so `normalize-space()`/`!=` are fine).
- Assert text is the German finding text so the raw SVRL stays useful; the catalog is the single
  source of the bilingual pair. All failed asserts map to ERROR (a failed `assert` is a hard rule).
- Fixture `validEbInterface61()` now carries the Auftragsreferenz, so it is the minimal *fully
  AT-B2G-valid* document; the missing/blank-order-reference variants are derived from it.

**Verification**

- `./mvnw verify -pl validation` green; JaCoCo 97.8 % line / 90.5 % branch (gate 90/85). Two
  intentionally-uncovered lines: the defensive `IllegalStateException` catch for a checked
  `applySchematronValidationToSVRL` failure that cannot occur on an XSD-clean DOM (same "cannot
  happen" spirit as the XSD stage's `orElseThrow`). Full `./mvnw verify` green across all nine
  modules.

**Next**

- M2 Task 8+: further AT-B2G rules (`AT-B2G-02` …) extend the same `.sch`/catalog; corpus + CLI
  tasks consume the fixed `AT-B2G-01` id and its DE/EN texts.

## 2026-07-24 — M2 Task 6: validation pipeline skeleton + phive XSD stage

**What**

- New `validation` module wired: deps `core`, `formats-ebinterface`, `phive-api`, `phive-xml`,
  `phive-rules-ebinterface`, `ph-ebinterface` (all parent-managed); JaCoCo gate 90/85.
- `SecureXml` (module-internal): namespace-aware `DocumentBuilderFactory`, secure-processing on,
  `disallow-doctype-decl` on, external entities/DTD off — XXE hardening at the boundary; returns
  `Optional<Document>` (empty for malformed / DOCTYPE).
- Pipeline: `ValidationContext` (lazy DOM / detected version / lenient Ebi61 parse, all memoized),
  `ValidationStage`, `FormatDetectionStage` (`FORMAT-01`/`FORMAT-02`), `XsdValidationStage` (phive
  ebInterface 6.1 VES behind a lazy registry holder), `EbInterface61Validator` facade. Fixed order
  and stop rules: `XML-01` → `FORMAT-01`/`FORMAT-02` → `EBI61-XSD`; `sourceFormat` `ebinterface-6.1`
  / `unknown`; `profile` always `at-b2g`. Facade never throws (null input → `XML-01`).
- TDD: failing tests first; 29 tests (SecureXml, context, both stages, facade integration,
  ArchUnit). ArchUnit: no Spring/JPA, main code must not depend on `mapping..`.
- ADR-0004 records the XSD-message honesty (parser text verbatim behind a German lead-in), the
  pipeline order/stop rules, and the boundary hardening.

**Decisions**

- XSD finding text is the Xerces message as delivered; asked in `Locale.GERMAN` it is German for
  built-in messages (may fall back to English), always behind our German lead-in — no fake
  translation. DOM-sourced errors carry `upload.xml` as location (no line/col in a DOM).
- `severityOf`: error-and-above → ERROR, else WARN (VES is XSD-only, so ERROR in practice).
- Facade decides the XSD stop via the freshly built report's `isValid()` — core stays the single
  source of validity truth.

**Verification**

- `./mvnw verify -pl validation` green; JaCoCo 100 % line / 100 % branch (gate 90/85). Full
  `./mvnw verify` green across all nine modules.

**Next**

- M2 Task 7+: Schematron stage and Austrian B2G business rules (`AT-B2G-01`/`AT-B2G-02`), plugging
  into the same context/stage contract; round-trip fixtures via `mapping` in test scope (Task 9).

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
