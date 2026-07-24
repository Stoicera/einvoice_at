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

5. **Java business rules operate on the parsed tree, unmarshalled from the hardened DOM, and are
   deliberately narrow (M2 / Task 8).** Rules that read more naturally as Java than as XPath live in
   `BusinessRuleStage`, gated exactly like Schematron: they run only on an XSD-valid document. The
   first such rule is `AT-B2G-02` — every `IBAN` present under
   `PaymentMethod/UniversalBankTransaction/BeneficiaryAccount` must pass the core `Iban` mod-97
   checksum (the 6.1 XSD only bounds an IBAN's length, so a transposed digit is schema-clean). The
   rule is intentionally about IBANs that are *present*: a missing payment method, a non-transfer
   payment method, or a beneficiary account without an `IBAN` element is **not** a finding here.
   Payment-completeness for B2G (is an IBAN *required*?) is a separate, later concern, not smuggled
   into a checksum rule. The finding never echoes the IBAN — it is bank-account PII, so `Iban`
   exposes only a throwing factory and the finding names the account by its 1-based position in both
   the message (`IBAN im Empfängerkonto <n> …`) and the location
   (`/Invoice/PaymentMethod/UniversalBankTransaction/BeneficiaryAccount[<n>]/IBAN`). To honour the
   "parse untrusted bytes exactly once" property (see Konsequenzen), `ValidationContext.ebiInvoice()`
   unmarshals the tree from the already-hardened `dom()` rather than re-reading the raw bytes: the
   formats strategy gained a `read(org.w3c.dom.Node)` overload alongside `read(byte[])`, sharing one
   lenient error-collection body, so the JAXB unmarshal reuses the XXE-safe DOM instead of opening a
   second, unhardened parse.

## Konsequenzen

- Tasks 7–10 consume these as fixed contracts: the stage order, the stop rules, the rule ids
  (`XML-01`, `FORMAT-01`, `FORMAT-02`, `EBI61-XSD`, and the later `AT-B2G-*`), the `sourceFormat`
  values and `PROFILE_AT_B2G`. Changing any of them is a breaking change to those tasks. The report
  preserves pipeline order, so findings from a schema-valid document appear Schematron-first
  (`AT-B2G-01`) then business-rule (`AT-B2G-02`); the corpus and CLI tasks assert this order and the
  exact `AT-B2G-02` message/location text.
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
  downstream; no stage re-parses untrusted bytes. This is now literally true end to end: the
  business-rule stage reads the JAXB tree via `ValidationContext.ebiInvoice()`, which unmarshals from
  the hardened `dom()` through the formats `read(Node)` overload — the untrusted upload is parsed
  exactly once, by `SecureXml`. The strategy keeps `read(byte[])` for callers outside the pipeline
  (e.g. the mapping module) that legitimately start from bytes.
