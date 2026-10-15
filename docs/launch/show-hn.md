# Show HN — draft

Status: **Entwurf** (2026-10-15). Nothing here is posted. The author posts it himself and answers
the comments himself.

Rules this draft follows (Show HN page, read 2026-10-15): the title starts with "Show HN"; the
project is something people can try without signing up (the public validator and the curl line in
the README); the author built it and is there to discuss it; no one is asked to upvote or comment.

## Title

Chosen (79 characters):

> Show HN: Validator for Austrian e-invoices that explains each finding in German

Alternatives, also under 80 characters:

- `Show HN: Einvoice-at – check ebInterface and Peppol invoices, findings in German`
- `Show HN: Open-source validator for Austrian e-invoices with German error reports`

URL to submit: `https://github.com/Stoicera/einvoice_at` (the README links the live validator in
its first screen). Do not submit the demo URL — the repository is what we want people to read.

## First comment (by the author, posted right after submitting)

> Hi HN, I'm Sebastian. I built einvoice-at, a self-hostable Java 25 / Spring Boot app that
> validates, generates and converts Austrian e-invoices: ebInterface 6.1 (the Austrian format) and
> Peppol BIS Billing 3.0 (UBL). There is a public validator without an account:
> https://einvoice.sebastiankern.net/validator — upload an XML invoice and you get a report with a
> German message, the rule id and the location in the document for every finding. The upload is
> checked in memory and not stored.
>
> Why: invoices to Austrian federal bodies have to be structured, and a rejected one comes back as
> Schematron output — English rule text, an XPath, an assertion id. That is fine for me and a wall
> for the person in accounting who has to fix the invoice. Two details made it more than a
> translation job. The federal profile has mandatory fields (order reference, supplier number) that
> international ERP systems do not know about. And AUSTRIAPRO publishes no Schematron for
> ebInterface, so the Austrian business rules are written in this repo, with a golden-file corpus of
> valid and deliberately broken documents to show they do what they claim.
>
> For Peppol it does the opposite: the official OpenPeppol rules run unmodified (through phive) at a
> version pinned in code, and a build test fails the day a newer rule set supersedes the pin. Rule
> set 2026.5 became mandatory on 2026-08-17; the switch here was on 2026-08-07. While switching I
> found that a deleted rule was still being "translated" and no test noticed, because the coverage
> test compared against a hand-typed list. That test now reads the rule ids from the shipped rule
> files.
>
> What it does not do: it is not a Peppol access point and does not send anything; no inbound
> processing, no bookkeeping, no tax or legal advice. It is not certified by anyone — the verdict
> that counts is the one at e-rechnung.gv.at. If you need many countries and formats, phive-based
> validators cover far more rule sets; this project only runs the Peppol rules, it does not
> reimplement them.
>
> What is still rough: German messages exist for the 79 Peppol rules an Austrian filer realistically
> trips (as of 2026-10-01); other Peppol rules show a German frame around the English original. The
> optional AI explanation of findings is switched off on the demo. ebInterface 7.0 is not supported.
> The quickstart has not yet been run by someone other than me on a clean machine.
>
> Feedback that would help most: an invoice that e-rechnung.gv.at rejects but this validator passes
> (synthetic or anonymised, please — never a real invoice with personal data), German messages that
> read wrong to someone in accounting, and whether the REST API (`POST /api/v1/validate`, RFC 9457
> errors) fits into an existing invoicing system.
>
> Code is Apache-2.0. I'll be here answering questions today.

Optional paragraph, **Sebastian decides before posting** (how openly the AI-assisted workflow is
mentioned; `CLAUDE.md` and the review records are in the repository, so it is visible anyway):

> The repo also shows how it was built: I used AI coding agents throughout, under written rules
> (`CLAUDE.md`), with a hostile review after each milestone whose findings are recorded in
> `docs/worklog.md`.

## Posting rules for us

- It is our own project; say so, as the first comment does.
- The demo must work at posting time: check `/actuator/health`, upload a file in the browser and run
  the README curl line from a second machine within 30 minutes before posting.
- No deploy to production on launch day (a push to `main` redeploys and resets the in-memory
  request counters used for measuring, see `checklist.md`).
- The author answers every comment for at least six hours after posting. Critique gets a thank-you
  and a fix or an issue link, never an argument.
- Never ask anyone to upvote, and do not share the HN link with a request to vote.
- If it does not get traction, do not repost the same day; read the HN FAQ on reposts before
  trying again.
