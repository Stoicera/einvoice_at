# CLAUDE.md — einvoice-at

You are working on **einvoice-at**: a production-grade Java/Spring Boot platform for generating, validating and converting Austrian e-invoices (ebInterface 6.1, Peppol BIS Billing 3.0 UBL). This repository is a public portfolio piece of the Stoicera Software Group — it must read like the work of a senior enterprise Java engineer. Quality over speed, always.

## Read first (in this order)
1. `docs/STOICERA_LABS_KONTEXT.md` — who we are, who this is for
2. `docs/ENGINEERING_STANDARDS.md` — binding Definition of Done, testing, security, CI
3. `docs/PRD.md` — product requirements (German)
4. `docs/SPEC.md` — technical spec: stack, module layout, domain model, API
5. `docs/MILESTONES.md` — work strictly milestone by milestone

## Hard rules
- **Stack:** Java 25, Spring Boot 4.1.x, Maven multi-module, PostgreSQL + Flyway, Thymeleaf + htmx UI, Keycloak IdP, Testcontainers, Selenium E2E. Do not introduce other frameworks/languages without an ADR and explicit approval.
- **Architecture:** modular monolith as laid out in SPEC §2. `core` has zero Spring/JPA dependencies. ArchUnit rules enforce module boundaries — never weaken a rule to make a test pass; fix the design instead.
- **Money is `BigDecimal`** with explicit scale/rounding. Any invoice arithmetic change needs property-based tests (jqwik).
- **Standards artefacts:** use ph-ebinterface / phive / ph-ubl for XSD/Schematron; never hand-copy XSDs or Schematron rules into the repo without provenance notes. Credit upstream in README.
- **Every finding the validator produces must have a German message.** English second.
- **AI features** live only in `ai-assist`, behind the `features.ai-explanations` flag, degrade gracefully, and scrub PII before any LLM call.
- **No secrets in the repo.** `.env.example` stays complete and current.
- **Tests before done:** a milestone is complete only per the Definition of Done in ENGINEERING_STANDARDS.md. Never mark work done with failing CI.
- **Commits:** Conventional Commits, small and thematic. Update README/ADRs in the same PR as the change they document.
- Language: code/comments/docs in English; domain terms (ebInterface, Auftragsreferenz, USt) stay German with glossary entries in `docs/glossary.md`.

## Working style
- One milestone at a time. Start each session by reading the current milestone in `docs/MILESTONES.md` and running the test suite; end each session with green CI and a short progress note in `docs/worklog.md` (date, what, decisions, next).
- When a requirement is ambiguous, check PRD → SPEC → ask the owner (Sebastian). Do not silently invent scope.
- When you fix a bug in mapping/validation: first add a failing golden-file test to the corpus, then fix.
- Prefer boring, idiomatic solutions over clever ones. This repo is read by enterprise reviewers.

## Commands (keep current as the repo grows)
```bash
./mvnw verify                 # full build + unit + integration tests
./mvnw test -pl core          # fast domain feedback loop
docker compose up -d          # local stack (app, postgres, keycloak, mailpit)
./mvnw spotless:apply         # format before committing
```
