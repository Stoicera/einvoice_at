# Next session — outstanding findings

**Read this first.** It is the work queue for the next session, and it is written so you need nothing
else to start.

**Where these came from.** A hostile due-diligence pass on **2026-08-07** against commit `14b7227`:
seven parallel reviewers (architecture, tests, security, documentation truthfulness, domain/mapping
correctness, operations, plus a Peppol readiness plan), the top findings adversarially re-attacked by
a second reviewer whose job was to kill them. Afterwards **five PRs merged** (#25 #26 #27 #28 #29),
so every finding below was **re-verified against `main` after those merges** — each one carries what
was actually read on current `main`, not what the original audit said.

**Coverage, stated honestly.** 30 findings survived triage. **12 were re-verified individually**
(11 still real, 1 already fixed). The remaining **18 were not re-verified** — they are listed in
§Unverified and must be re-checked against `main` before you act on them, because five PRs have
landed since they were written.

---

## Start here

1. **`roundtrip-universal-claim-false`** — the only P1 that is a *capability* problem rather than a
   wording problem, and the one a reviewer would find by running the corpus. Everything else in P1 is
   text.
2. **`release-state-stale-in-readfirst-docs`** — half an hour, and it removes a falsehood from the
   two documents `CLAUDE.md` orders a reader to open first.
3. **`docker-publish-without-tests`** — a supply-chain gap: the image production runs is published
   without waiting for the test job. Cheap to fix, embarrassing to be asked about.
4. **`backup-cron-discards-exit-code`** — **this one is a defect in what was shipped on 2026-08-07**,
   so it should not be allowed to age.
5. Then the mapping trio (`ebi-reduction-prepaid-rounding-unread`, `ebi-reverse-mapper-drops-repeats`,
   `ubl-reverse-mapper-unread-bts`) — they are one theme and share a test idiom, so do them together.

---

## Findings

### 1. The universal round-trip claim is false, and the excluded document is one the validator calls valid

**P1 · Effort M · `README.md:388`, `validation/src/test/java/com/stoicera/einvoice/validation/CrossFormatRoundTripTest.java:87-93`**

README says **every** valid ebInterface document in the corpus survives a trip through UBL
byte-for-byte. `@ValueSource` lists **three** files; `corpus/valid/` holds **four** ebInterface
documents. The fourth, `minimal.xml`, is excluded because it cannot be converted at all — no
`Biller/Address/Street` trips `Address.java:26`, no `InvoiceRecipient/Address` trips
`Invoice.java:94-95`. So for the same bytes, `POST /api/v1/validate` answers *valid* and
`POST /api/v1/convert` answers **422** — on the document `corpus/README.md:46` calls "the floor of
validity". The claim is repeated in three places (README, the test's own Javadoc, corpus README).

**Evidence on main today:** untouched by the five merged PRs (`git log 14b7227..HEAD` over those
paths returns only the Peppol version line in `corpus/README.md`).

**Write this test first.** In `CrossFormatRoundTripTest`, replace the hand-written
`ebInterfaceSurvivesATripThroughUblUnchanged` (lines 87-103) with
`everyValidEbInterfaceCorpusFileSurvivesATripThroughUbl(String resource)`, driven by a
`@MethodSource` that filters `CorpusTest.corpus()` for non-UBL entries under `corpus/valid/`.
Delete the `@ValueSource` — do not keep both; the whole point is that the two lists can no longer
diverge. It goes red on `minimal.xml`.

**Then fix — this is a design decision, make it deliberately.** Either relax `Address` to EN 16931
BG-5 (country code only) and move street/ZIP/city into an AT-B2G profile rule, **or** keep the
strictness and have the conversion refuse with a domain-level finding that names the missing BT.
Either way, delete the universal claim from all three documents.

---

### 2. Two read-first documents say the live instance and the tag are still outstanding

**P1 · Effort S · `README.md:45`, `docs/MILESTONES.md:33,40,43`**

`README.md:45` ends the Deutsche Kurzfassung with "bis auf die Live-Instanz und das `v0.1.0`-Tag,
die beide eine Maschine bzw. einen Knopfdruck brauchen" — present tense, no date. MILESTONES heads M6
"abgeschlossen bis auf zwei Owner-Schritte" and says **nicht provisioniert** / **nicht getaggt**.
Both shipped on 2026-08-06. `git tag -l` settles it in one command, which is exactly why a reviewer
would try it.

Note the English status block at `README.md:10-22` does **not** make this claim — only the German
one, so a German-speaking Austrian reviewer is the one who sees it.

**Evidence on main today:** `git diff 14b7227..HEAD -- README.md docs/MILESTONES.md` is one line
(the Peppol pin); MILESTONES is byte-identical.

**Write this test first.** New plain JUnit 5 class (no Spring, no Testcontainers, so surefire runs
it in milliseconds): `app/src/test/java/com/stoicera/einvoice/app/docs/ReleaseStateDocumentationTest.java`,
method `readFirstDocsDoNotCallClosedOwnerStepsOutstanding()`. Read `README.md` and
`docs/MILESTONES.md` from the repo root and assert they contain none of `nicht getaggt`,
`nicht provisioniert`, `bis auf die Live-Instanz`.

**Then fix** the two documents and link the live URL near `README.md:6` — it is currently nowhere in
the README.

---

### 3. The image production runs is published without waiting for the tests

**P1 · Effort S · `.github/workflows/ci.yml:324`**

The `docker:` job has **no `needs:`** key at all — confirmed by grep: the only `needs:` in the whole
451-line file is at line 429 (`deploy: needs: [build, docker]`). So `ghcr.io/stoicera/einvoice_at:main`
is built and pushed in parallel with the test suite. `deploy` does gate on `build`, so a red build
does not *deploy* — but the tagged image production tracks has already moved, and anyone pulling
`:main` between the two gets an untested image.

**Write this test first.** New `app/src/test/java/com/stoicera/einvoice/app/CiPipelineOrderTest.java`
(same package as the existing `ArchitectureTest`, this repo's home for repo-wide invariants). Parse
`.github/workflows/ci.yml` and assert the `docker` job declares a `needs:` that includes the test
job. No test anywhere currently reads a workflow file — verified.

**Then fix:** add `needs: [build]` to the `docker` job.

---

### 4. The backup cron throws away the exit code the backup script exists to produce

**P1 · Effort M · `docs/deployment.md:1059` and `/etc/cron.d/einvoice-backup` on the production VPS**

**Shipped on 2026-08-07 — this is our own.** Both halves of the cron line end in
`>> /var/log/einvoice-backup.log 2>&1`. That is cron's silencing idiom: **cron mails on output, not
on exit status.** `backup.sh` was written so that a truncated dump exits non-zero ("EXIT CODE IS THE
POINT" — its own header), and the cron line then discards exactly that signal. A failing nightly
backup is invisible until somebody opens the log.

`offsite-sync.sh` inherits the same problem once it is armed, which matters more, because
`OFFSITE_REQUIRED=1` exists precisely to turn a silent skip into a failure.

**Write this test first.** New plain JUnit 5 `app/src/test/java/com/stoicera/einvoice/app/ops/BackupCronRunbookTest.java`
(precedent for reading repo files by relative path: `EndToEndGenerationTest.java:39` uses
`Path.of("..", "samples")`). Extract the fenced ```cron block from `docs/deployment.md` and assert
the documented line does not end both halves with a bare `2>&1` redirect — i.e. that a failure still
reaches somebody.

**Then fix:** keep the log, but let failure escape — e.g. `|| echo "einvoice backup FAILED" >&2`
after each half, or wrap in a small `nightly.sh` that tees the log and exits non-zero. Then apply the
same change on the production box and say so in `deployment.md` §10.

---

### 5. One shared `phive-rules.version` feeds two artefacts whose upstream projects have split

**P2 · Effort S · `pom.xml:57`, used at `:206-208` and `:211-213`**

`phive-rules-ebinterface` has moved to the separate `phive-rules-foundations` project: Maven Central
metadata (fetched, not recalled) runs `… 4.4.1, 4.4.2, 5.0.0, 5.0.1` with **no 4.5.x**, while
`phive-rules-peppol` is at `4.5.2` with **no 5.x**. One property cannot satisfy both any more, so the
next Dependabot bump past 4.4.2 makes the build unresolvable — and the failure will look like a
mystery rather than an upstream reorganisation.

**Write this test first.** In `PeppolValidationStageTest` (which already owns the
"this pin must not go stale silently" family and already imports `Files`/`Path`/`Pattern`), add
`theTwoPhiveRuleArtefactsDoNotShareOneVersionProperty()`: read the parent `pom.xml` and assert the
two dependencies resolve through **different** properties.

**Then fix:** split into `phive-rules-peppol.version` and `phive-rules-ebinterface.version` with a
comment recording the upstream split, and correct the now-misleading "Parent-managed (phive-rules
4.4.1)" comments at `validation/pom.xml:70` and `:79`.

---

### 6. Discounts, prepayments and rounding are read by nothing, so a converted invoice can demand more money

**P2 · Effort M · `mapping/…/ebinterface/EbInterface61ToInvoiceMapper.java:412`**

`ReductionAndSurchargeDetails`, `ReductionAndSurchargeListLineItemDetails`, `PrepaidAmount`,
`RoundingAmount` and `BelowTheLineItem` are read nowhere in the repository. A 10 % Rabatt with
`PayableAmount` 1080.00 converts to UBL `cbc:PayableAmount` 1200.00.

It is **not silent** — `totalsDeviations` always fires CONV-04 at ERROR — but the report blames the
filer's arithmetic instead of naming the element the platform cannot represent. That mitigation is
why this is P2 rather than P1. What is *not* mitigated: `ConvertController.java:51` tells API readers
"Converts through the canonical model, **so no amount can change in transit**", unqualified and false.

**Write this test first.** In `EbInterface61ToInvoiceMapperTest` (its Javadoc says it exists for
"reading *foreign* ebInterface documents", which is exactly this): take
`FORWARD.map(Fixtures.sampleB2gInvoice())`, add a line-level Reduktion, and assert the notes carry a
CONV-01 located at `…/ReductionAndSurchargeListLineItemDetails` and **no** line-level CONV-04.

**Then fix:** add an unrepresentable-element sweep before `lines(…)`, and correct or qualify the
OpenAPI sentence in the same commit.

---

### 7. The ebInterface reverse mapper drops repeatable elements without a note

**P2 · Effort M · `mapping/…/ebinterface/EbInterface61ToInvoiceMapper.java:375`**

Four silent-drop sites against genuinely repeatable elements (verified against ph-ebinterface 8.1.0
generated sources). This is the **same F6 hole the UBL side already closed** in M4 — commit `3ddc761`
fixed it for UBL only and its message says so.

**Write this test first.** `EbInterface61ToInvoiceMapperTest.reportsFurtherLineDescriptionsItDoesNotRead()`,
mirroring the existing `UblToInvoiceMapperTest.reportsFurtherPaymentTermsNotesItDoesNotRead` (:187)
so the two sides read identically.

---

### 8. The UBL reverse mapper never checks BT-106/BT-109 and never reads BT-10, BT-113, BT-114, BG-20/21

**P2 · Effort M · `mapping/…/ubl/UblToInvoiceMapper.java:438`**

`totalsDeviations` compares only `TaxInclusiveAmount`, `PayableAmount` and `TaxTotal/TaxAmount`.
`LineExtensionAmount` (BT-106) and `TaxExclusiveAmount` (BT-109) are read nowhere; per-category
`TaxSubtotal` amounts are never compared. `BuyerReference` (BT-10) is read by nothing, so a Peppol
invoice that satisfies R003 via BT-10 loses its Auftragsreferenz and later fails AT-B2G-01 with no
note explaining why. The class Javadoc (:44-47) nevertheless claims "the derivation is compared
against what the document stated".

**Write this test first.** `UblToInvoiceMapperTest.reportsTheBuyerReferenceItCannotCarry()` — build a
UBL invoice with `BuyerReference` set and `OrderReference` null, and assert a CONV-01 naming BT-10.
Then one with `LineExtensionAmount` 1.00 off its lines, and one with `PrepaidAmount`.

---

### 9. ADR-0003 says validation compares documents against the canonical model — it never does

**P2 · Effort M · `docs/adr/0003-canonical-model.md:11` and `:85`**

Stated **twice**, not once (the original audit caught only the first). No arithmetic rule exists in
the ebInterface pipeline, so a document whose stated totals disagree with its own lines passes
validation.

**Write this test first.** `CorpusTest` is table-driven — do **not** add a method. Add a fixture
`corpus/invalid/at-b2g-06-totals-mismatch.xml` (a copy of a valid file with one total altered) and a
row to the `corpus()` `@MethodSource` (`CorpusTest.java:48-74`) expecting the rule id you are about
to introduce. It goes red because nothing detects it today.

**Then fix:** either implement the recomputation rule the ADR promises, or correct the ADR in both
places. Prefer the rule — the ADR is right about what the canonical model is *for*.

---

### 10. The IBAN mask is defeated by the standard grouped print format

**P2 · Effort S · `ai-assist/…/internal/PiiScrubber.java:71`**

The pattern is `\b[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}\b` — contiguous only. `AT61 1904 3002 3457 3201`,
the form printed on every invoice and bank statement, matches none of the four scrubber patterns and
therefore **reaches the LLM provider**. `docs/privacy.md` and `SECURITY.md` state the masking
unconditionally.

**Write this test first.** `PiiScrubberTest.masksAnIbanInTheGroupedPrintFormat()`, immediately after
`masksAnIbanAndAVatIdWhateverTheirCasing()` (ends line 62) so the two gaps of this class sit
together. The file already has `IBAN = "AT611904300234573201"` at line 19 — assert the spaced form is
masked too.

---

### 11. A broad `catch (RuntimeException)` drops the party names before the LLM call

**P2 · Effort S · `app/…/web/DashboardController.java:238-244`, `app/…/ai/ReportExplanationService.java:170-179`**

Both catches return empty/null so that "a report whose invoice has since been deleted must still be
explainable" — reasonable. But the same catch swallows *any* runtime failure, and the party-name
literals are what `[NAME]` masking is driven from. A transient database error therefore sends the
prompt **unmasked**, silently, with no logger in either class to notice.

**Write this test first.** `ReportExplanationServiceTest.aDatabaseFailureReadingThePartiesNeverReachesTheProvider()`
— mock the parties lookup to throw, and assert the provider is never called (rather than called with
an unmasked prompt). This is the first test in that class to construct the service, so add the
Mockito imports.

---

## Unverified — re-check against `main` before acting

Not individually re-verified after the five merges. Severities are the original auditor's.

| Sev | Id | Where |
|---|---|---|
| P2 | `adr0002-dependency-chain-wrong` | `docs/adr/0002-modular-monolith.md:14` — describes edges ArchUnit forbids; omits `formats-api` |
| P2 | `spec8-tenant-delete-retention-stale` | `docs/SPEC.md:195` — calls shipped features unimplemented |
| P2 | `readme-lighthouse-100-asserted` | `README.md:19` — "100 … asserted in CI"; CI asserts ≥ 95 |
| P2 | `appjs-appcss-line-counts-drifted` | `docs/SPEC.md:107` — counts a previous review corrected have drifted again |
| P2 | `nojs-e2e-test-vacuous` | `e2e/…/PublicValidatorFlowIT.java:230` — passes whether or not JS is disabled |
| P2 | `pdf-endpoint-test-vacuous` | `app/…/InvoiceApiIT.java:307` — asserts non-empty body, comment claims more |
| P2 | `archunit-claims-without-rules` | `ai-assist/…/internal/package-info.java:8` — names rules that do not exist |
| P2 | `controller-repository-rule-half-scoped` | `app/…/ArchitectureTest.java:204` — scoped narrower than its message |
| P3 | `ratelimit-bucket-per-exact-ip` | `RateLimitFilter.java:301` — an IPv6 caller gets unbounded buckets |
| P3 | `apikey-reaches-actuator-info-metrics` | `SecurityConfig.java:64` |
| P3 | `pit-gate-hygiene` | `mapping/pom.xml:182` — `rendering` has no PIT gate |
| P3 | `corpus-table-no-completeness-guard` | `CorpusTest.java:48` |
| P3 | `readme-ruleids-omits-peppol-01` | `README.md:421` |
| P3 | `glossary-and-german-naming-drift` | `docs/glossary.md:23` |
| P3 | `ebi-country-only-from-attribute` | `EbInterface61ToInvoiceMapper.java:209` |
| P3 | `explain-operation-duplicated` | `ReportExplanationService.java:18` |
| P3 | `formats-ebinterface-no-whitelist` | `FormatsEbInterfaceArchitectureTest.java:54` |
| P3 | `peppol-id-prefix-duplicated-in-messagede` | `PeppolValidationStage.java:165` — 2026.5 prefixes ids into the text |

---

## Already fixed on 2026-08-07 — do not re-investigate

| What | Closed by |
|---|---|
| Peppol pin stale (2025.11 → **2026.5**, mandatory 2026-08-17); `BR-CO-25` deleted upstream but still translated; R004/R007 German texts factually wrong; ADR-0007's stale "78 Übersetzungen" | **#26** |
| Every page linked `/swagger-ui.html` while production served 404 | **#27** |
| Off-site backup script, `deployment.md` §10.4, the retracted `rsync --delete` recipe | **#25** |
| OpenAPI description announced UBL support as future work it had shipped | **#28** |
| **Anonymous uploads spooled to disk**, contradicting "keine Datei auf einem Datenträger" (`file-size-threshold` defaulted to 0 B) | **#29** |

The audit's own memo also **retracted two findings** it had read off an unmerged branch (that
`offsite-sync.sh` was undocumented and that off-site copying was "deliberately not scripted"). Both
are now true on `main` via #25 — do not re-raise them.

---

## Ground rules carried forward

- **Test first, and watch it fail for the right reason.** Every fix above names the failing test to
  write before the change.
- **A mapping or validation bug grows the golden-file corpus first**, then gets fixed.
- **Never weaken an ArchUnit rule to make a test pass** — fix the design. If a rule is narrower than
  its failure message claims, widen the rule, do not soften the message.
- **Docs land in the same PR as the change they describe** (`CLAUDE.md`).
- **Prefer deriving expected values from shipped artefacts over hand-maintained lists.** Three of
  today's defects were hand-maintained lists that had quietly gone stale.
