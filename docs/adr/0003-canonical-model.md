# ADR-0003 — The canonical model derives, it does not trust

Date: 2026-07-23 · Status: accepted

## Kontext

Every format this platform touches (ebInterface 6.1, UBL BIS 3.0) carries redundant arithmetic:
line nets, category taxables, tax amounts, document totals. Redundancy invites inconsistency —
the number-one rejection reason for B2G invoices. The canonical model in `core` is the single
place where invoice arithmetic is defined; formats and mapping (M2/M4) translate into and out of
it, and the validation module (M2) compares documents against it.

## Entscheidung

1. **Derive, don't trust.** `Invoice.Builder` computes the VAT breakdown and totals from the
   lines; callers cannot supply them. The record constructor re-derives and verifies both, so
   even direct constructor calls cannot create an arithmetically inconsistent invoice.
2. **One rounding step per amount.** `Money` is scale-2 `BigDecimal` with `HALF_UP`
   (kaufmännisches Runden). Rounding happens exactly once per derived amount: at the line net
   (quantity × price) and at the category tax (category taxable × rate, per EN 16931 BR-CO-17).
   Construction from raw decimals with more than two places is rejected, never silently rounded.
3. **Category-sum taxation.** Tax is computed on the per-category taxable sum, not per line —
   the EN 16931 rule. This is pinned twice: by the example test
   `InvoiceTest.taxIsComputedOnTheCategorySum` (two 0.10 € lines at 13 % yield 0.03 € tax,
   not 0.02 €) and by the jqwik property that re-derives every breakdown tax from its category
   taxable sum.
4. **Structural invariants in core, profile rules in `validation`.** Core enforces what is true
   of every EN 16931 invoice (unique line ids, due date ≥ issue date, currency coherence,
   arithmetic). Austrian B2G profile rules (Auftragsreferenz mandatory, IBAN presence) are
   validation-module business rules — they produce German findings, not exceptions.
5. **Deliberately absent (YAGNI, documented):** document- and line-level allowances/charges
   (BG-20/BG-21), prepaid amounts, rounding amount (BT-114), multi-currency tax accounting; a
   persistence `id` (the domain model is identity-free — database identity arrives with the
   persistence layer in M3); SPEC §3's `taxSummary` is realized as `vatBreakdown` (EN 16931
   BG-23 naming). Added when mapping (M2/M4) demonstrates the need, with property tests in the
   same PR.

## Konsequenzen

- Mapping code (M2/M4) can never disagree with core arithmetic; a mapped document that fails
  recomputation is a mapping bug by definition.
- The validation module gets recomputation for free via `Invoice.computeVatBreakdown/-Totals`.
- Every arithmetic change must extend the jqwik property suite (CLAUDE.md hard rule); the
  95/90 JaCoCo gate on `core` keeps the domain honest.
- Invoices whose *source documents* contain arithmetic we reject (e.g. per-line tax rounding)
  cannot round-trip losslessly — the conversion report (M4) must surface this, and the
  validation module reports it as a finding rather than refusing to parse.
