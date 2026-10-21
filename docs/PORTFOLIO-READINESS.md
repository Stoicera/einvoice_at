# Portfolio readiness — before the public launch

Status: 2026-10-08 · Measured against the Stoicera OSS-launch prompt (SSOT `ai/prompts/oss-launch.md`)
· Purpose: what has to change before the repository is posted publicly (Show HN, LinkedIn).

## Where it stands (measured 2026-10-08)

| Check | State |
|---|---|
| Live demo | <https://einvoice.sebastiankern.net> — `/actuator/health` 200 `UP`, anonymous `POST /api/v1/validate` with the B2G sample 200 `valid: true`, `id: null`; `/app` → Keycloak login (2 redirects, PKCE) |
| Production build | `main` as of 2026-10-08: PR #38 (Spring Boot 4.1.1, Tomcat 11.0.26, Netty 4.2.19) plus #35–#37 (agent files, deployment docs, backup scripts — no application code) |
| CI on `main` | green incl. OWASP Dependency-Check (run 37805734730); README CI badge points at a workflow that really runs |
| Licence | `LICENSE` is Apache-2.0, README and GitHub metadata say Apache-2.0 — consistent |
| Release | one release, `v0.1.0` (2026-08-06); 38 commits on `main` since (12 on the first-parent line, as of `c125460`), CHANGELOG `[Unreleased]` still says "Nothing yet" |
| Dependabot alerts | 0 open |
| Code-scanning alerts | 2 open: `java/spring-disabled-csrf-protection` (`SecurityConfig`), `java/polynomial-redos` (`PiiScrubber`) |
| Secret-scanning alerts | 0 open |
| Open issues / PRs | 0 issues; 11 Dependabot PRs, the oldest from 2026-07-24 |
| GitHub community profile | 75 % — missing CONTRIBUTING and a code of conduct; PR template and issue forms (bug, feature) exist, though the profile API reports `issue_template: false` for YAML forms |
| Social preview / llms.txt | GitHub default image; no `llms.txt` |
| Deploy and rollback docs | `docs/deployment.md` (walkthrough), `docs/deployment-reference.md` (rollback = deploy the previous `sha-<commit>` tag); Swarm `FailureAction: rollback` is set in production |
| Security contact | `SECURITY.md` named `security@stoicera-software.at` — that domain has no NS, MX or A record, so every report bounced. **Fixed in this PR** (GitHub private vulnerability reporting, enabled, plus office@stoicera.com) |

Not verified here: the README quickstart from a fresh clone on a clean machine (OSS-launch step 1).

## Checklist, in priority order

Effort: S = under 1 hour, M = half a day, L = more than a day.

1. **[done in this PR] Security contact that works** — S. A dead disclosure address in a repo whose
   SECURITY.md is one of its selling points is the first thing a security-minded reader tests.
2. **Clear the Dependabot backlog** — M. 11 bot PRs, open since July, read as "unmaintained" to any
   visitor. Rebase each on `main`, merge the green ones one at a time (each merge redeploys), close the
   superseded ones with a comment. The OWASP gate that blocked them is green again since #38.
3. **Remove stale status text** — S. README "Deutsche Kurzfassung" still says the live instance and
   the `v0.1.0` tag are missing (both exist); the M6 status block dominates the first screen; the
   live demo link is not in the README at all (only in the release notes and the repo homepage
   field). Put "Live demo: einvoice.sebastiankern.net" under the one-line summary.
4. **Cut `v0.2.0`** — S. Since `v0.1.0`: Peppol rules 2026.5, anonymous uploads never reach disk,
   OpenAPI fixes, the CVE dependency bump. Fill CHANGELOG `[Unreleased]` from the merge history
   (added / changed / fixed / breaking), tag, publish release notes.
5. **Triage the two code-scanning alerts** — M. The CSRF alert is the stateless `/api/**` chain
   (ADR-0006, CSRF stays on for the browser chain): dismiss as "won't fix" with that reference.
   The ReDoS alert in `PiiScrubber` needs a real look — it runs on untrusted text before an LLM call
   (feature off in production today); fix the pattern or document why input length bounds it.
6. **Problem-type URIs point at a host that does not exist** — M, owner decision.
   `Problems.BASE` is `https://einvoice-at.stoicera.com/problems/`; the name does not resolve, and
   `stoicera.com` is the brand domain, not for lab projects (zone rule 2026-08-14). Pick a resolvable
   base (e.g. a `docs/problems.md` anchor on GitHub or the demo host), change the constant and the
   ~10 test constants, note it in CHANGELOG as an API-visible change.
