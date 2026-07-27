# M4 hostile review — findings

**Reviewed:** `feat/m4-ubl-convert-pdf` (PR #7), `main...HEAD` = 116 files, +9659/−332.
**Date:** 2026-07-25. **Stance:** due diligence by a Viennese enterprise Java shop deciding whether
this repository is evidence of senior engineering. Local `./mvnw verify` is green (736 tests). That
is the starting point, not the verdict.

Findings are ordered by what would block a merge, not by how hard they are to fix.

---

## P1 — Blocking

### F1 · CI is red, and the milestone is being presented as done
`gh pr checks 7` → **Security scan (OWASP Dependency-Check): fail.** ENGINEERING_STANDARDS §1 DoD
item 4 requires a green pipeline *including the security scan*; CLAUDE.md says "Never mark work done
with failing CI." The worklog and README both attribute the failure to the missing `NVD_API_KEY`
secret. That attribution is wrong, and the wrong diagnosis is the more serious half of this finding.

The real cause is a Maven inheritance bug. `dependency-check-maven` is declared in the root POM's
`<profiles><profile id=security><build><plugins>`, and Maven inherits a parent's `<build><plugins>`
into every child module. Verified, not assumed:

```
./mvnw -Psecurity help:effective-pom -pl core   # → contains dependency-check-maven,
                                                #   goal `aggregate`, phase `verify`
```

So `-Psecurity verify` runs the *aggregate* goal in all nine modules. They share one CveDB; the
first execution closes it, and every execution after that NPEs per CVE
(`Cannot invoke "BasicDataSource.getConnection()" because "this.connectionPool" is null` —
hundreds of them in the job log). The POM's own comment claims the opposite: *"the aggregate report
is produced once at the root rather than nine times."* It is produced nine times.

### F2 · `POST /convert` answers **500** on a well-formed hostile document
`UblToInvoiceMapper:141` and `EbInterface61ToInvoiceMapper:131` call `Currency.getInstance(code)` on
a string taken straight from the upload. Both format adapters run with schema validation
deliberately off, so nothing constrains that string: `<cbc:DocumentCurrencyCode>BOGUS</…>` reaches
the JDK, which throws `IllegalArgumentException` → `ApiExceptionHandler.handleUnexpected` → **500**,
plus an ERROR stack trace in the application log for every such request.

Every other domain rejection on this path is an `InvariantViolationException` → 422. The currency is
the one place the code trusts a JDK factory to behave like a `core` invariant.

What makes this a P1 rather than a nitpick: **the hazard was already recognised and handled one
module over.** `InvoiceJsonReader.toCurrency` (M3) catches exactly this exception and converts it to
a 400. The two new M4 reverse mappers regressed a guard the codebase already had.

### F3 · The milestone's own acceptance criterion is not met
MILESTONES M4 asks, verbatim: *"Golden-Files für Roundtrips (ebInterface→UBL→ebInterface,
dokumentierte Abweichungen)."* What shipped is two **same-format** jqwik round trips
(`EbInterface61RoundTripPropertyTest`, `UblRoundTripPropertyTest`) plus `ConvertApiIT
.convertsUblBackToEbInterface`, which converts out and back and then asserts only that the result
contains a namespace string. Nothing pins the **cross-format** round trip against a golden file, and
the deviations it produces are documented nowhere.

This is the one criterion that proves the two mappers agree about the same invoice. Its absence is
why F6 below went unnoticed.

---

## P2 — Correctness, architecture, resource safety

### F4 · The three new modules are outside the only cross-module ArchUnit rule set
`app/ArchitectureTest` is unchanged in this milestone. `libModulesNeverDependOnApp()` still lists
`core, mapping, validation, formats-ebinterface`; `moduleClassesAreImported()`'s vacuity guard lists
the same four. `formats-api`, `formats-ubl` and `rendering` are in neither. The class Javadoc still
says "Every lib module (core, mapping, validation, formats-ebinterface)".

CLAUDE.md: "ArchUnit rules enforce module boundaries." For a third of the tree, as of this
milestone, they do not.

### F5 · A test whose name and comment claim an assertion the body does not make
`UblEndToEndGenerationTest.corpusCopyMatchesTheSamplesTwinModuloTheirDifferentSourceInvoices` — the
inline comment above it reads *"The corpus copy and the samples twin are the same bytes, so neither
can drift alone."* The body compares the corpus file against **nothing**. It asserts the file starts
with an XML declaration, contains a namespace, and is valid. Three assertions that would pass for
any Peppol-valid UBL invoice on earth.

A reviewer reading the test list sees a byte-for-byte drift guard. There isn't one.

### F6 · Silent data loss when reading a *foreign* UBL document
`UblToInvoiceMapper:159-166` takes `Delivery/ActualDeliveryDate` if present and otherwise
`InvoicePeriod`. When a document carries **both** — legal UBL, and common in the wild — the service
period is dropped with no `CONV-01` note.

