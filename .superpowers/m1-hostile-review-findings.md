# M1 hostile due-diligence findings (2026-07-23, synthesized from 4 lens reviews)

Deliberately not fixed yet — Sebastian to prioritize. P1 = gut the repo's central claims; P2 = an enterprise reviewer blocks on these; P3 = discipline/polish.

## P1 — Critical
1. AE/E invoices non-conformant by construction: BG-23 lacks BT-120/BT-121 exemption reason (BR-AE-10/BR-E-10); undocumented in ADR-0003. VatBreakdownEntry.java.
2. Flagship jqwik properties are non-falsifiable: everyBreakdownTax…/grossEqualsNetPlusTax… re-assert VatBreakdownEntry/Totals constructor invariants; taxTotal/netTotal properties are same-code-path mirrors; ADR-0003 cites a tautology as the BR-CO-17 pin; VAT numbers pinned only by ~3 example assertions; no mutation testing.

## P2 — Major
3. No BT-3 invoice type code; credit notes as negative quantities; wholly negative invoices constructible; undocumented. Invoice.java/InvoiceLine.java.
4. "ArchUnit-enforced boundaries" overstated: 1 of 4 SPEC §2 rules exists; ADR-0002 claims "violations fail the build". README/ADR-0002/SPEC vs CoreArchitectureTest.
5. Unbounded BigDecimal magnitude/precision: Money.of("1E+1000000000") → OOM via setScale; million-digit quantity×price CPU/heap bomb inside build(). Money.java:29-34, InvoiceLine.java.
6. Multi-currency derivation untested: generators + all examples EUR-only; hardcoded-EUR regression passes all 63 tests.
7. Supply chain: Actions pinned to mutable tags (ci.yml); base images by tag not digest (Dockerfile, compose); no Dependabot config (zero-cost, not covered by the M6 scan deferral).
8. Working default Postgres password committed + 5432 published on all interfaces by default. docker-compose.yml, .env.example.
9. Exception design debt: messages echo unvalidated input verbatim (full IBAN = PII; control chars/unbounded length pre-shape-check) → log-injection/GDPR debt at M2; single unstructured English-only exception type gives M2's German-first findings no ruleId reuse path.
10. Domain-scope gaps collectively undocumented: VatCategory lacks K/G/O; BR-CO-26 seller identity (BT-29/30/31 choice) unrepresentable; Address stricter than BG-5 (only BT-40 mandatory in EN). ADR-0003 §5 incomplete.
11. SPEC §3 stale: still lists `id` + `taxSummary[]`; code has neither name. ADR footnote does not repair an authoritative-looking spec (DoD "Doku aktualisiert" miss).

## P3 — Minor
12. Derived state stored as record components → ~4× recompute per build(), 13-positional-arg public constructor (tamper-guarantee counterpoint documented in ADR-0003; defensible, debatable).
13. Message-style drift (prefix/capitalization) across value objects; misleading null messages in InvoiceLine ("must be non-zero" for null); IVE-for-null vs NPE convention (Effective Java 72); Money.of(String) leaks NumberFormatException.
14. Enforced gate (95/90) weaker than advertised measurement (100/100); no mutation testing to give the number teeth.
15. jqwik seeds unpinned → property failures irreproducible from recorded runs.
16. Surefire include-list hand-maintained; silent-skip failure mode persists for future *IT/*Spec suffixes (already bit once).
17. Glossary gaps: kaufmännisches Runden, Kleinunternehmer missing; Kennzahl unused.
18. Worklog CI provenance wrong: run was pull_request-triggered, not "branch push" (push filters to main).
19. TDD optics: Totals/VatBreakdownEntry direct tests arrived only with the coverage-gate commit whose subject understates its scope.
20. .dockerignore misses .superpowers/ → internal review scratch enters build context and GHA layer cache (mode=max on PRs widens poisoning surface).
21. Unbounded free-text lengths (invoiceNumber, description, name, address fields) vs EN BT max lengths at the future trust boundary.
22. countryCode not trim/case-normalized while vatId/bic are; InvoiceLine Javadoc understates enforced invariants (non-zero, scale caps).
23. Test boundary gaps: scale-4 acceptance never deterministic; VatRate 2.5 %/100 % boundaries untested; wrong-length-checksum-valid IBAN acceptance unasserted; times() bound (≤0.005) doesn't pin HALF_UP direction.
