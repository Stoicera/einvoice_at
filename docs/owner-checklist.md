# Owner checklist — the tasks only you can do

Status verified: **2026-08-07** against the live repository, registry and production server.

**Everything on the 2026-07-30 list is done.** A, B and C all closed on 2026-08-06 — the
deployment itself in the morning walkthrough, the rest in the autonomous completion sprint that
evening (worklog entry of the same date). Milestone M6 is formally closed.

| Item | State | Evidence |
|---|---|---|
| A1 · About box | ✅ description, 10 topics, website `https://einvoice.sebastiankern.net` | `gh repo view --json description,homepageUrl,repositoryTopics` |
| A2 · Branch protection on `main` | ✅ five required contexts, `strict`, no force-push, no deletion — enabled as the sprint's deliberate **last** action, so it could not block the sprint's own merges during the GitHub Actions outage of that evening | `gh api /repos/Stoicera/einvoice_at/branches/main/protection --jq '.required_status_checks.contexts'` |
| A3 · Nothing else waiting | ✅ `NVD_API_KEY` present; `DOKPLOY_DEPLOY_WEBHOOK` present since 2026-08-06 | `gh secret list` |
| B · Live instance | ✅ all §9 checks incl. the 5-address forged-XFF probe and a real browser login | deployment.md §9 |
| B10 · Backups | ✅ nightly 02:15 cron on the production VPS, restore rehearsed into a scratch DB, row counts matched | `/etc/cron.d/einvoice-backup`, `/var/log/einvoice-backup.log` |
| B11 · Auto-deploy | ✅ **proven end-to-end 2026-08-07**: run 31127548170 all six jobs green, Deploy logged `{"message":"Application deployed successfully"}` at 06:42:57Z, and the app container's `StartedAt` is 06:43:04Z — seven seconds later, `healthy`, service 1/1 | `gh run view 31127548170`; `docker inspect` on skdevserver1 |
| C · v0.1.0 | ✅ tag + GitHub release, live validator linked at the top of the notes | `gh release view v0.1.0` |

---

## What is genuinely left, and for whom

### 1 · Off-site backup copy — owner, ~10 min, the only open operational risk

The nightly dump lands on the same disk as the database: that is a copy, not a backup.

**The machine side is done** (2026-08-07). `scripts/offsite-sync.sh` is installed at
`/opt/einvoice-at/scripts/offsite-sync.sh` and already chained into `/etc/cron.d/einvoice-backup`
after the dump. It ships **disarmed**: with no `OFFSITE_TARGET` it prints a `NOT CONFIGURED` notice
and exits 0, so the nightly run is unaffected until you arm it. Verified by running the exact cron
line on the production server.

What is left is the part that needs your credit card and your credentials:

1. Order a **Hetzner Storage Box BX11** (€3.20/mo, 1 TB, no minimum term) in FSN1, enable SSH
   support.
2. On the production VPS: `ssh-keygen -t ed25519 -f /root/.ssh/storagebox -N ""` and register
   `/root/.ssh/storagebox.pub` in the Storage Box panel.
3. Write `/opt/einvoice-at/offsite.env` (`chmod 600`) — the four lines are in `deployment.md` §10.4.
   Note **port 23**, not 22, and set `OFFSITE_REQUIRED=1` so a typo'd target fails the nightly run
   instead of looking like "not configured yet".
4. Run `/opt/einvoice-at/scripts/offsite-sync.sh /var/backups/einvoice` once. It ends by downloading
   the newest dump back off the box and comparing its SHA-256 — that line is the proof.

No code or cron change is needed after step 3.

> The earlier revision of this checklist recommended `rsync -a --delete`. **Do not use `--delete`
> here.** It makes the remote mirror the local directory, so an emptied local backup directory —
> failed disk, wrong mount, a bad `BACKUP_KEEP_DAYS` — propagates that emptiness off-site on the next
> nightly run and destroys the last surviving copy at exactly the moment it is needed. The installed
> script deliberately omits it and refuses to sync an empty source at all.

Alternative if you prefer clicking over cron: Dokploy → Settings → S3 Destinations with a Hetzner
Object Storage bucket, then per-database scheduled backups in the panel. More moving parts, same
result; the cron path reuses the dump-and-verify pipeline you have already rehearsed.

### 2 · Peppol 2026.5 rule-set upgrade — ✅ done 2026-08-07, ten days early

No longer an owner item, and it needed **no dependency bump at all**: the already-pinned
phive-rules 4.4.1 ships `PeppolValidation2026_05` and the full `2026.5` artefact tree. The upgrade
was a code change plus a corpus re-run, exactly as ADR-0007 predicted.

The corpus came through unchanged — 2026.5's new and escalated rules are scoped to Dutch and Danish
schemes, and the fixtures are `schemeID="9915"` / `AT`. What the corpus could **not** see, and what
no existing test caught, was that 2026.5 deleted `BR-CO-25` outright and rewrote two assertions whose
German translations then described rules that no longer exist. Both are fixed, and both now have
tests that read the shipped artefacts instead of a hand-typed list.

**You no longer have to remember the next deadline.**
`PeppolValidationStageTest.noNewerRuleSetIsAlreadyMandatory` enumerates the dated rule sets
phive-rules publishes and fails the build on the day one of them supersedes the pin, naming the
version and the date. Nothing about this line needs to be in your calendar any more.

### 3 · Dependabot PRs — 6 open, routine

With `main` protected and green, merge them one at a time on green checks. The
`actions/upload-artifact` 7.x major needs its changelog read first; the rest are patch/minor.

### 4 · Watch two suppression expiries

`.owasp-suppressions.xml`: kotlin-stdlib (2026-11-30) and the Tomcat examples-webapp CVE
(2026-10-31). When Spring Boot manages Tomcat ≥ 11.0.25 or Dependabot offers it, delete the
entry rather than renewing it.
