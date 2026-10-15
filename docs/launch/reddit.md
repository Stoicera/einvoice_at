# Reddit — drafts

Status: **Entwurf** (2026-10-15). Nothing here is posted.

> **Before posting, the author reads the current rules and sidebar of each subreddit himself.**
> The rules could not be fetched for this draft (Reddit refused the request on 2026-10-15), so
> nothing below is checked against them. Look in particular for: a self-promotion policy (some subs
> allow own projects only on certain days or in a megathread, or require a share of non-promotional
> activity), required flair, title format, minimum account age or karma, and whether links or
> text posts are expected. Adapt the text to the rule, or do not post in that sub.

Rules for us, whatever the subreddit says: post from the author's own account, say that it is our
own project, answer every comment for six hours, no vote requests, no cross-posting the same text
to many subs on one day beyond the two below.

---

## r/selfhosted (English)

Rule check by the author on posting day (fill in): self-promotion rule · flair · day restriction ·
title format.

**Title:** `einvoice-at: self-hosted validator and generator for Austrian e-invoices (ebInterface 6.1, Peppol BIS 3.0)`

**Body:**

I built a self-hostable app for Austrian e-invoices and would like feedback from people who run
their own stack.

What it does: validates ebInterface 6.1 and Peppol BIS Billing 3.0 (UBL) invoices, generates both
from structured data, and converts between them with a report of what the target format cannot
carry. Every finding comes with a German message, the rule id and the location in the document.

How it runs: Docker Compose — the app (Java 25 / Spring Boot), PostgreSQL 17, Keycloak for login
and Mailpit for local mail. An optional `observability` profile adds Prometheus, Tempo and Grafana,
provisioned. Three commands to start:

```bash
git clone https://github.com/Stoicera/einvoice_at.git && cd einvoice_at
cp .env.example .env   # set POSTGRES_PASSWORD, KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD
docker compose up -d   # then open http://localhost:8080/validator
```

Things self-hosters may care about: anonymous uploads are checked in memory and not written to disk
or logs; signed-in users can delete their account and all data from the dashboard; backup and
restore scripts are in the repo and the restore is exercised on every CI build; there is a
deployment walkthrough for one VM with Dokploy.

What it is not: not a Peppol access point, it does not send invoices, no inbound processing, no
bookkeeping or tax advice.

If you want to try it before installing anything, there is a public instance:
https://einvoice.sebastiankern.net/validator (no account).

Repo (Apache-2.0): https://github.com/Stoicera/einvoice_at

Questions I would like answers to: is the compose setup clear enough to run without reading the
whole README, and is Keycloak too heavy for a tool like this in your setup?

---

## r/Austria (Deutsch)

Rule check by the author on posting day (fill in): self-promotion rule · flair (likely a tech or
"Wirtschaft" flair, check) · language rule. The Austrian angle is genuine — the federal
e-invoicing obligation — which is why this sub is on the list at all.

**Titel:** `Kostenloser Prüfer für E-Rechnungen an den Bund – erklärt jeden Fehler auf Deutsch (Open Source)`

**Text:**

Wer Rechnungen an Bundesdienststellen schickt, muss sie als strukturierte E-Rechnung einreichen
(ebInterface oder Peppol). Wird eine abgelehnt, kommt die Begründung oft als technische
Schematron-Ausgabe zurück: englischer Regeltext, XPath, Assertion-ID.

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

## Other subs (not drafted)

`r/opensource` and one subject-specific sub (for example a Java or e-invoicing sub) are candidates
named in the launch prompt. Draft them only after the author has read their rules; the r/selfhosted
text is the closest starting point.
