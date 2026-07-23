# Worklog — einvoice-at

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
- CI run 30026798053 green on branch push (both jobs); PR #1.

**Next**

- M2 — ebInterface 6.1 erzeugen + validieren: `formats-ebinterface` (ph-ebinterface 8.1.0),
  `mapping` (canonical → ebInterface), validation stages XSD + Schematron (phive) + first
  business rules (Auftragsreferenz, IBAN), golden-file corpus.
