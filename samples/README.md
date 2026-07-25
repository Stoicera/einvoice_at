# samples/

Reference documents for `einvoice-at`, committed at the repository root (not inside any Maven
module) so they can be read, linked, and consumed by more than one module without a source/test
dependency between them.

## `invoice-b2g-sample.json`

The canonical example of the **canonical-invoice JSON** shape: a strict, boundary-facing JSON
representation of the `core` domain model (`com.stoicera.einvoice.core.invoice.Invoice`), read by
`com.stoicera.einvoice.mapping.json.InvoiceJsonReader` (module `mapping`, Milestone 2 / Task 5).

It describes a fully populated Austrian B2G invoice — two taxed lines (20 % and 10 %), an
Auftragsreferenz, a Lieferantennummer, a delivery date (BT-72), a Biller contact email, SEPA payment
details and payment terms — so that every field the reader supports appears at least once (except
`servicePeriod`, mutually exclusive with the `deliveryDate` already present here; Task 3's fixtures
exercise that arm). `mapping/src/test/java/.../mapping/Fixtures.java`'s
`jsonSampleB2gInvoice()` builds the identical invoice by hand via `Invoice.builder()`;
`InvoiceJsonReaderTest.parsesSampleFileIntoExpectedInvoice()` asserts the two are record-equal, so
this file and that fixture must be kept in lockstep if either ever changes.

An ebInterface 6.1 XML twin of this same invoice (`invoice-b2g-sample.ebinterface.xml`) is added by
the golden-file corpus task (M2 Task 9): `InvoiceJsonReader` → `InvoiceToEbInterface61Mapper` →
`EbInterface61Strategy.write` is asserted to reproduce that committed XML byte-for-byte
(line-ending-normalized), so the JSON sample is also the input fixture for the milestone's
end-to-end acceptance test.

## `invoice-b2g-sample.ebinterface.xml`

The ebInterface 6.1 XML twin of `invoice-b2g-sample.json`: exactly what the generation pipeline
(`InvoiceJsonReader` → `InvoiceToEbInterface61Mapper` → `EbInterface61Strategy.write`) writes for that
JSON, committed verbatim. `validation`'s `EndToEndGenerationTest` regenerates it in memory on every
run and asserts byte-for-byte equality (line endings normalized: CR stripped, trailing newline
ignored), so this file provably *is* the pipeline's own output and cannot silently drift from the
mapper. The identical bytes also serve as the `valid/b2g-full.xml` entry of the validation golden-file
corpus (`validation/src/test/resources/corpus/`).

