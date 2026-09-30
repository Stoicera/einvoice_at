# einvoice-at — Agent Context


## Company (Stoicera Software Group)

Two founders (Sebastian Kern, Raphael Lugmayr), Upper Austria. Brands: Stoicera (B2B web/AI/EU cloud, modern stack) and Lugmayr-Kern (.NET/Java contract work, B2C). Goal until 7.7.2027: 10,000 € monthly result, half recurring — every task names the goal it serves; results first, ship the 80 % version, measure, iterate. Founders orchestrate; agents build, test, deploy, operate. Truth before effect: no invented facts, no superlatives, name assumptions. Simple over complete. Decide and report in three lines (done, open, blocked). Customer contact answered substantively within 4 hours. Never: customer data or secrets in repo/prompts; dark patterns; religious or warrior vocabulary in anything public; Hostinger/Vercel/Railway. German (AT) for customers, code in English, commits in German.

## What einvoice-at is

See `docs/00_ssot.md`. One sentence here: Austrian e-invoicing platform (ebInterface 6.1, Peppol BIS 3.0), for Austrian companies and integrators.
Non-goals: <three bullets>.
Active PRD: `docs/PRD.md` — read before building.

## How we work here

- Work = GitHub issue. Questions as issue comments, not chat.
- Fresh git worktree per task from `origin/main` (never build on main), PR against `main` with `Closes #NN` and the template Intent · Gherkin · Evidence · Debt taken · Open. Small PRs. CI green before PR. Full loop: `ai/prompts/factory-feature.md`.
- Build: `./mvnw verify` (Spotless check + unit + integration tests; Testcontainers, needs Docker) · Test (fast): `./mvnw test -pl core` · E2E: `./mvnw -pl e2e verify -Pe2e` · Load: `./mvnw -pl e2e gatling:test -Pload` (needs a running stack)
- Lint/Format: `./mvnw spotless:apply` (the check runs inside `./mvnw verify`) · Security: `./mvnw -Psecurity verify` (needs `NVD_API_KEY`) · Local stack: `docker compose up -d`
- Migrate: Flyway runs on application start (`spring-boot-starter-flyway` in `app/pom.xml`); no separate command.
- Deploy: push to `main` → `.github/workflows/ci.yml` → image to GHCR → job `Deploy (Dokploy webhook)` → smoke test per `docs/deployment.md` §9. Rollback: `<cmd>` (no manual command in the repo yet; a failed rollout reverts itself via Swarm `FailureAction: rollback`, `docs/deployment.md` §8.3).
- No per-PR preview: the deploy job runs on push to `main` only.
- Before you request review: check the result against the intent, fix deviations yourself. Copilot review runs automatically on every PR; a second model reviews security.
- Decisions with reach → `docs/decisions/` (ADR, one page). Cycle memo → `docs/cycles/`.
- After any production change: append one line to `ops/runlog.md` (date · agent · what · rollback).

## Conventions

- Stack: Java 25, Spring Boot 4.1, Maven multi-module (Maven Wrapper), Thymeleaf, Flyway, PostgreSQL 17, Keycloak, Docker Compose.
- Money in cents (integer), time zone Europe/Vienna, tenant id on every table.
- No new dependency without one sentence of justification in the PR. No speculative abstractions.
- Tests: unit for logic, integration for API, one E2E per critical path. Synthetic data only.
- Accessibility and Lighthouse ≥ 90 on public pages are merge gates.

## Never

- Personal or customer data in repo, fixtures, logs or prompts.
- Secrets in files; use 1Password (`op run`) or Coolify secrets.
- Destructive operations (drop, force-push, rm -rf on servers) without a fresh backup and a run-log line.
- Silent scope changes: if the PRD is wrong, say so in the issue, then build.
