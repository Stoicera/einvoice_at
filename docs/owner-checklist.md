# Owner checklist — the tasks only you can do

Status verified: **2026-08-06** against the live repository, registry and production server.

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
| B11 · Auto-deploy | ✅ webhook secret set; the merge-triggered `main` run calls it — observe any `main` merge landing live ~6 min later | the `Deploy (Dokploy webhook)` job of the latest `main` run |
| C · v0.1.0 | ✅ tag + GitHub release, live validator linked at the top of the notes | `gh release view v0.1.0` |

---

## What is genuinely left, and for whom

### 1 · Off-site backup copy — owner, ~20 min, the only open operational risk

The nightly dump lands on the same disk as the database: that is a copy, not a backup.
Fastest good fix (the decision and the credentials are yours):

1. Order a **Hetzner Storage Box BX11** (€3.20/mo, 1 TB, no minimum term) in FSN1, enable SSH
   support.
2. On the production VPS: `ssh-keygen -t ed25519 -f /root/.ssh/storagebox -N ""`, register the
   key in the Storage Box panel, then append one line to the backup cron chain:

   ```bash
   rsync -a --delete -e "ssh -i /root/.ssh/storagebox -p23" \
     /var/backups/einvoice/ uXXXXXX@uXXXXXX.your-storagebox.de:einvoice/
   ```

3. Verify the same way §10 taught: list the box, pull one dump back, restore it into a scratch
   database.

Alternative if you prefer clicking over cron: Dokploy → Settings → S3 Destinations with a Hetzner
Object Storage bucket, then per-database scheduled backups in the panel. More moving parts, same
result; the cron path reuses the dump-and-verify pipeline you have already rehearsed.

### 2 · Peppol 2026.5 rule-set upgrade — next working session, before **2026-08-17**

The next hard date. A code change (phive rules bump plus a corpus re-run), not an ops task.

### 3 · Dependabot PRs — 6 open, routine

With `main` protected and green, merge them one at a time on green checks. The
`actions/upload-artifact` 7.x major needs its changelog read first; the rest are patch/minor.

### 4 · Watch two suppression expiries

`.owasp-suppressions.xml`: kotlin-stdlib (2026-11-30) and the Tomcat examples-webapp CVE
(2026-10-31). When Spring Boot manages Tomcat ≥ 11.0.25 or Dependabot offers it, delete the
entry rather than renewing it.
