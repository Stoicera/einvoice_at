# einvoice-at — Agent Context


## Company (Stoicera Software Group)

Two founders (Sebastian Kern, Raphael Lugmayr), Upper Austria. Brands: Stoicera (B2B web/AI/EU cloud, modern stack) and Lugmayr-Kern (.NET/Java contract work, B2C). Goal until 7.7.2027: 10,000 € monthly result, half recurring — every task names the goal it serves; results first, ship the 80 % version, measure, iterate. Founders orchestrate; agents build, test, deploy, operate. Truth before effect: no invented facts, no superlatives, name assumptions. Simple over complete. Decide and report in three lines (done, open, blocked). Customer contact answered substantively within 4 hours. Never: customer data or secrets in repo/prompts; dark patterns; religious or warrior vocabulary in anything public; Hostinger/Vercel/Railway. German (AT) for customers, code in English, commits in German.

## What einvoice-at is

See `docs/00_ssot.md`. One sentence here: Austrian e-invoicing platform (ebInterface 6.1, Peppol BIS 3.0), for Austrian companies and integrators.
Non-goals: <three bullets>.
Active PRD: `docs/prd/NN_*.md` — read before building.

## How we work here

- Work = GitHub issue. Questions as issue comments, not chat.
- Fresh git worktree per task from `origin/main` (never build on main), PR against `main` with `Closes #NN` and the template Intent · Gherkin · Evidence · Debt taken · Open. Small PRs. CI green before PR. Full loop: `ai/prompts/factory-feature.md`.
- Build: `<cmd>` · Test: `<cmd>` · Lint/Typecheck: `<cmd>` · Migrate: `<cmd>`
- Deploy: push to `main` → GitHub Actions → Coolify (`<app name>`) → smoke test → Sentry check. PostHog receives events from `main` (big products). Rollback: `<cmd>`.
- Preview per PR at `<pattern>`.
- Before you request review: check the result against the intent, fix deviations yourself. Copilot review runs automatically on every PR; a second model reviews security.
- Decisions with reach → `docs/decisions/` (ADR, one page). Cycle memo → `docs/cycles/`.
- After any production change: append one line to `ops/runlog.md` (date · agent · what · rollback).

## Conventions

- Stack: <Next.js 15, TypeScript strict, Prisma, PostgreSQL 17, Tailwind, shadcn/ui>.
- Money in cents (integer), time zone Europe/Vienna, tenant id on every table.
- No new dependency without one sentence of justification in the PR. No speculative abstractions.
- Tests: unit for logic, integration for API, one E2E per critical path. Synthetic data only.
- Accessibility and Lighthouse ≥ 90 on public pages are merge gates.

## Never

- Personal or customer data in repo, fixtures, logs or prompts.
- Secrets in files; use 1Password (`op run`) or Coolify secrets.
- Destructive operations (drop, force-push, rm -rf on servers) without a fresh backup and a run-log line.
- Silent scope changes: if the PRD is wrong, say so in the issue, then build.
