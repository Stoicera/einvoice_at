# ADR-0004 — Validation pipeline shape and XSD finding messages

Date: 2026-07-24 · Status: accepted

## Kontext

M2 introduces the `validation` module: it takes untrusted upload bytes and produces a
`ValidationReport` of German-first `Finding`s. The full pipeline is format detection → XSD →
Schematron → Austrian B2G business rules; this task (M2 / Task 6) lays the skeleton and the XSD
stage, with Schematron and the business rules following in later tasks. Two decisions need pinning
down now because Tasks 7–10 build on them and because they touch security and the
"every finding has a German message" rule.

The XSD stage runs the bundled ebInterface 6.1 validation-executor-set (VES) from
`phive-rules-ebinterface` — an XSD-only VES — via phive. The underlying schema violations are
produced by the Xerces parser, and their message text is not ours to author: it is the parser's own
diagnostic (e.g. `cvc-complex-type.2.4.a: ...`).

## Entscheidung

1. **Parsing is hardened at the boundary, once, in `SecureXml`.** A namespace-aware
   `DocumentBuilderFactory` with `FEATURE_SECURE_PROCESSING` on, `disallow-doctype-decl` on, and
   external general/parameter entities and external-DTD loading off. A document that merely declares
   a `DOCTYPE` is rejected — this alone closes XXE and entity-expansion (billion-laughs). Malformed
   bytes or a forbidden `DOCTYPE` are the domain, not exceptions: `parse` returns `Optional.empty()`,
   which the pipeline turns into a single `XML-01` error finding (Engineering Standards §4).

2. **Fixed pipeline order with early stops.** Secure DOM parse (`XML-01`) → format detection
   (`FORMAT-01` unknown namespace / `FORMAT-02` recognised-but-unsupported ebInterface version) → XSD
   (`EBI61-XSD`) → Schematron + business rules (later tasks). Each stage stops the pipeline when
   continuing is meaningless: a document that is not well-formed cannot be format-detected; one whose
   format is unknown or unsupported cannot be schema-checked; and a structurally XSD-invalid document
   cannot be meaningfully checked by Schematron or business rules, which assume a valid tree. The
   report's `sourceFormat` is `"ebinterface-6.1"` once 6.1 is detected, `"unknown"` otherwise; the
   `profile` is always `at-b2g` — this validator only knows the Austrian B2G profile. The facade
   never throws: `null` input is treated as empty and yields `XML-01`.

3. **XSD finding text is the parser's, verbatim in each locale, behind a German lead-in that is
   ours.** Every `EBI61-XSD` finding reads `messageDe = "Das Dokument verletzt das
   ebInterface-6.1-Schema: " + <German parser text>` and `messageEn = <English parser text>`. We
   honestly do **not** translate the technical detail; we ask Xerces for its own message in each
   language. Getting a genuine English message is not a second `getErrorText(Locale.ENGLISH)` call on
   the same `IError` — empirically, Xerces bakes its diagnostic into the `SAXParseException` message
   at the moment the document is validated, using the `Locale` that particular validation run was
   asked for, and phive wraps that already-rendered string in an `IError` whose `getErrorText(Locale)`
   returns the same text regardless of the locale argument passed *afterwards*. So `XsdValidationStage`
   runs the XSD executor twice on the same DOM — once with `Locale.GERMAN`, once with
   `Locale.ENGLISH` — and pairs the two resulting error lists by position; both runs validate the
   identical DOM against the identical schema, so they report the same violations in the same order
   and only the message text differs. If the English run's text is missing or blank for some error,
   the fallback is that error's own `getAsStringLocaleIndepdent()` (helger's real, misspelled method
   name) — never the German text, so a fallback can never reintroduce the bug this design fixes. When
   the parser hands back no usable text at all in a given locale, a fixed German/English fallback is
   used so the `Finding` non-blank invariants hold. The location phive attaches to a DOM-sourced error
   is the source name (`upload.xml`), not a line/column, because a DOM carries no positional
   information; we pass it through unchanged (from the German run).

4. **The phive registry is built once, lazily.** `EbInterfaceValidation.initEbInterface` populates a
   `ValidationExecutorSetRegistry` that is expensive to construct; it lives in a lazy holder class so
   the cost is paid on first validation, not at class load, and the JVM class-init lock makes
   publication safe.

## Konsequenzen

- Tasks 7–10 consume these as fixed contracts: the stage order, the stop rules, the rule ids
  (`XML-01`, `FORMAT-01`, `FORMAT-02`, `EBI61-XSD`, and the later `AT-B2G-*`), the `sourceFormat`
  values and `PROFILE_AT_B2G`. Changing any of them is a breaking change to those tasks.
- `messageDe` findings whose technical detail is itself English (Xerces has no German translation for
  that particular built-in message, so the `Locale.GERMAN` run falls back to its own default) are a
  known, accepted gap, not a bug. If product wants fully-German XSD detail later, the fix is a curated
  German message map keyed by the Xerces error code (e.g. `cvc-complex-type.2.4.a`), added as a
  separate concern — not by hand-editing parser output at the call site. `messageEn` does not have
  this gap: it is always sourced from a genuine `Locale.ENGLISH` validation run.
- The XSD stage now validates each document twice (once per locale) instead of once. For an XSD-only
  VES on typical invoice-sized documents this is cheap; if it ever shows up in profiling, the fix is
  to cache the German and English `IError` lists per validation-executor pass rather than dropping
  back to a single-locale fetch (which reintroduces the bug this ADR update fixes).
- `core` stays the single source of validity: the facade asks the freshly built `ValidationReport`
  whether it `isValid()` to decide whether to stop after XSD, rather than re-implementing the
  ERROR-severity check.
- The XSD stage depends only on the securely parsed DOM, so the same hardening protects every stage
  downstream; no stage re-parses untrusted bytes.
