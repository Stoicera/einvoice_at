# Contributing to einvoice-at

Thanks for looking. This file says how to build the project, what gets merged, and what to expect
from us.

## Before you start

- **Bug or small fix:** open a pull request directly, or a [bug report](https://github.com/Stoicera/einvoice_at/issues/new?template=bug_report.yml) first if you are unsure.
- **Anything larger** (new endpoint, new format, new dependency): open a [feature request](https://github.com/Stoicera/einvoice_at/issues/new?template=feature_request.yml) first, so we can agree on the shape before you spend the time.
- **Questions:** use the [question](https://github.com/Stoicera/einvoice_at/issues/new?template=question.yml) template.
- **Security issues:** never in a public issue — see [SECURITY.md](SECURITY.md).
- Out of scope by design (PRD §5): a Peppol access point, automatic upload to e-rechnung.gv.at,
  inbound invoice processing, bookkeeping and tax.

## Build and test

Requires Java 25 (the Maven Wrapper fetches Maven) and Docker (integration tests use
Testcontainers).

```bash
./mvnw verify                 # Spotless check + unit + integration tests — what CI's build job runs
./mvnw spotless:apply         # format before you commit
./mvnw test -pl core          # fast loop for the domain model
./mvnw -pl e2e verify -Pe2e   # browser end-to-end tests (needs Docker)
docker compose up -d          # the full local stack, see the README Quickstart
```

## What gets merged

- **Small, focused pull requests** against `main`, with the [PR template](.github/PULL_REQUEST_TEMPLATE.md) filled in.
- **CI green.** Five required checks: build, mutation tests, OWASP Dependency-Check, browser E2E +
  load, Docker image. Branches must be up to date with `main`.
- **Tests with the change:** unit tests for logic, integration tests for API behaviour. A
  validation or mapping bug gets a failing golden file in
  `validation/src/test/resources/corpus/` before the fix.
- **Docs in the same pull request** — README section, OpenAPI annotation, or an ADR in
  `docs/adr/` for a decision with reach.
- **No new dependency** without one sentence in the PR saying why it is needed.
- **Synthetic data only.** Never commit real invoices, personal data, or secrets — not in fixtures,
  logs, or screenshots.
- Validator messages are **German first, English second**. Code and docs are English.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (`fix:`,
  `feat:`, `docs:` …), in English or German.

## What to expect from us

We aim to answer new issues and pull requests within five working days. If a pull request does not
fit, we say why rather than leaving it open.

## Licence

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), the licence of this repository (Apache-2.0 §5).
