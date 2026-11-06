# Reddit — drafts

Status: **Entwurf** (2026-11-06, replaces the draft of 2026-10-15). Nothing here is posted.

> **The subreddit rules could not be read by the agent, twice.** `reddit.com/r/<sub>/about/rules`
> answered HTTP 403 to curl on 2026-10-15 and again on 2026-11-06, and the fetch tool is blocked
> for reddit.com. So every "rule check" line below is **not measured** and has to be filled in by
> the author in a browser before posting. Look for: self-promotion policy (own-project posts only
> on certain days, in a megathread, or only with a share of non-promotional activity), required
> flair, title format, minimum account age or karma, link post versus text post, language.
> If a rule and a draft disagree, the rule wins — adapt the text or drop the sub.

Rules for us, whatever the subreddit says: post from the author's own account, say that it is our
own project in the first sentence, answer every comment for six hours, never ask for votes, never
post the same text to more than the subs listed here on one day, never post a real invoice.

---

## 1. r/selfhosted (English) — primary

Rule check by the author on posting day (fill in): self-promotion rule · flair · day restriction ·
title format · link or text post.

**Title:** `einvoice-at: self-hosted validator and generator for Austrian e-invoices (ebInterface 6.1, Peppol BIS 3.0)`

**Body:**

This is my own project. I built a self-hostable app for Austrian e-invoices and would like
feedback from people who run their own stack.

What it does: validates ebInterface 6.1 and Peppol BIS Billing 3.0 (UBL) invoices, generates both
from structured data, and converts between them with a report of what the target format cannot
carry. Every finding comes with a German message, the rule id and the location in the document.

How it runs: Docker Compose — the app (Java 25 / Spring Boot), PostgreSQL 17, Keycloak for login
and Mailpit for local mail. An optional `observability` profile adds Prometheus, Tempo and Grafana,
provisioned. Three commands:

```bash
git clone https://github.com/Stoicera/einvoice_at.git && cd einvoice_at
cp .env.example .env   # set POSTGRES_PASSWORD, KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD
docker compose up -d   # then open http://localhost:8080/validator
```

If something on your machine already listens on 5432, set `POSTGRES_PORT` in `.env` — that was the
one thing that broke the quickstart on a developer laptop when I ran it from a fresh clone.

Things self-hosters may care about: anonymous uploads are checked in memory and not written to disk
or logs; signed-in users can delete their account and all data from the dashboard; backup and
restore scripts are in the repo and the restore is exercised on every CI build; there is a
deployment walkthrough for one VM with Dokploy; `/.well-known/security.txt` and a SECURITY.md with
a threat model that names its own limits.

What it is not: not a Peppol access point, it does not send invoices, no inbound processing, no
bookkeeping or tax advice.

Public instance if you want to try it before installing anything (no account):
https://einvoice.sebastiankern.net/validator

Repo (Apache-2.0): https://github.com/Stoicera/einvoice_at

Two questions I would like answers to: is the compose setup clear enough to run without reading
the whole README, and is Keycloak too heavy for a tool like this in your setup?

---

## 2. r/opensource (English)

Rule check by the author on posting day (fill in): self-promotion rule (many such subs want the
post to be about the project's *openness*, not a feature list) · flair ("Promotional" or similar
if the sub has one) · title format.

**Title:** `einvoice-at — Apache-2.0 validator for Austrian e-invoices; where the official rules stop, the rules are in the repo`

**Body:**

My own project, Apache-2.0, and the reason I am posting it here is a specific open-source point
rather than the feature list.

Austria's federal bodies accept only structured e-invoices (ebInterface or Peppol BIS Billing 3.0).
For Peppol there is an official, machine-readable rule set from OpenPeppol, and the project runs it
unmodified through phive at a version pinned in code; a build test fails the day a newer rule set
supersedes the pin. For ebInterface, the Austrian format, the standards body publishes no
Schematron at all. So anyone who wants to check the Austrian business rules by machine has to
write them — and then the only way to make such rules trustworthy is to publish them with the
evidence. That is what the repo does: the AT-B2G rules are Schematron files in the repository, and
a golden-file corpus of valid and deliberately broken documents (one defect per file) shows what
each rule catches.

The second open-source point: every finding carries a German message, the rule id and the
location, because the person who has to fix a rejected invoice is in accounting, not in
engineering. The German messages for the 79 Peppol rules an Austrian filer realistically trips
(count as of 2026-10-01) are in a single class and pinned by a test.

What it is not: not a Peppol access point, no sending, no inbound processing, no bookkeeping, no
tax advice, not certified by anyone. The verdict that counts is the one at e-rechnung.gv.at.

Repo: https://github.com/Stoicera/einvoice_at — CONTRIBUTING.md says what gets merged and that we
answer issues within five working days. Public validator without an account:
https://einvoice.sebastiankern.net/validator

What I would value: a review of the AT-B2G Schematron by anyone who has filed to the Austrian
federal government and been rejected for a reason these rules do not catch.