**Milestone Abnahme (owner action).** This twin is the artifact uploaded to the official ebInterface
portal check (<https://formvalidation.brz.gv.at/> / the WKO ebInterface validator) to confirm the
platform's output passes an authoritative external validator, not only our own. That is a manual
owner step (Sebastian); the automated acceptance lives in `EndToEndGenerationTest`.

**Status: PASSED, re-confirmed 2026-07-25 (owner-run) on the bytes committed here** —
*"Diese Datei ist gültig gemäß ebInterface Standard ebInterface 6.1"*.

Abnahme history, so the claim above is traceable to the bytes it was made about:

| Date | Verdict | Bytes checked |
|---|---|---|
| 2026-07-24 | passed | pre-M2-fix-wave twin (`Country` element text echoed the ISO code) |
| **2026-07-25** | **passed** | **current twin** — after the M2 fix wave's German `Country` display name (finding A6) and M3 Task 2's added `Delivery/Date` (BT-72) and `Biller/Address/Email` |

Re-run the check whenever the twin's bytes change again — that is, whenever
`EndToEndGenerationTest.committedTwinMatchesTheFreshlyGeneratedXml` fails on an intentional mapper or
writer change and this file is regenerated (see **Regeneration** below).

**Regeneration.** Do not hand-edit this file. On an *intentional* mapper or writer change,
`EndToEndGenerationTest.committedTwinMatchesTheFreshlyGeneratedXml` fails and reports the fresh
pipeline output as its "actual" value; copy that verbatim over this file (and the corpus
`valid/b2g-full.xml`), re-derive the corpus `invalid/*` files from their single documented defects,
and re-run the portal check. There is no generator flag or `--generate` mode — the acceptance test is
the single source of the expected bytes.

### JSON field reference

| Field | Type | Required | Canonical target | Notes |
|---|---|---|---|---|
| `invoiceNumber` | string | yes | `Invoice.invoiceNumber` (BT-1) | |
| `type` | string | yes | `Invoice.type` (BT-3) | `"INVOICE"` or `"CREDIT_NOTE"` — any other value is rejected. |
| `issueDate` | string | yes | `Invoice.issueDate` (BT-2) | ISO-8601, `yyyy-MM-dd`. |
| `dueDate` | string | no | `Invoice.dueDate` (BT-9) | ISO-8601, `yyyy-MM-dd`. |
| `deliveryDate` | string | no | `Invoice.deliveryDate` (BT-72) | ISO-8601, `yyyy-MM-dd`. Mutually exclusive with `servicePeriod` (§ 11 Abs 1 Z 4 UStG: day of delivery *or* service period, never both); `core` rejects a document carrying both. |
| `servicePeriod` | object | no | `Invoice.servicePeriod` (BG-14) | `{"from": "...", "to": "..."}`, both ISO-8601. Mutually exclusive with `deliveryDate` (see above). |
| `currency` | string | yes | `Invoice.currency` (BT-5) | ISO 4217, e.g. `"EUR"`. |
| `orderReference` | string | no | `Invoice.orderReference` (BT-13, Auftragsreferenz) | Required by the Austrian federal B2G profile (enforced by the `validation` module, not here). |
| `supplierNumber` | string | no | `Invoice.supplierNumber` (Lieferantennummer) | |
| `seller` | object | yes | `Invoice.seller` (BG-4) | `name`, `vatId`, `address`, `email`. |
| `buyer` | object | yes | `Invoice.buyer` (BG-7) | `name`, `vatId`, `address`, `email`. |
| `seller.address` / `buyer.address` | object | yes | `Address` (BG-5 / BG-8) | `street`, `city`, `postalCode`, `countryCode` (ISO 3166-1 alpha-2). |
| `seller.email` / `buyer.email` | string | no | `Party.email` | Business contact email; maps to `Address/Email` in the ebInterface 6.1 output. Omitted (no `Email` element) when the party carries none. |
| `lines` | array | yes, ≥1 entry | `Invoice.lines` (BG-25) | See below. |
| `lines[].id` | string | yes | `InvoiceLine.id` | |
| `lines[].description` | string | yes | `InvoiceLine.description` | |
| `lines[].quantity` | **string** | yes | `InvoiceLine.quantity` | Decimal; see "amounts are strings" below. |
| `lines[].unitCode` | string | yes | `InvoiceLine.unitCode` | UN/ECE Recommendation 20, e.g. `HUR`, `C62`. |
| `lines[].unitPrice` | **string** | yes | `InvoiceLine.unitPrice` | Decimal. |
| `lines[].vatCategory` | string | yes | `VatRate.category` (part of BG-30) | One of `STANDARD`, `ZERO_RATED`, `REVERSE_CHARGE`, `EXEMPT`. |
| `lines[].vatPercent` | **string** | yes | `VatRate.percentage` | Decimal, e.g. `"20"`. |
| `paymentMeans` | object | no | `Invoice.paymentMeans` (BG-17) | `iban` (checksum-validated), `bic` (optional). Whole object omitted when there is no SEPA payment info. **Provenance:** the sample's `AT611904300234573201` and its Bank-Austria-format BIC `BKAUATWW` are canonical ebInterface test values — a checksum-valid IBAN chosen so the fixture clears the `AT-B2G-02` mod-97 check — not a real account or a real bank customer. |
| `paymentTerms` | string | no | `Invoice.paymentTerms` (BT-20) | Free text. |
| `exemptionReasons` | array | no | wired via `Invoice.Builder#exemptionReason` (BT-120/BT-121) | `category` (`VatCategory`), `code` and/or `text`. One entry per VAT category that needs a reason (`REVERSE_CHARGE`, `EXEMPT`); `REVERSE_CHARGE` gets a default reason from the builder when omitted. |

### Amounts and quantities are JSON strings — always

`lines[].quantity`, `lines[].unitPrice` and `lines[].vatPercent` are **JSON strings**, not JSON
numbers, e.g. `"unitPrice": "120.00"`, never `"unitPrice": 120.00`. This is deliberate boundary
strictness, not an oversight:

- A JSON number literal is an IEEE-754-adjacent construct with no fixed decimal semantics; parsing
  it straight into `BigDecimal` risks silently accepting a value the source system never intended
  (trailing-zero loss, scientific notation, etc.).
- A quoted decimal string has exactly the textual shape the sender wrote, parsed by
  `new BigDecimal(String)` with no intermediate floating-point representation.

`InvoiceJsonReader` enforces this: a numeric JSON node in any of these three fields is **rejected**
with an `InvoiceJsonException` naming the offending field (`lines[<index>].unitPrice`, etc.), not
silently coerced. All other fields in the shape above are ordinary JSON strings/objects/arrays;
`InvoiceJsonReader` also rejects unknown properties anywhere in the document
(`FAIL_ON_UNKNOWN_PROPERTIES`).

A JSON document that is well-formed per this shape but describes an invoice that violates a domain
invariant (a checksum-invalid IBAN, a blank invoice number, mismatched VAT breakdown) is rejected by
`core` itself with `InvariantViolationException` — `InvoiceJsonReader` lets that propagate untouched;
see its Javadoc for the full "two voices" rationale.
