# Validation golden-file corpus

This directory is the executable specification of the `validation` module's ebInterface 6.1 / Austrian
B2G pipeline (`EbInterface61Validator`). Every file here is run through the real validator by
`CorpusTest`, and the set of rule ids it is expected to fail on is asserted against the table below.
The corpus is a regression net that grows by one discipline (CLAUDE.md): **first a failing corpus
file, then the fix** — a reported mapping or validation bug becomes a golden file here before it is
closed in code.

## Layout

```
corpus/
  valid/     documents that must pass the whole pipeline with no compliance-affecting finding
  invalid/   documents that must fail on exactly one deliberate defect
```

## What is asserted

`CorpusTest` compares the **distinct rule ids of the ERROR/WARN (compliance-affecting) findings** the
validator produces against the expected set, and cross-checks `isValid()` against "expected set is
empty". `INFO` findings never gate compliance and are ignored, so a `valid/` file may legitimately
carry them (none does today). Distinct ids rather than a multiset because a single structural defect
can legitimately surface as several XSD findings — see `xsd-missing-invoice-number.xml`.

**Set semantics — ERROR+WARN, stricter than `isValid()` (finding A7).** The expected set is matched
set-equal over **both** ERROR and WARN findings — the corpus's notion of "compliance-affecting".
This is deliberately stricter than core's `ValidationReport.isValid()`, which counts **ERROR only**.
The gap matters the moment the first WARN-severity rule is added (SPEC §7 plans "tax rates
plausible", a natural WARN): a WARN-only finding must show up in the expected set here even though it
leaves `isValid()` true — so it cannot silently pass the corpus. The secondary `isValid() ==
(expected set empty)` cross-check holds only while no file expects a *WARN-only* set (none does
today); when the first WARN-only rule lands, that cross-check — not the set-equal assertion, which is
load-bearing — is the one to revisit.

The pipeline is staged and short-circuits, which is itself under test: a not-well-formed upload never
reaches format detection, a wrong-version document never reaches the XSD stage, and an XSD-invalid
document never reaches the Schematron/business-rule stages. Each `invalid/` file therefore isolates
exactly one stage.

## Corpus table

| File | Expected (ERROR/WARN rule ids) | Distinguishing feature / stage exercised |
|---|---|---|
| `valid/minimal.xml` | *(none)* — `isValid()` | full pipeline, happy path (floor of validity) |
| `valid/b2g-full.xml` | *(none)* — `isValid()` | full pipeline, fully-populated B2G invoice |
| `valid/credit-memo-reverse-charge.xml` | *(none)* — `isValid()` | `DocumentType="CreditMemo"` + reverse-charge (AE) tax item |
| `valid/exempt-invoice.xml` | *(none)* — `isValid()` | tax-exempt (category E) + exemption reason |
| `invalid/malformed.xml` | `XML-01` | secure DOM parse |
| `invalid/wrong-namespace-ebi60.xml` | `FORMAT-02` | format detection |
| `invalid/xsd-missing-invoice-number.xml` | `XSD-01` (≥1) | XSD schema |
| `invalid/at-b2g-01-missing-order-reference.xml` | `AT-B2G-01` | AT-B2G Schematron (OrderReference absent) |
| `invalid/at-b2g-01-whitespace-order-id.xml` | `AT-B2G-01` | AT-B2G Schematron (OrderID whitespace-only) |
| `invalid/at-b2g-02-invalid-iban.xml` | `AT-B2G-02` | AT-B2G business rule (IBAN mod-97) |
| `invalid/at-b2g-03-missing-biller-email.xml` | `AT-B2G-03` | AT-B2G Schematron (Biller/Address/Email absent) |
| `invalid/at-b2g-04-missing-suppliernumber.xml` | `AT-B2G-04` | AT-B2G Schematron (Biller/InvoiceRecipientsBillerID absent) |
| `invalid/at-b2g-05-missing-payment-method.xml` | `AT-B2G-05` | AT-B2G Schematron (PaymentMethod absent) |

## File-by-file

### `valid/minimal.xml`
The smallest document that clears the whole pipeline: every element and attribute the bundled
ebInterface 6.1 XSD requires, plus every field the AT-B2G Schematron demands — the
`OrderReference/OrderID` (Auftragsreferenz, `AT-B2G-01`), a Biller `Address/Email` (`AT-B2G-03`), the
`InvoiceRecipientsBillerID` (Lieferantennummer, `AT-B2G-04`) and a `PaymentMethod` (`AT-B2G-05`) — and
nothing else (single line, single tax item). Hand-authored (it mirrors
`TestDocuments.validEbInterface61()`, which the validator's unit tests independently prove valid) so
the corpus documents the *floor* of validity, not just the fully-populated sample. **M3:** extended
with `Address/Email`, `InvoiceRecipientsBillerID` and the minimal `PaymentMethod/NoPayment` variant so
it stays valid under the new `AT-B2G-03`/`04`/`05` rules; `TestDocuments.validEbInterface61()` was
extended identically so the two stay mirrors of each other.

### `valid/b2g-full.xml`
The fully-populated B2G invoice: two taxed lines (20 % and 10 %), Auftragsreferenz, Lieferantennummer,
a delivery date (BT-72, `Delivery/Date`), a Biller contact email (`Address/Email`), SEPA payment
details, due date and payment terms. **Provenance:** this file is the byte-for-byte
output of the real generation pipeline for `samples/invoice-b2g-sample.json`
(`InvoiceJsonReader` → `InvoiceToEbInterface61Mapper` → `EbInterface61Strategy.write`); it is the same
artifact committed as `samples/invoice-b2g-sample.ebinterface.xml`. `EndToEndGenerationTest` pins the
samples twin byte-for-byte against the live pipeline output, and separately pins this corpus copy
byte-for-byte against the samples twin — so all three are provably identical, transitively including
the live pipeline output. The nine `invalid/` files below are each derived from this document
by introducing **exactly one** defect, so the difference under test is isolated to a single edit.

### `valid/credit-memo-reverse-charge.xml`
**Distinguishing feature:** the only credit note in the corpus (`DocumentType="CreditMemo"`) and the
only reverse-charge document — a single line at category `AE` (Übergang der Steuerschuld, § 19 UStG),
so `TaxAmount` is 0 and the tax item carries the `"Übergang der Steuerschuld: …"` comment. Auftrags­
referenz and Lieferantennummer present (AT-B2G-clean); a `PaymentMethod/NoPayment` block, because an
effektive Gutschrift moves no money (finding A10 — a `CREDIT_NOTE` without paymentMeans emits
`NoPayment` rather than omitting the payment block).
**Provenance:** pipeline output of the current generation chain
(`Invoice.builder()` → `InvoiceToEbInterface61Mapper` → `EbInterface61Strategy.write`). Canonical
input: invoice `2026-CN-0042`, type `CREDIT_NOTE`, issue `2026-07-15`, order ref `BBG-2026-CN-0042`,
supplier `L-100234`, seller `Bau Süd GmbH`/`ATU33333333`, buyer `Bundesbeschaffung GmbH`/`ATU87654321`,
one line `Bauleistung (Reverse Charge)` × 1 `C62` @ `5000.00` at `VatRate.REVERSE_CHARGE`, no
paymentMeans. The `Country` element text is the German display name (`Österreich`) with the ISO code
on `@CountryCode` (finding A6). **Regenerate** by re-running that chain on the same canonical input
(see "Regenerating" below). **M3:** `Address/Email` added (`office@bau-sued.at`) so it stays valid
under the new `AT-B2G-03` rule; not reflected in the canonical-input list above because the current
mapper/canonical model already carries a Biller e-mail (M3 Tasks 1–2, earlier in this same milestone)
— the addition only catches this hand-provenanced file up to what the pipeline now actually emits.

### `valid/exempt-invoice.xml`
**Distinguishing feature:** the only tax-exempt document — a single line at category `E` with an
exemption reason (`VATEX-EU-G` / "Innergemeinschaftliche Lieferung"), so `TaxAmount` is 0 and the tax
item carries the `"Steuerbefreiung: …"` comment. Auftragsreferenz and Lieferantennummer present.
**Provenance:** pipeline output of the same generation chain. Canonical input: invoice `2026-EX-0042`,
type `INVOICE`, issue `2026-07-12`, order ref `BBG-2026-EX-0042`, supplier `L-100777`, seller
`Export Öl GmbH`/`ATU55555555`, buyer `Abnehmer S.r.l.`/`IT12345670017`, exemption reason
`VATEX-EU-G` on category `EXEMPT`, one line `Lieferung (steuerfrei)` × 4 `C62` @ `250.00` at
`VatRate.EXEMPT`. The `Country` element text is the German display name (`Österreich` for the seller,
`Italien` for the `IT` recipient) with the ISO code on `@CountryCode` (finding A6). **Regenerate** by
re-running that chain on the same canonical input. **M3:** `Address/Email` (`office@export-oel.at`)
and a `PaymentMethod/UniversalBankTransaction` (the same canonical IBAN/BIC as `b2g-full.xml`) added so
it stays valid under the new `AT-B2G-03`/`AT-B2G-05` rules, for the same reason as the credit-memo file
above.

### `invalid/malformed.xml`
**Defect:** the `InvoiceNumber` end tag is misspelled `</InvoiceNo>`, so the start/end tags do not
match and the document is not well-formed XML. The hardened parser rejects it before any format or
schema logic runs → single `XML-01`.

### `invalid/wrong-namespace-ebi60.xml`
**Defect:** the root namespace is `.../schema/6p0/` instead of `.../schema/6p1/`. ebInterface 6.0 is a
*recognised but unsupported* version, so format detection stops the pipeline → single `FORMAT-02`
(naming both the found 6.0 and the supported 6.1). A namespace matching no known format would instead
be `FORMAT-01`; that case is covered by the validator's unit tests.

### `invalid/xsd-missing-invoice-number.xml`
**Defect:** the required `<InvoiceNumber>` element is removed. The document is well-formed and in the
right namespace, but structurally invalid against the 6.1 XSD → one or more `XSD-01` findings. This
file also guards the **stage gate**: because XSD failure short-circuits the pipeline, no `AT-B2G`
finding may appear even though the (still present) order reference and IBAN would otherwise be checked.

### `invalid/at-b2g-01-missing-order-reference.xml`
**Defect:** the whole `<OrderReference>` element is removed from `InvoiceRecipient`. The document is
XSD-valid (OrderReference is optional in the schema) but violates the Austrian federal B2G requirement
for an Auftragsreferenz → single `AT-B2G-01` from the Schematron stage.

### `invalid/at-b2g-01-whitespace-order-id.xml`
**Defect:** the `<OrderID>` value is replaced with whitespace only (`   `), leaving the
`<OrderReference>` element structurally present. The document is XSD-valid (`IDType` is an
unconstrained string) but the Schematron's `normalize-space(...)` is empty, so it still fails
`AT-B2G-01` → single finding. This is the whitespace sibling of the fully-absent case above: it pins
that a *blank* Auftragsreferenz is no better than a missing one.

### `invalid/at-b2g-02-invalid-iban.xml`
**Defect:** the beneficiary `IBAN` last digit is changed `…573201` → `…573202`, keeping the correct
shape and length (so it clears the XSD, which only bounds length) but failing the mod-97 checksum →
single `AT-B2G-02` from the hand-written business-rule stage.

### `invalid/at-b2g-03-missing-biller-email.xml`
**Defect:** the `<Email>` element is removed from `Biller/Address`, leaving the rest of `Address`
(`Name`/`Town`/`ZIP`/`Country`) and the sibling `InvoiceRecipientsBillerID` intact. The document is
XSD-valid (`Email` is optional in `AddressType`) but violates the Austrian federal B2G requirement for
a Biller contact e-mail address → single `AT-B2G-03` from the Schematron stage.

### `invalid/at-b2g-04-missing-suppliernumber.xml`
**Defect:** the whole `<InvoiceRecipientsBillerID>` element is removed from `Biller`, leaving its
`Address`/`Email` intact. The document is XSD-valid (`InvoiceRecipientsBillerID` is optional in
`BillerType`) but violates the Austrian federal B2G requirement for a Lieferantennummer → single
`AT-B2G-04` from the Schematron stage.

### `invalid/at-b2g-05-missing-payment-method.xml`
**Defect:** the whole `<PaymentMethod>` element (and its `UniversalBankTransaction`/`BeneficiaryAccount`
content) is removed. The document is XSD-valid (`PaymentMethod` is optional on `InvoiceType`) but
violates the Austrian federal B2G requirement for a payment method (neither `UniversalBankTransaction`
nor `NoPayment` present) → single `AT-B2G-05` from the Schematron stage.

**IBAN/BIC provenance (all files above whose payment block carries a `UniversalBankTransaction`).**
`AT611904300234573201` and its Bank-Austria-format BIC `BKAUATWW` — the value
`at-b2g-02-invalid-iban.xml` mutates by one digit — are canonical ebInterface test values shared by
every such corpus file, checksum-valid so the `AT-B2G-02` happy path is exercised deliberately rather
than by accident. Neither is a real account or a real bank customer; see `samples/README.md` for the
same note against the JSON sample.

## Regenerating the pipeline-derived files

**`valid/b2g-full.xml`** — do not hand-edit it. On an intentional mapper/writer change, rerun
`EndToEndGenerationTest` locally: it prints the fresh pipeline output as the assertion "actual", which
you copy verbatim into both `valid/b2g-full.xml` and `samples/invoice-b2g-sample.ebinterface.xml`.
It is the only valid file pinned byte-for-byte against the live pipeline (`EndToEndGenerationTest`).
See `samples/README.md` for the twin's role in the one-time official portal check (Abnahme).

**`valid/credit-memo-reverse-charge.xml` and `valid/exempt-invoice.xml`** — also pipeline output, but
not byte-for-byte pinned (their canonical inputs live only in this README's provenance notes, not in a
committed JSON twin). On an intentional mapper/writer change, regenerate them by re-running the
generation chain on the canonical inputs documented above and copying the output back. `CorpusTest`
guards that they stay *valid*, not that they stay byte-identical; keep the provenance notes truthful.

**The `invalid/` files** are each `valid/b2g-full.xml` (or, for the whitespace case, the same base)
with **exactly one** documented defect re-applied — re-derive them by hand after regenerating
`b2g-full.xml`.

## Maintenance caveat

XSD-stage tests pin exact Xerces message phrasing (a JDK/Xerces-upgrade landmine); the mapper's schema-validity fixtures live in multiple locations (Fixtures, TestDocuments, corpus) that must be kept consistent when validation documents change.

**M3 note.** The `AT-B2G-03`/`04`/`05` rules closed three federal-MUST gaps ADR-0004 Entscheidung 9
named as documented-but-unimplemented (Biller e-mail, Lieferantennummer, payment-method presence). All
four `valid/` files now carry the three previously-optional fields (`Biller/Address/Email`,
`Biller/InvoiceRecipientsBillerID`, `PaymentMethod`); `TestDocuments.validEbInterface61()` in the
validator's unit tests was extended identically so the two stay mirrors of each other. Any *new*
`valid/` fixture added after M3 must carry all three fields from the start, or it will fail
`CorpusTest` the moment it is added.

## M4 — Peppol BIS Billing 3.0 (UBL)

Three files judged not by this project's own rules but by the **official OpenPeppol rule set**,
executed unmodified through phive at the version pinned in `PeppolValidationStage`
(`2025.11` as of 2026-07-25). That difference matters for how they are maintained:

- **`valid/peppol-ubl-invoice.xml`** and **`valid/peppol-ubl-creditnote.xml`** — output of the real
  chain (`PeppolFixtures` → `InvoiceToUblMapper` → the UBL strategy), the UBL counterpart of
  `valid/b2g-full.xml`. Regenerate them by re-running that chain and copying the output back.
  A credit note is a *separate document type with a separate Peppol rule set* (a `ubl:CreditNote`
  root, judged by `eu.peppol.bis3:creditnote`), which is why both are here rather than one standing
  in for the other.
- **`invalid/peppol-missing-endpoint-ids.xml`** — the same invoice with both parties' electronic
  addresses (BT-34/BT-49) removed. One defect, but the rule set reports it **per party**, so the
  expected id set has two entries: `PEPPOL-EN16931-R020` (seller) and `PEPPOL-EN16931-R010`
  (buyer). That is the rules being per-party, not this file carrying two defects — the
  one-defect-per-file convention above still holds.

**Expected rule ids here are the rule set's own** (`PEPPOL-EN16931-R010`, `BR-…`, `UBL-CR-…`), not
ids from this project's `RuleIds` registry, because the finding carries the assertion id the
official rules publish. A reader can look those up directly in the OpenPeppol documentation.

**Rule-set upgrade caveat.** Bumping the pinned Peppol version is expected to change what these
files report — that is the whole point of pinning it. Re-run `CorpusTest` as part of any such bump
and update the expectations deliberately; a silent change here would mean the same invoice quietly
started or stopped being valid. The procedure is in ADR-0007.