---

## 3. r/java (English) — the subject-specific sub

Rule check by the author on posting day (fill in): r/java has historically been strict about
self-promotion and about posts that are "just a link to my project" — read the current rule text;
if it requires a technical write-up rather than an announcement, post the Dev.to article
(`devto.md`) as a link post with this text as the comment instead.

**Title:** `Four times the build was green and something was still wrong — lessons from an open-source Spring Boot 4 / Java 25 e-invoice validator`

**Body:**

Own project; this is about the engineering, not an announcement. einvoice-at validates, generates
and converts Austrian e-invoices (ebInterface 6.1, Peppol BIS Billing 3.0) — Java 25, Spring Boot
4.1, Maven multi-module, PostgreSQL 17, Keycloak, Testcontainers, ArchUnit, jqwik, PIT. Apache-2.0:
https://github.com/Stoicera/einvoice_at

Four things the test suite could not see, each with the pull request:

1. **A deleted rule we kept translating** (#26, 2026-08-07). After switching to Peppol rule set
   2026.5 the corpus stayed green, but `BR-CO-25` had been removed upstream and the German message
   catalogue still "translated" it. The coverage test compared against a hand-typed list. Now a
   test reads the rule ids from the shipped rule files, and another fails the build on the day a
   newer rule set supersedes the pinned one.
2. **A green repo turned red without a changed line** (#38, 2026-10-08). OWASP Dependency-Check
   went red for CVEs published against libraries we had not touched. Fix was an upgrade, not a
   suppression. It happened again on 2026-11-02: DOMPurify inside the swagger-ui webjar; fixed by
   pinning `org.webjars:swagger-ui` ahead of what springdoc manages.
3. **A dead button no test could see** (#27, 2026-08-07). With API docs switched off in
   production, the "REST-API" button led to a 404. The test for the switched-off state checked the
   docs were gone, never that the link was. Fixed test-first in both directions.
4. **Our own privacy promise was not quite true** (#29, 2026-08-07). "The upload is not stored"
   — but `spring.servlet.multipart.file-size-threshold` defaults to 0, so every upload was streamed
   to a temp file for the length of the request. The threshold now sits above the accepted maximum.

Things I would like to discuss: how others keep time-based facts (rule-set validity, CVE feeds)
from going stale silently, and whether anyone has a cleaner pattern than a webjar override for
transitive front-end bundles that trip the dependency scan.

---

## 4. r/Austria (Deutsch) — only because the angle is genuinely Austrian

Rule check by the author on posting day (fill in): self-promotion rule · flair (likely "Wirtschaft"
or a tech flair; check) · language rule · whether link posts to own projects are allowed at all.
If the sub does not allow it, drop it — the federal e-invoicing obligation is the only reason it
is on the list.

**Titel:** `Kostenloser Prüfer für E-Rechnungen an den Bund – erklärt jeden Fehler auf Deutsch (Open Source)`

**Text:**

Eigenes Projekt, offen gesagt. Wer Rechnungen an Bundesdienststellen schickt, muss sie als
strukturierte E-Rechnung einreichen (ebInterface oder Peppol). Wird eine abgelehnt, kommt die
Begründung oft als technische Schematron-Ausgabe zurück: englischer Regeltext, XPath, Assertion-ID.

Ich habe dafür einen Prüfer gebaut: https://einvoice.sebastiankern.net/validator

- XML-Rechnung hochladen, das Format wird automatisch erkannt.
- Jeder Befund kommt mit deutscher Meldung, Regel-ID und Fundstelle, zum Beispiel „Auftragsreferenz
  fehlt: Rechnungen an Bundesdienststellen müssen eine Auftragsreferenz enthalten."
- Kein Konto, der Upload wird nur im Arbeitsspeicher geprüft und nicht gespeichert.
- Für Peppol läuft das offizielle OpenPeppol-Regelwerk unverändert; für ebInterface gibt es kein
  offizielles Schematron, die österreichischen Regeln sind deshalb im Repository nachlesbar.

Was er nicht ist: keine Einreichung, kein Ersatz für die Prüfung bei e-rechnung.gv.at (deren Urteil
zählt), keine Rechts- oder Steuerberatung, nicht zertifiziert.

Der Code ist offen (Apache-2.0) und lässt sich selbst betreiben:
https://github.com/Stoicera/einvoice_at

Mich interessiert vor allem: Welche Ablehnungsgründe vom Bund kennt ihr, die so ein Prüfer finden
sollte? Bitte keine echten Rechnungen mit Personendaten posten oder hochladen, ein Beispiel mit
erfundenen Daten reicht.

---

## Order and spacing

Launch day (see `README.md` in this folder): r/selfhosted right after Show HN, r/opensource one
hour later, r/java the next morning (its rule may demand a write-up — then the Dev.to article is
already published and can be linked), r/Austria only if its rules allow it, same afternoon.
Never two subs within the same hour, never the same text twice.