The same `getFirst()`-and-forget pattern silently discards: a second `cac:TaxTotal` (the
tax-accounting-currency total Peppol allows), a second `cac:PaymentMeans`, additional
`PaymentTerms/Note` entries, and every `PartyIdentification` past the first.

For documents this platform *wrote* none of this can happen — core enforces mutual exclusion and
writes one of each. But `POST /convert` exists precisely to eat documents this platform did not
write. The entire premise of the conversion report is that nothing disappears without being named.

### F7 · Dead code whose Javadoc claims a role it does not have
`TargetFormat.id()` documents itself as *"The stable identifier this format is reported as in a
`ConversionReport`"*. It has **zero call sites** in main or test. `ConversionService` builds the
report from `ConversionFormat.id()` instead — `"ebinterface"`/`"ubl"`, not
`"ebinterface-6.1"`/`"ubl-2.1"`. DoD §1 forbids dead paths.

The knock-on is worse than the dead method. `ConversionReport`'s `@param` docs advertise
`ebinterface-6.1` / `ubl-invoice-2.1`; `ConversionReportTest` constructs it with those strings —
**values production never produces**. The unit test and the shipped wire contract disagree about the
value space, so the test cannot catch a regression in it. And a single `POST /convert` response
speaks two vocabularies at once: `conversion.sourceFormat = "ebinterface"` next to
`report.sourceFormat = "ubl-invoice-2.1"`.

### F8 · A database transaction held open across a full XSLT validation run
`ConversionService.convert` is `@Transactional`. Inside it: parse the upload, map to canonical, map
to target, serialise, and run **the complete Peppol VES** — XSD plus two Schematron/XSLT layers,
seconds of CPU on a real document. The only database work is one audit INSERT on the last line, and
`AuditService.record` is already `@Transactional` in its own right.

Every in-flight conversion therefore pins a HikariCP connection for its entire duration. The pool is
the first thing to fall over under concurrency, and it will fall over for a reason that has nothing
to do with the database.

### F9 · No rate limit on the most expensive endpoint in the platform
`RateLimitFilter` matches `POST /api/v1/validate` and nothing else. A conversion costs strictly more
than a validation — read, two mappings, a write, *and* a full Peppol VES run — and is reachable by
any tenant holding an API key. The 2 MB cap bounds one request; it does not bound a request rate.

### F10 · The conversion path's XXE safety is incidental and untested
`ConversionService.read` is safe today only because `validator.detectFormat` (which uses
`SecureXml`, rejecting any `DOCTYPE`) runs *before* the raw bytes reach a JAXB marshaller: a DOCTYPE
document detects as `UNKNOWN` and is refused with 400. Nothing states that this ordering is
load-bearing, and no test pins it. Reorder those two statements — a plausible refactor, since one
looks like a mere sanity check — and untrusted XML goes straight into the adapter's own parser.

---

## P3 — Documentation that misleads the reader

### F11 · `InvoiceValidator`'s Javadoc says the class supersedes itself
`InvoiceValidator:44`: *"This class supersedes `InvoiceValidator`, which remains as the
ebInterface-only entry point its own tests and the M2 corpus already use."* The class **is**
`InvoiceValidator`. The class it superseded, `EbInterface61Validator`, was deleted in this
milestone — it does not remain, and nothing uses it.

### F12 · Three further live references to the class M4 deleted
- `app/src/main/resources/application.yml:25` — sends a reader to `EbInterface61Validator.MAX_INPUT_BYTES`.
- `validation/src/test/resources/corpus/README.md:4` — a file **added in M4**, naming the class M4 deleted.
- `docs/adr/0004-…:122` — the rule-id reference table (ADR prose is historical and stays; a lookup table is not).

### F13 · The published OpenAPI contract still says `/validate` is ebInterface-only
`ValidationController`'s `@Operation(summary = "Validate an ebInterface 6.1 document")`, its
`@Parameter(description = "The ebInterface 6.1 document to validate.")` and its class Javadoc all
predate M4. The endpoint now auto-detects and validates UBL too — README and SPEC say so; the
generated OpenAPI document, which is what an integrator actually reads, does not.

### F14 · A bean named for a format it is no longer specific to
`InvoicePipelineConfig.ebInterface61Validator()` returns the format-dispatching `InvoiceValidator`.
The Spring bean id is literally `ebInterface61Validator`.

### F15 · The NVD cache comment describes a design that was not implemented
The CI comment claims the cache is *"keyed on the week … guaranteeing a full refresh at least
weekly"*. The key is `nvd-${{ runner.os }}-${{ github.run_id }}` — unique per run. The primary key
therefore never hits, `restore-keys` always restores the newest prefix match, a fresh cache entry is
written on **every** run, and there is no weekly refresh whatsoever. A run that dies mid-sync (which
is exactly what happens today) persists its half-written NVD database for the next run to inherit.

