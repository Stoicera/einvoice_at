# Dev.to — draft article

Status: **Entwurf** (2026-10-15). Nothing here is published. Target length of the finished article:
600–900 words (the draft body below is about 850). English. If the article also runs in the
Werkbank publication, publish it there first and set `canonical_url` on Dev.to to that URL.

Front matter for Dev.to:

```yaml
title: "What a green build did not tell us: four findings from an Austrian e-invoice validator"
published: false
tags: java, springboot, opensource, testing
# canonical_url: <Werkbank URL, if it runs there first>
```

Every date below is taken from the repository (merged pull requests, `CHANGELOG.md`,
`docs/worklog.md`); the PR number is given so a reader can check it.

---

## Draft

### Intro (≈120 words)

Invoices to Austrian federal bodies must be structured e-invoices — ebInterface 6.1 or Peppol BIS
Billing 3.0. When one is rejected, the reason comes back as Schematron output: an English rule text,
an XPath, an assertion id. einvoice-at is an open-source Java 25 / Spring Boot app that validates,
generates and converts both formats and answers every finding with a German message, the rule id and
the location. Try it against the live instance:

```bash
curl -sL https://raw.githubusercontent.com/Stoicera/einvoice_at/main/validation/src/test/resources/corpus/invalid/at-b2g-01-missing-order-reference.xml \
  | curl -s -F 'file=@-;filename=invoice.xml' https://einvoice.sebastiankern.net/api/v1/validate \
  | jq '.report | {valid, findings: [.findings[] | {ruleId, location, messageDe}]}'
```

This article is not about the features. It is about four moments when the build was green and
something was still wrong — and what the project does differently since.

### 1. A deleted rule that we kept translating (PR #26, 2026-08-07) (≈170 words)

- OpenPeppol made rule set 2026.5 mandatory on 2026-08-17. The project switched on 2026-08-07, ten
  days before, because after that date a "valid" from us would have been judged by rules the
  recipient no longer applies.
- The golden-file corpus stayed green — correctly, the changed rules concern Dutch and Danish schemes.
- But rule `BR-CO-25` had been deleted with no successor, and the German message catalogue went on
  translating it. No test failed: the coverage test compared against a list typed into the test
  file, not against the rule set. Two German messages had also become wrong, not just imprecise.
- Since then a test reads the allowed rule ids from the shipped rule files, and another one fails
  the build on the day a newer rule set supersedes the pinned one, with version and date in the
  message.
- Line to keep: a deadline you have to remember is a risk; now the build reports it.

### 2. A green repository turned red without a changed line (PR #38, 2026-10-08) (≈140 words)

- Builds pin dependencies; they do not pin the world's verdict on them. The OWASP Dependency-Check
  job in CI was red from 2026-09-24 at the latest, for CVEs published against libraries the project
  had not touched.
- Fix: an upgrade, not a suppression — Spring Boot 4.1.0 → 4.1.1, Tomcat 11.0.24 → 11.0.26,
  Netty 4.2.16 → 4.2.19, only `pom.xml`. Green on `main` since 2026-10-08.
- The rule in `.owasp-suppressions.xml`: a suppression is a claim that a CVE does not apply, it
  names its reason, and where possible it carries an expiry date so it has to be argued again.

### 3. A dead button that no test could see (PR #27, 2026-08-07) (≈150 words)

- The landing page had a "REST-API ansehen" button and every page an "API" menu item. On production,
  where the API docs were switched off at the time, both led to a 404.
- There was a test for the switched-off state. It checked that the docs were no longer served. Its
  own comment said the docs were "really gone, not merely unlinked" — the second half was never
  tested and never true.
- Found by clicking through the live site in a real browser. Fixed test-first in both directions:
  links disappear when the docs are off, and appear when they are on — otherwise a wiring bug that
  removes the link everywhere would stay green.
- Line to keep: a green suite proves the code does what the tests say; it cannot notice a page
  pointing at something that does not exist.

### 4. Our own privacy promise was not quite true (PR #29, 2026-08-07) (≈170 words)

- The public validator says the upload is not stored: no report, no log entry, no file on a disk.
  The last part was false.
- Spring Boot's `spring.servlet.multipart.file-size-threshold` defaults to 0 bytes, so every upload
  was streamed to a temporary file. The app had set the size limits, not the threshold. Observed on
  the running instance during a 1.9 MB upload; the file was deleted at the end of the request.
- For a page that lists privacy as a property, the gap between promise and mechanism is the bug,
  not how long the file lived.
- Fix: a threshold at least as large as the accepted maximum, so anything the app accepts stays
  below it and in memory. Re-measured the same way: before a temp file, after none. Listed under
  "Fixed" in v0.2.0 (2026-10-09).

### Close (≈100 words)

- The pattern across all four: the tests checked what someone wrote down, not what is true outside
  the code — a rule set, the CVE database, a page in a browser, a disk.
- What helped each time: derive the check from the source of truth (rule files, not a list), let
  time-based facts fail the build, use the product in a browser, measure a promise on the running
  system.
- Not in this article: what the project does not do (no Peppol access point, no sending, no tax
  advice). Repo, Apache-2.0: https://github.com/Stoicera/einvoice_at — feedback via issues welcome.

---

## Not to be written (binding)

No user or customer numbers (there are none). No "certified", "officially approved" or "checked by
the federal government". No server addresses, host names of the control plane or paths. No effort
or cost figures. Nothing that sounds like legal or tax advice.
