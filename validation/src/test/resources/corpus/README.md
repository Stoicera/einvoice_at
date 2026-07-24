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

The pipeline is staged and short-circuits, which is itself under test: a not-well-formed upload never
reaches format detection, a wrong-version document never reaches the XSD stage, and an XSD-invalid
document never reaches the Schematron/business-rule stages. Each `invalid/` file therefore isolates
exactly one stage.

## Corpus table

| File | Expected (ERROR/WARN rule ids) | Stage exercised |
|---|---|---|
| `valid/minimal.xml` | *(none)* — `isValid()` | full pipeline, happy path |
| `valid/b2g-full.xml` | *(none)* — `isValid()` | full pipeline, happy path |
| `invalid/malformed.xml` | `XML-01` | secure DOM parse |
| `invalid/wrong-namespace-ebi60.xml` | `FORMAT-02` | format detection |
| `invalid/xsd-missing-invoice-number.xml` | `EBI61-XSD` (≥1) | XSD schema |
| `invalid/at-b2g-01-missing-order-reference.xml` | `AT-B2G-01` | AT-B2G Schematron |
| `invalid/at-b2g-02-invalid-iban.xml` | `AT-B2G-02` | AT-B2G business rule (IBAN mod-97) |

## File-by-file

### `valid/minimal.xml`
The smallest document that clears the whole pipeline: every element and attribute the bundled
ebInterface 6.1 XSD requires, plus the `OrderReference/OrderID` (Auftragsreferenz) the Austrian B2G
Schematron demands — and nothing else (no payment block, single line, single tax item). Hand-authored
(it mirrors `TestDocuments.validEbInterface61()`, which the validator's unit tests independently prove
valid) so the corpus documents the *floor* of validity, not just the fully-populated sample.

### `valid/b2g-full.xml`
The fully-populated B2G invoice: two taxed lines (20 % and 10 %), Auftragsreferenz, Lieferantennummer,
SEPA payment details, due date and payment terms. **Provenance:** this file is the byte-for-byte
output of the real generation pipeline for `samples/invoice-b2g-sample.json`
(`InvoiceJsonReader` → `InvoiceToEbInterface61Mapper` → `EbInterface61Strategy.write`); it is the same
artifact committed as `samples/invoice-b2g-sample.ebinterface.xml`. `EndToEndGenerationTest` pins the
samples twin byte-for-byte against the live pipeline output, and separately pins this corpus copy
byte-for-byte against the samples twin — so all three are provably identical, transitively including
the live pipeline output. The five `invalid/` files below are each derived from this document
by introducing **exactly one** defect, so the difference under test is isolated to a single edit.

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
right namespace, but structurally invalid against the 6.1 XSD → one or more `EBI61-XSD` findings. This
file also guards the **stage gate**: because XSD failure short-circuits the pipeline, no `AT-B2G`
finding may appear even though the (still present) order reference and IBAN would otherwise be checked.

### `invalid/at-b2g-01-missing-order-reference.xml`
**Defect:** the whole `<OrderReference>` element is removed from `InvoiceRecipient`. The document is
XSD-valid (OrderReference is optional in the schema) but violates the Austrian federal B2G requirement
for an Auftragsreferenz → single `AT-B2G-01` from the Schematron stage.

### `invalid/at-b2g-02-invalid-iban.xml`
**Defect:** the beneficiary `IBAN` last digit is changed `…573201` → `…573202`, keeping the correct
shape and length (so it clears the XSD, which only bounds length) but failing the mod-97 checksum →
single `AT-B2G-02` from the hand-written business-rule stage.

## Regenerating `valid/b2g-full.xml`

Do not hand-edit it. On an intentional mapper/writer change, rerun `EndToEndGenerationTest` locally: it
prints the fresh pipeline output as the assertion "actual", which you copy verbatim into both
`valid/b2g-full.xml` and `samples/invoice-b2g-sample.ebinterface.xml`. Then re-derive the five
`invalid/` files by re-applying their single documented defect. See `samples/README.md` for the twin's
role in the one-time official portal check (Abnahme).
