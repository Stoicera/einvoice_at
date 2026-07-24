# Stoicera Labs — Engineering Standards (verbindlich für alle Labs-Repos)

> English below where it maps 1:1 to repo content. Diese Standards sind die messbare Definition von "Senior Software Engineering Meisterwerk". Jedes Repo wird daran gemessen — von uns und von jedem, der es liest.

## 1. Definition of Done (pro Feature / Milestone)

Ein Feature ist fertig, wenn:

1. Code implementiert, idiomatisch für den Stack, keine toten Pfade / auskommentierter Code.
2. Unit-Tests + Integrationstests vorhanden und grün; für User-Flows E2E-Test vorhanden.
3. Doku aktualisiert (README-Abschnitt, OpenAPI, ADR falls Architekturentscheidung).
4. CI-Pipeline grün (Build, Tests, Lint, Security-Scan).
5. Läuft im Docker-Compose-Setup lokal UND auf dem Deployment-Target (Hetzner + Dokploy).
6. Kein Secret im Repo. Keine TODO-Kommentare ohne Issue.

## 2. Repository-Hygiene

- **README (Englisch, mit deutscher Kurzfassung):** Was, warum, Architekturdiagramm, Quickstart (< 5 Minuten mit Docker Compose), Screenshots, Testing-Abschnitt, Deployment-Abschnitt, Lizenz.
- **`docs/`**: PRD, SPEC, MILESTONES, ADRs (`docs/adr/NNNN-title.md`, Format: Kontext → Entscheidung → Konsequenzen), Glossar (deutsche Fachbegriffe ↔ englische Erklärung).
- **Conventional Commits** (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`). Kleine, thematisch geschlossene Commits.
- **Branching:** trunk-based; kurzlebige Feature-Branches, PRs mit Beschreibung (auch bei Solo-Arbeit — die PR-Historie ist Teil des Portfolios).
- **Releases:** SemVer, GitHub Releases mit Changelog ab Milestone 6 (erstes getaggtes Release `v0.1.0`, siehe `docs/MILESTONES.md` M6). Entscheidung 2026-07-24: `MILESTONES.md` ist für den Release-Zeitpunkt maßgeblich; diese Zeile war bis dahin im Widerspruch dazu und ist damit korrigiert, nicht neu entschieden.
- **Lizenz:** Apache-2.0 (business-freundlich, Enterprise-üblich).

## 3. Testing-Pyramide (verbindlich)

| Ebene | Java-Projekt | .NET-Projekt |
|-------|--------------|--------------|
| Unit | JUnit 5 + AssertJ + Mockito | xUnit + FluentAssertions + NSubstitute |
| Architektur | ArchUnit (Layer-Regeln) | NetArchTest |
| Integration | Spring Boot Test + **Testcontainers** (PostgreSQL, Keycloak) | WebApplicationFactory + **Testcontainers** (PostgreSQL) |
| E2E / UI | **Selenium WebDriver** (bewusst: JKU-Ausschreibung nennt Selenium) | **Playwright .NET** |
| Last (leichtgewichtig) | Gatling oder k6 (ein Szenario reicht) | k6 (ein Szenario reicht) |

Coverage ist kein Selbstzweck — aber kritische Domänenlogik (Mapping, Validierung, Fristenberechnung) hat nahe 100 %.

## 4. Security-Baseline

- AuthN/AuthZ: OAuth2/OIDC (Java: Spring Security Resource Server + Keycloak als IdP im Compose-Setup; .NET: ASP.NET Core Identity + OIDC-ready).
- Input-Validierung an jeder Systemgrenze; keine dynamischen SQL-Strings (JPA/EF Core Parametrisierung).
- Secrets ausschließlich via Environment/`.env` (nie committen); `.env.example` gepflegt.
- Dependency-Scanning in CI: Dependabot + OWASP Dependency-Check (Java) / `dotnet list package --vulnerable` + GitHub Advisory (NET).
- Security-Header, CSRF-Schutz, Rate Limiting am öffentlichen Endpoint.
- Audit-Log für fachlich relevante Aktionen (wer, was, wann).
- `SECURITY.md` mit Threat-Model-Skizze (STRIDE-light) — das liest bei Ausschreibungen niemand zufällig, aber jeder Prüfer merkt es.

## 5. Observability

- **OpenTelemetry** (Traces + Metrics + Logs) von Anfang an; OTLP-Export konfigurierbar.
- Strukturierte Logs (JSON in Produktion): Java = SLF4J/Logback, .NET = Serilog.
- Health-/Readiness-Endpoints (Actuator bzw. ASP.NET Health Checks).
- Compose-Profil `observability` mit Grafana + Prometheus + Tempo/Loki ODER schlicht Grafana-Cloud-freundlicher OTLP-Export — klein halten, aber vorhanden.

## 6. CI/CD (GitHub Actions)

Pipeline-Stufen: `build → lint/format → unit → integration (Testcontainers) → e2e (nur main/nightly) → security-scan → docker build+push (GHCR) → deploy (Dokploy Webhook, nur main)`.

- Formatierung erzwungen: Spotless + google-java-format (Java) / `dotnet format` + EditorConfig (.NET).
- Statische Analyse: Error Prone oder SonarCloud free tier (Java) / Roslyn Analyzers `TreatWarningsAsErrors` (.NET).
- Build-Zeit unter 10 Minuten halten; E2E darf in separaten Workflow.

## 7. Deployment

- Ein `docker-compose.yml` für lokale Vollumgebung (App + DB + Keycloak + Mailpit + Observability-Profil).
- Produktion: Hetzner VPS + **Dokploy**; Deployment-Doku (`docs/deployment.md`) mit Schritten, Backups (pg_dump-Cron), TLS (Traefik/Let's Encrypt via Dokploy).
- 12-Factor: Konfiguration über Env, stateless App-Container, Migrationen automatisch (Flyway / EF Core Migrations) beim Start oder als Job.

## 8. KI-Integrationsstandard

- Provider-Zugriff ausschließlich über eine eigene schmale Abstraktion (`LlmClient`-Interface); Default-Provider **OpenRouter**, austauschbar (LiteLLM-kompatibel), Modell konfigurierbar.
- KI-Features sind **degradierbar**: Fällt der Provider aus, funktioniert die Kernanwendung vollständig weiter.
- Kein Kundendatenversand ohne explizites Opt-in; Prompt-Templates versioniert im Repo; Kosten-/Token-Logging.

## 9. Dokumentierte Entscheidungen (ADRs) — Minimum je Repo

Mindestens: Stack-Wahl, Architekturstil, Auth-Ansatz (warum Keycloak/Identity), Datenbank-Wahl, KI-Provider-Abstraktion, Deployment-Target. ADRs sind kurz (½ Seite), ehrlich (Trade-offs!), datiert.
