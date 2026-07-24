# ADR-0002 — Modular monolith

Date: 2026-07-23 · Status: accepted

## Kontext

The platform has one bounded domain (invoice generation, validation, conversion), one team, and a single-VPS deployment target (Hetzner + Dokploy). Stoicera Labs explicitly anti-lists "twelve microservices without a business reason". At the same time the domain has sharp internal seams: a canonical model, two format implementations, mapping, a validation pipeline, rendering, and an optional AI layer — and ebInterface 7.0 (Q4 2026) must be addable without touching the core.

## Entscheidung

One deployable Spring Boot application, structured as a **Maven multi-module modular monolith**:

```
core → formats-ebinterface / formats-ubl → mapping → validation → rendering → ai-assist → app
```

Boundary rules (ArchUnit-enforced incrementally — the core rule since M1, the cross-module rules as the involved modules gain code, M2/M3):

- `core` depends on nothing but the JDK.
- `formats-*` and `mapping` never import Spring.
- Only `app` knows the database.
- `ai-assist` is called only from `app`, behind the `features.ai-explanations` flag.

## Konsequenzen

- One artifact to build, test, deploy and operate — honest for this scale, restart-proof on a single VPS.
- Module boundaries are compiler- and ArchUnit-enforced, not convention-only; each rule fails the build from the milestone it lands in. Rules must never be weakened to make a test pass.
- Library modules stay Spring-free, so domain logic tests run in milliseconds and the core could be extracted as a standalone library later if a real reason appears.
- If scale ever demands service extraction, the module seams are the cut lines — but that is a future decision with its own ADR, not a hedge we pay for now.
