# einvoice-at

[![CI](https://github.com/Stoicera/einvoice_at/actions/workflows/ci.yml/badge.svg)](https://github.com/Stoicera/einvoice_at/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Austrian e-invoicing platform: generate, validate and convert ebInterface 6.1 and Peppol BIS Billing 3.0 (UBL) — with human-readable German validation reports.**

A self-hostable Java 25 / Spring Boot platform built by [Stoicera Software Group](https://stoicera.com) as a production-grade reference system. Austria's federal government only accepts structured e-invoices (ebInterface or Peppol BIS) via e-rechnung.gv.at — and rejected invoices come back with Schematron output that non-technical users cannot read. This platform closes that gap.

> **Status: Milestone M0 — foundation.** Repository skeleton, build, CI and local Docker stack. The domain model lands with M1; see [docs/MILESTONES.md](docs/MILESTONES.md).

## Deutsche Kurzfassung

**einvoice-at** ist eine selbst hostbare Plattform für die österreichische E-Rechnung: Sie **erzeugt** ebInterface 6.1 und Peppol BIS Billing 3.0 (UBL) aus strukturierten Rechnungsdaten, **validiert** hochgeladene XML-Rechnungen gegen XSD, Schematron und österreichische Geschäftsregeln — mit einem menschenlesbaren, deutschen Prüfbericht — und **konvertiert** zwischen beiden Formaten mit dokumentierten Mapping-Grenzen. Optional erklärt ein abschaltbarer KI-Assistent jeden Prüfungsfehler in einfacher Sprache. Aktueller Stand: Milestone M0 (Fundament).

## Architecture

Modular monolith, Maven multi-module, module boundaries enforced by ArchUnit (from M1). See [ADR-0002](docs/adr/0002-modular-monolith.md).

```
einvoice-at
├── core                  canonical invoice model (EN 16931 core subset), pure Java, zero Spring
├── formats-ebinterface   ebInterface 6.1 read/write/validate (wraps ph-ebinterface)
├── formats-ubl           Peppol BIS 3.0 / UBL 2.1 read/write/validate (wraps ph-ubl)
├── mapping               canonical ↔ formats, golden-file tested
├── validation            XSD + Schematron (phive) + Austrian business rules → ValidationReport
├── rendering             invoice → PDF / HTML print view
├── ai-assist             LlmClient port + OpenRouter adapter, feature-flagged, degradable
└── app                   Spring Boot app: REST API, web UI, security, persistence, audit
```

Stack: Java 25, Spring Boot 4.1, PostgreSQL 17 + Flyway, Thymeleaf + htmx, Keycloak, Testcontainers, Selenium. Rationale in [ADR-0001](docs/adr/0001-java-spring-boot-stack.md).

## Quickstart

Requires Docker with Compose.

```bash
git clone https://github.com/Stoicera/einvoice_at.git
cd einvoice_at
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

JUnit 5 + AssertJ + Mockito for unit tests, ArchUnit for module-boundary rules, Testcontainers for integration tests, Selenium WebDriver for E2E — built out milestone by milestone per [docs/ENGINEERING_STANDARDS.md](docs/ENGINEERING_STANDARDS.md). Currently: application smoke test on the health endpoint.

## Deployment

Target: single Hetzner VPS via Dokploy (Traefik/TLS). Deployment documentation lands with milestone M6 in `docs/deployment.md`.

## Standards artefacts & credits

XSD and Schematron handling for ebInterface and Peppol builds on the excellent open-source work of [Philip Helger](https://github.com/phax): [ph-ebinterface](https://github.com/phax/ph-ebinterface), [ph-ubl](https://github.com/phax/ph-ubl), [phive](https://github.com/phax/phive) and [phive-rules](https://github.com/phax/phive-rules) (Apache-2.0/MIT). ebInterface is a standard of [AUSTRIAPRO](https://www.austriapro.at/); Peppol BIS Billing 3.0 is maintained by [OpenPeppol](https://peppol.org/).

## License

[Apache-2.0](LICENSE) © Stoicera Software Group — Raphael Lugmayr & Sebastian Kern GesbR
