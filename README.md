# einvoice-at

[![CI](https://github.com/Stoicera/einvoice_at/actions/workflows/ci.yml/badge.svg)](https://github.com/Stoicera/einvoice_at/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Austrian e-invoicing platform: generate, validate and convert ebInterface 6.1 and Peppol BIS Billing 3.0 (UBL) — with human-readable German validation reports.**

A self-hostable Java 25 / Spring Boot platform built by [Stoicera Software Group](https://stoicera.com) as a production-grade reference system. Austria's federal government only accepts structured e-invoices (ebInterface or Peppol BIS) via e-rechnung.gv.at — and rejected invoices come back with Schematron output that non-technical users cannot read. This platform closes that gap.

> **Status: Milestone M2 — ebInterface 6.1 generation + validation.** `core`'s canonical invoice
> model now maps to ebInterface 6.1 (`mapping`), and uploaded ebInterface documents run through a
> staged XSD + Schematron + Austrian business-rule pipeline (`validation`) that produces a
> German-first `ValidationReport`. REST API, persistence and the web UI land in M3+. See
> [docs/MILESTONES.md](docs/MILESTONES.md).

## Deutsche Kurzfassung

**einvoice-at** ist eine selbst hostbare Plattform für die österreichische E-Rechnung: Sie **erzeugt** ebInterface 6.1 und Peppol BIS Billing 3.0 (UBL) aus strukturierten Rechnungsdaten, **validiert** hochgeladene XML-Rechnungen gegen XSD, Schematron und österreichische Geschäftsregeln — mit einem menschenlesbaren, deutschen Prüfbericht — und **konvertiert** zwischen beiden Formaten mit dokumentierten Mapping-Grenzen. Optional erklärt ein abschaltbarer KI-Assistent jeden Prüfungsfehler in einfacher Sprache. Aktueller Stand: Milestone M2 (ebInterface 6.1 erzeugen + validieren).

## Architecture

Modular monolith, Maven multi-module; boundary rules are enforced by ArchUnit as each module gains code — the `core`-is-JDK-only rule is active since M1, cross-module rules land with M3. See [ADR-0002](docs/adr/0002-modular-monolith.md).

```
einvoice-at
├── core                  canonical invoice model (EN 16931 core subset), pure Java, zero Spring
├── formats-ebinterface   ebInterface 6.1 read/write/validate (wraps ph-ebinterface)
├── formats-ubl           Peppol BIS 3.0 / UBL 2.1 read/write/validate (wraps ph-ubl) — planned M4
├── mapping               canonical ↔ formats, golden-file tested
├── validation            XSD (phive) + own AT-B2G Schematron + Austrian business rules → ValidationReport
├── rendering             invoice → PDF / HTML print view — planned M4
├── ai-assist             LlmClient port + OpenRouter adapter, feature-flagged, degradable — planned M5
└── app                   Spring Boot app: REST API, web UI, security, persistence, audit — health endpoint only today; the rest lands M3+
```

`core`, `formats-ebinterface`, `mapping` and `validation` are built and tested as of M2; the other four rows are `package-info.java`-only stubs today (see the status note above).

Stack: Java 25, Spring Boot 4.1, PostgreSQL 17 + Flyway, Thymeleaf + htmx, Keycloak, Testcontainers, Selenium. Rationale in [ADR-0001](docs/adr/0001-java-spring-boot-stack.md).

## Quickstart

Requires Docker with Compose.

```bash
git clone https://github.com/Stoicera/einvoice_at.git
cd einvoice_at
cp .env.example .env   # then set POSTGRES_PASSWORD (e.g. openssl rand -base64 24)
docker compose up -d
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

## Development

Requires JDK 25+ (build targets 25). Maven comes via the committed wrapper.

```bash
./mvnw verify                 # full build: Spotless check + unit + integration tests
./mvnw test -pl core          # fast domain feedback loop
./mvnw spotless:apply         # format before committing
docker compose up -d          # local stack (app + postgres)
```

Formatting is google-java-format, enforced by Spotless in every build and in CI.

## Testing

JUnit 5 + AssertJ + Mockito for unit tests, ArchUnit for module-boundary rules, Testcontainers for integration tests, Selenium WebDriver for E2E — built out milestone by milestone per [docs/ENGINEERING_STANDARDS.md](docs/ENGINEERING_STANDARDS.md). Currently: `core` domain model at 99.54 % line / 98.09 % branch coverage (JaCoCo gate: 95/90), including
a [jqwik](https://jqwik.net) property suite for money/VAT arithmetic and an ArchUnit rule
pinning `core` to JDK-only dependencies. Plus the application smoke test on the health endpoint.
Mutation testing ([PIT](https://pitest.org)) gates all four implemented modules in CI — `core` at 90 %, and `mapping`, `validation` and `formats-ebinterface` at 85 % — so the coverage numbers have teeth, not just line reach. The security-critical `validation` module (the untrusted-input boundary) is gated deliberately: its surviving mutants are documented equivalent/defensive ones, not shape-asserting gaps.
The M2 modules carry the same JaCoCo discipline — `formats-ebinterface` and `validation` gate at 90 % line / 85 % branch, `mapping` at 95/90.

## Validation pipeline

`EbInterface61Validator` (module `validation`) validates an uploaded ebInterface 6.1 document against the Austrian B2G profile and returns a `ValidationReport` of German-first `Finding`s. The pipeline is staged with hard gating — a document must clear one stage before the next runs, and each stage stops the pipeline when continuing would be meaningless:

0. **Size guard** — an upload over 20 MB is rejected as `XML-02` before a single byte is parsed: a defensive, module-level cap that protects the validator independently of any caller. This is separate from — and looser than — the stricter 2 MB application-layer cap SPEC §4 places in front of the HTTP endpoint once M3 exposes it.
1. **Secure parse** — `SecureXml` parses the upload with an XXE-hardened, namespace-aware `DocumentBuilderFactory` (`disallow-doctype-decl`, external entities/DTD off). Not well-formed XML or a bare `DOCTYPE` → `XML-01`, pipeline stops.
2. **Format detection** — the root namespace is resolved against known ebInterface versions. An unrecognised namespace → `FORMAT-01`; a recognised but unsupported version (e.g. ebInterface 6.0) → `FORMAT-02`. Either stops the pipeline.
3. **XSD** — the bundled ebInterface 6.1 validation-executor-set (`VID_EBI_61`) runs via [phive](https://github.com/phax/phive). This VES is **XSD-only** — AUSTRIAPRO publishes no Schematron for ebInterface — so schema violations become `XSD-01` findings, each genuinely bilingual (the stage validates the document twice, once per `Locale`, because the underlying Xerces diagnostic text is baked in at validation time; see [ADR-0004](docs/adr/0004-validation-pipeline-and-xsd-messages.md)). An XSD-invalid document stops the pipeline: Schematron and business rules assume a structurally valid tree.
4. **Own AT-B2G Schematron** — runs only once the document is XSD-valid. `AT-B2G-01` checks the Auftragsreferenz is present, required for invoices to Austrian federal bodies.
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
