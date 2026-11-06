---
title: "What a green build did not tell us: four findings from an Austrian e-invoice validator"
published: false
tags: java, springboot, opensource, testing
# canonical_url: <Werkbank URL — set it if the German issue (werkbank.md) is published first>
---

<!-- Status: Entwurf (2026-11-06, replaces the outline of 2026-10-15). Nothing published.
     About 1 200 words in the article body, code blocks included. Every date is taken from the repository (merged pull
     requests, CHANGELOG.md, docs/worklog.md, CI run ids), with the PR number so a reader can
     check it. Not to be written, binding: user or customer numbers (there are none),
     "certified" or "approved by the federal government", server addresses or control-plane
     paths, effort or cost figures, anything that sounds like legal or tax advice. -->

Invoices to Austrian federal bodies have to be structured e-invoices — ebInterface 6.1 or Peppol
BIS Billing 3.0. When one is rejected, the reason comes back as Schematron output: an English rule
text, an XPath, an assertion id. [einvoice-at](https://github.com/Stoicera/einvoice_at) is an
open-source Java 25 / Spring Boot 4.1 application that validates, generates and converts both
formats and answers every finding with a German message, the rule id and the location in the
document. You can try the validator against the live instance without an account:

```bash
curl -sL https://raw.githubusercontent.com/Stoicera/einvoice_at/main/validation/src/test/resources/corpus/invalid/at-b2g-01-missing-order-reference.xml \
  | curl -s -F 'file=@-;filename=invoice.xml' https://einvoice.sebastiankern.net/api/v1/validate \
  | jq '.report | {valid, findings: [.findings[] | {ruleId, location, messageDe}]}'
```

It answers `"valid": false` with one finding, `AT-B2G-01` at `/eb:Invoice`: *Auftragsreferenz
fehlt* — the order reference federal bodies require and most international ERP systems have never
heard of (1.0 s round trip on 2026-11-06).

This article is not about the features. It is about four moments when the build was green and
something was still wrong, and what the project does differently since. The pattern is the same
each time: the tests checked what somebody had written down, not what was true outside the code.

## 1. A deleted rule we kept translating

OpenPeppol made rule set 2026.5 mandatory on 2026-08-17. The project switched on 2026-08-07
(PR #26), ten days early, because after the deadline a "valid" from us would have been judged
against rules the recipient no longer applies. The golden-file corpus stayed green, correctly: the
changed rules concern Dutch and Danish schemes.

But rule `BR-CO-25` had been deleted upstream with no successor, and the German message catalogue
went on "translating" it. No test failed, because the coverage test compared the catalogue against
a list typed into the test file. Two German messages had quietly become wrong as well, not merely
imprecise.

Two tests replaced that list. One reads the allowed rule ids out of the shipped rule files. The
other reads the publication dates OpenPeppol ships inside the phive-rules artefact and fails the
build on the day a newer rule set supersedes the pinned one — with the version and the date in the
assertion message:

```java
@Test
void noNewerRuleSetIsAlreadyMandatory() {
  int[] pinned = versionOf(PeppolValidationStage.RULE_SET_VERSION);
  LocalDate today = LocalDate.now();
  Map<String, LocalDate> published = publishedRuleSets(); // read from phive-rules, not listed here
  assertThat(published).hasSizeGreaterThan(1);
  assertThat(published).allSatisfy((version, mandatoryFrom) -> {
    int[] candidate = versionOf(version);
    boolean isNewer = candidate[0] > pinned[0]
        || (candidate[0] == pinned[0] && candidate[1] > pinned[1]);
    if (isNewer) {
      assertThat(mandatoryFrom)
          .as("OpenPeppol rule set %s became mandatory on %s and this validator is still pinned"
              + " to %s. Upgrade PeppolValidationStage to PeppolValidation%s_%02d (ADR-0007).",
              version, mandatoryFrom, PeppolValidationStage.RULE_SET_VERSION,
              candidate[0], candidate[1])
          .isAfter(today);
    }
  });
}
```

Before this test the deadline lived in an owner checklist and in one person's memory. It is the
one tripwire in the repository that fires without anybody editing the repository first.

## 2. A green repository turned red without a changed line

Builds pin dependencies; they do not pin the world's verdict on them. The OWASP Dependency-Check
job in CI went red on 2026-09-24 at the latest, for CVEs published against libraries the project
had not touched. The fix was an upgrade, not a suppression: Spring Boot 4.1.0 → 4.1.1, Tomcat
11.0.24 → 11.0.26, Netty 4.2.16 → 4.2.19, only `pom.xml` (PR #38, green on `main` since
2026-10-08).

It happened again on 2026-11-02, this time in a scheduled run that exists only to warm the NVD
cache: `swagger-ui-bundle.js` inside the swagger-ui 5.32.11 webjar embeds DOMPurify 3.4.12, flagged
with two CVSS 10.0 advisories, fixed in DOMPurify 3.4.17. springdoc 3.1.0 manages that webjar at
5.32.11, and even springdoc 3.2.0 manages 5.34.0, which still embeds 3.4.16 — verified by extracting
the jars rather than trusting a changelog. So the webjar is now pinned separately at 5.34.1, which
embeds 3.4.17. One detail that cost an hour: springdoc builds the UI's resource path from its own
`springdoc.swagger-ui.version` property, so a webjar pinned ahead of it answers 404 until that
property says the same number. The integration test that requests `/swagger-ui/index.html` is what
caught it.

The rule in `.owasp-suppressions.xml` stands: a suppression is a claim that a CVE does not apply,
it names its reason, and where possible it carries an expiry date so it has to be argued again.

## 3. A dead button that no test could see

The landing page had a "REST-API ansehen" button and every page an "API" menu item. In production,
where the API docs were switched off at the time, both led to a 404.

There was a test for the switched-off state. It checked that the docs were no longer served. Its
own comment said the docs were "really gone, not merely unlinked" — the second half was never tested
and never true. It was found by clicking through the live site in a real browser, and fixed
test-first in both directions (PR #27, 2026-08-07): the links disappear when the docs are off and
appear when they are on, so a wiring bug that removes the link everywhere cannot stay green either.

A green suite proves the code does what the tests say. It cannot notice a page pointing at
something that does not exist.

## 4. Our own privacy promise was not quite true

The public validator says the upload is not stored: no report, no log entry, no file on a disk. The
last part was false. Spring Boot's `spring.servlet.multipart.file-size-threshold` defaults to zero
bytes, so every upload was streamed to a temporary file for the length of the request. The
application had set the size limits, not the threshold. Observed on the running instance during a
1.9 MB upload; the file was deleted at the end of the request.

For a page that lists privacy as a property, the gap between promise and mechanism is the bug, not
how long the file lived. The fix (PR #29, 2026-08-07, listed under "Fixed" in v0.2.0) sets the
threshold at least as large as the accepted maximum, so anything the application accepts stays
below it and in memory. Re-measured the same way: before, a temp file; after, none.

## What helped each time

Derive the check from the source of truth — the rule files, not a list; the artefact's own
publication dates, not a calendar entry. Let time-based facts fail the build instead of waiting in
a document. Use the product in a browser. Measure a promise on the running system, not in the
configuration that is supposed to produce it. And treat a transitive front-end bundle like any
other dependency: extract it and look.

What the project does not do: it is not a Peppol access point and sends nothing, no inbound
processing, no bookkeeping, no tax advice, not certified by anyone — the verdict that counts is the
one at e-rechnung.gv.at. The repository is Apache-2.0; `CONTRIBUTING.md` says what gets merged and
that issues get an answer within five working days. If you run it yourself:

```bash
git clone https://github.com/Stoicera/einvoice_at.git && cd einvoice_at
cp .env.example .env   # set POSTGRES_PASSWORD, KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD
docker compose up -d   # http://localhost:8080/validator — set POSTGRES_PORT if 5432 is taken
```

From a fresh clone on 2026-11-06 that took 312 seconds to a healthy stack, image build included,
and the only thing that broke was a database already listening on 5432.
