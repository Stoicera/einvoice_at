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
   not 0.02 €) and by the jqwik property suite, which recomputes every amount with an
   independent plain-BigDecimal oracle (no reuse of production arithmetic).
4. **Structural invariants in core, profile rules in `validation`.** Core enforces what is true
   of every EN 16931 invoice (unique line ids, due date ≥ issue date, currency coherence,
   arithmetic). Austrian B2G profile rules (Auftragsreferenz mandatory, IBAN presence) are
   validation-module business rules — they produce German findings, not exceptions.
5. **Exemption reasons are supplied, not derived.** Categories AE and E require an exemption
   reason (BT-120/BT-121, BR-AE-10/BR-E-10); S and Z must not carry one (BR-S-10/BR-Z-10).
   The reason is business data the model cannot derive, so the builder accepts it per category
   (`exemptionReason(VatCategory, VatExemptionReason)`); AE defaults to the standard-mandated
   `VATEX-EU-AE` / "Reverse charge", E has no default and must be explicit. VATEX code-list
   validation is a validation-module concern, like unit codes.
6. **Direction by type code, not by sign.** BT-3 is the UNTDID 1001 subset 380/381. Credit
   notes are type 381 with positive amounts (UBL CreditNote / ebInterface CreditMemo practice);
   the payable amount must never be negative. Negative lines (rebates, returns) remain legal as
   long as the document total stays non-negative; a correction that nets negative is a credit
   note, not a negative invoice.
7. **Deliberately absent (YAGNI, documented):** document- and line-level allowances/charges
   (BG-20/BG-21), prepaid amounts, rounding amount (BT-114), multi-currency tax accounting; a
   persistence `id` (the domain model is identity-free — database identity arrives with the
   persistence layer in M3); SPEC §3's `taxSummary` is realized as `vatBreakdown` (EN 16931
   BG-23 naming); VAT categories K (intra-community supply), G (export outside the EU), O (not
   subject to VAT) and L/M (Canary Islands/Ceuta-Melilla — outside Austrian scope) — the
   Austrian rate set covers S/Z/AE/E until mapping demands more; the seller-identity choice
   rule BR-CO-26 (BT-29/BT-30/BT-31 — we always require a name, and registration identifiers
   arrive with the mapping layer); `Address` is deliberately stricter than BG-5 (EN mandates
   only the country code; Austrian B2G practice needs street/city/postal code, so core requires
   them). Added when mapping (M2/M4) demonstrates the need, with property tests in the same PR.

## Konsequenzen

- Mapping code (M2/M4) can never disagree with core arithmetic; a mapped document that fails
  recomputation is a mapping bug by definition.
- The validation module gets recomputation for free via `Invoice.computeVatBreakdown/-Totals`.
- Every arithmetic change must extend the jqwik property suite (CLAUDE.md hard rule); the
  95/90 JaCoCo gate on `core` keeps the domain honest.
- Invoices whose *source documents* contain arithmetic we reject (e.g. per-line tax rounding)
  cannot round-trip losslessly — the conversion report (M4) must surface this, and the
  validation module reports it as a finding rather than refusing to parse.