7. **README sections the launch prompt requires** — M. Missing: a short "why this exists" in our own
   words, an honest comparison with the alternatives (the e-rechnung.gv.at upload check, phive-based
   validators, commercial e-invoicing services — say what each does better), and a roadmap with
   explicit non-goals (no Peppol Access Point, no bookkeeping, no inbound processing — PRD §5). Every
   number with a measurement date. Then run the quickstart from a fresh clone and fix what breaks.
8. **Community files** — S. `CONTRIBUTING.md` (setup, `./mvnw verify`, what gets merged, response
   time), `CODE_OF_CONDUCT.md`, a "question" issue template (blank issues are disabled, so questions
   currently have no path).
9. **Repo furniture** — S. Social preview 1280×640 from `scripts/social-preview.mjs` (Navy #0B1F3A,
   Cobalt #2558E8, Inter) and committed; `llms.txt` at the root pointing to README, `docs/`,
   `samples/`.
10. **Bound the OWASP job** — S. On `main` it took 57 min on 2026-10-08 (run 37805734730, log:
    "Cache not found for input keys: nvd-Linux-2026-W41, nvd-Linux-"; runs from PR branches cannot
    share their cache with `main`) and has no `timeout-minutes`, so a stalled NVD
    sync holds the job for GitHub's 6-hour default. Add a timeout and a scheduled weekly run on
    `main` that warms the cache.

## Status 2026-10-09

| # | Item | State |
|---|---|---|
| 1 | Security contact | done (this PR) |
| 2 | Dependabot backlog | 8 of 11 merged one at a time (#13, #16, #33, #15, #32, #12, #23, #34), production health 200 and anonymous validate checked after each deploy; the three majors (#2 upload-artifact 4→7, #14 cache 4→6, #22 dependency-check 12→13) stay open with a comment each — they need their own PR |
| 3 | Stale status text, demo link | done (#44) |
| 4 | `v0.2.0` | CHANGELOG in #44; tag after this PR |
| 5 | Code-scanning alerts | ReDoS real and fixed (#42); CSRF dismissed as intended (stateless API chain, ADR-0006) |
| 6 | Problem-type URIs | done (#43): demo host, configurable via `PROBLEM_TYPE_BASE_URI`, `/problems/<slug>` redirects to `docs/problems.md` |
| 7 | README launch sections | open |
| 8 | Community files | done (#44): CONTRIBUTING, CODE_OF_CONDUCT, question template |
| 9 | Repo furniture | `llms.txt` done (#44); social preview open |
| 10 | Bound the OWASP job | timeout done (#41); scheduled cache warm-up on `main` open |

## Status 2026-10-13

| # | Item | State |
|---|---|---|
| 7 | README launch sections | done (this PR): "Why this exists", "How it compares", "Roadmap and non-goals", plus "For companies / Für Unternehmen" linking `docs/enterprise.md` and `docs/demo.md`. Not done: the quickstart from a fresh clone on a clean machine (still "not verified here") |
| 9 | Repo furniture | `llms.txt` done (#44); **social preview still open** — `scripts/social-preview.mjs` does not exist yet, GitHub shows the default image |

Added in the same PR, outside the numbered list: `docs/demo.md` (ten-minute demo script) and
`docs/enterprise.md` (operating models, security and privacy properties, backup, service hours,
limits, integration path, IT checklist) — the two documents a company visitor or a sales
conversation needs. Live checks on 2026-10-13: `/actuator/health` `UP`, `/validator` 200,
`/swagger-ui.html` 200, `/v3/api-docs` 15 operations on 13 paths, anonymous validate of both samples
`valid: true`, the broken Peppol corpus file two findings `R020`/`R010` with German messages,
`/api/v1/reports` without credential 401, `/app` 302 to login, security headers present.

Owner decision, taken 2026-10-09: the demo **stays** on `einvoice.sebastiankern.net` (personal OSS
portfolio domain) — no move, no broken links. `stoicera.com` is not used for lab projects.

## Status 2026-10-22

| # | Item | State |
|---|---|---|
| 10 | Bound the OWASP job | done: the first scheduled run on `main` (cron `0 3 * * 1`, started 2026-10-19 08:31 UTC) ran only the job "Security scan (OWASP Dependency-Check)" and finished green in 16 min (run 39223344329); every other job skipped as designed |

Also on 2026-10-22: `docs/deployment.md` §10/§10.4, `docs/owner-checklist.md` and `docs/worklog.md`
no longer name private network addresses, host names, user names or server paths of the operated
instance; placeholders (`<backup-host>`, `<app-dir>`, …) stand in. The old text remains in the Git
history — whether to rewrite it is an owner decision (force-push is blocked by the ruleset).

After the list: the launch package itself (OSS-launch Part B, drafts in `docs/launch/`) is a separate
task.