Separately, the `<skipProvidedScope>false</skipProvidedScope>` element carries a comment about the
aggregate report — text belonging to a different setting.

### F16 · Style inconsistency google-java-format cannot catch
`InvoiceToUblMapper` imports 27 types at the top, then writes `java.time.LocalDate` (l. 282, 365),
`java.util.Optional` (l. 364, 368, 385) and two `oasis.…commonbasiccomponents_21` types (l. 391,
429) fully qualified inline. In a file whose whole value proposition is legibility.

### F17 · SPEC §2 still describes a module that was not built that way
The module tree lists `rendering/  # invoice → PDF / HTML print view`. ADR-0008 decided PDF only;
README was updated to match. SPEC was not.

---

## What is genuinely good (stated so the criticism above is calibrated)

- Consuming the OpenPeppol rule set **unmodified at a pinned version**, with
  `isPinnedRuleSetRegistered()` as a loud tripwire for a dependency bump that drops the pin, is the
  right call and is documented honestly — including the admission that the German message is a
  German frame around English rule text rather than a faked translation.
- `UblRootElement` exists because JAXB's unmarshal-by-declared-type would silently accept a
  `CreditNote` as an `InvoiceType`, and the Javadoc says that was *measured, not assumed*. That is
  the difference between a defensive check and a superstition.
- Refusing to synthesise BT-34/BT-49 from a VAT id, and reporting the absence instead, is the
  correct and harder choice.
- `PrintableText` closes a reachable crash class (`showText` throws on unencodable glyphs) with a
  visible `?` rather than silent removal.
- Conversion through the canonical model, with `derive-don't-trust` producing `CONV-04` instead of
  adopting a foreign total, is the right architecture and the reason F6 is a gap rather than a
  rewrite.

---

## Resolution — all 17 closed, same branch

Fixed test-first throughout. `./mvnw verify` green; 736 → 764 tests; every JaCoCo and PIT gate met.

| # | Outcome |
|---|---|
| F1 | `<inherited>false</inherited>` on the plugin. Measured: the `aggregate` goal bound in **10** reactor projects before, **1** after. CI now asserts the binding count with the scan skipped (seconds, no NVD data), and the cache key is genuinely week-scoped. Missing `NVD_API_KEY` now **skips** the scan with a warning annotation + job summary instead of failing — a permanently red stage teaches people to ignore red. |
| F2 | `mapping.internal.Currencies.parseOrDefault`, used by both reverse mappers; absent code still defaults to EUR, an unusable one is an `InvariantViolationException` → 422 with a `Texts.safeEcho`-bounded message. Proven end to end in `ConvertApiIT`. |
| F3 | `CrossFormatRoundTripTest` — 6 tests. Every corpus ebInterface document returns **byte-for-byte identical** through UBL; the UBL→ebInterface→UBL direction asserts it loses the endpoints and nothing else. |
| **F3a** | *Found by writing F3.* The exemption `Comment` grew by one category code per conversion, unboundedly. The reverse mapper now parses the structure the forward mapper writes, so BT-121 survives too. New jqwik property `reEmittingAReadDocumentReproducesItExactly` closes the whole blind spot, not just this bug. |
| F4 | One shared `LIB_MODULE_PACKAGES`, plus `everyLibModuleIsListed()` so an unlisted module fails loudly — verified by unlisting `rendering` and watching it fail. |
| F5 | Rewritten as two real byte-for-byte drift guards against the corpus entries' actual generator, plus a separate Peppol-clean assertion. |
| F6 | Five `only…` helpers emit `CONV-01` for every discarded repeat; the both-delivery-date-and-period case is reported explicitly. Six new tests, including one asserting our own output still produces none. |
| F7 | `TargetFormat.id()`, `ConversionReport.plus()` and `lossless()` deleted; `ConversionReportTest` now uses the values production emits; the two-vocabulary response is documented where it is built. |
| F8 | `@Transactional` removed from `ConversionService.convert`. |
| F9 | `/convert` rate-limited per credential, authenticated callers included; separate bucket from `/validate`; 3 new unit tests + config + `.env.example`. |
| F10 | `neverResolvesAnExternalEntityInAnUploadedDocument`, and the load-bearing ordering is now stated in `ConversionService.read`'s Javadoc. |
| F11–F17 | Self-referential Javadoc corrected; all three live references to the deleted class fixed; OpenAPI now describes `/validate` as dual-format; bean renamed `invoiceValidator`; NVD cache comment matches the code; inline FQNs imported; SPEC's `rendering` row corrected. |

**Outstanding owner action:** add the `NVD_API_KEY` repository secret. Until then the security
stage skips loudly; adding it makes it a live gate with no workflow change.
