# ADR-0001 — Java 25 / Spring Boot 4.1 stack

Date: 2026-07-23 · Status: accepted

## Kontext

einvoice-at is a Stoicera Labs portfolio system targeting the Austrian enterprise market (Werkverträge in Linz/Wien, public sector, JKU tender explicitly naming Java/Spring). The platform must handle Austrian e-invoice standards (ebInterface 6.1, Peppol BIS Billing 3.0), where mature open-source tooling exists in the Java ecosystem. The repository itself is a proof of senior enterprise Java work — the stack choice is part of the message.

## Entscheidung

- **Java 25 (LTS)** — current LTS; signals up-to-date Java practice. Build targets 25 via `maven.compiler.release`.
- **Spring Boot 4.1.0** (Spring Framework 7) — the current OSS line; 3.5 went EOL 2026-06-30. Verified as latest release on Maven Central 2026-07-23.
- **Maven multi-module** with committed wrapper (3.9.16) — the enterprise default in Austria; module layout doubles as the architecture (ADR-0002).
- **PostgreSQL 17 + Flyway** — boring, migration-first persistence (from M3).
- **Thymeleaf + htmx** — server-rendered UI without SPA build complexity (from M5).
- **Keycloak as IdP**, Spring Security OAuth2 Resource Server (from M3).
- **ph-ebinterface 8.1.0, ph-ubl 10.2.0, phive 12.1.0, phive-rules 4.4.1** for standards artefacts — actively maintained by Philip Helger; we do not hand-copy XSD/Schematron. Pinned in the parent POM; the set is mutually compatible (phive-rules 4.4.1 itself builds against exactly these ph-ebinterface/ph-ubl versions).

## Konsequenzen

- Enterprise reviewers see the exact stack the regional market asks for; no framework zoo to justify.
- Spring Boot 4.x is young — patch upgrades are expected and must be routine (Dependabot).
- Coupling to the Helger library family: release cadence and API changes there dictate our upgrade path for standards artefacts. Accepted — the alternative (maintaining XSD/Schematron ourselves) is strictly worse.
- Java 25 requires current toolchains everywhere (CI, Docker base images, contributor machines); the enforcer plugin fails fast on older environments.
