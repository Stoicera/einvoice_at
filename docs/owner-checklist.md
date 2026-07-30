# Owner checklist — the tasks only you can do

Status verified: **2026-07-30** against the live repository and registry.
Everything else in this project is done. This is the complete list of what is waiting on you.

Ordered so the cheap things come first and nothing blocks on something below it.

```
  A · Repository polish        10 min   ← start here, three quick wins
  B · Deploy the live instance  2–3 h   ← the real work
  C · Tag and release v0.1.0    10 min  ← after B, so the release can link the demo
```

**Total: about 3 hours, most of it in B.** When all three are done, milestone M6 is formally closed
and there is nothing left that a machine or a button is waiting for.

---

## A · Repository polish — 10 minutes

Three small things. None depends on the others; none depends on the deployment. Do them now, because
they improve a public portfolio repository today and each one is under five minutes.

### A1 · Fill in the GitHub "About" box · 5 min

**What:** The description, website and topics shown at the top-right of the repository page.

**Why:** That box is read *before* your README — it is what shows in search results, in the
organisation's repository list, and in the preview card when the link is shared. Yours is currently
empty, which reads as an abandoned repository regardless of what is inside. This is the best
visibility-per-minute item on the entire list.

**Verified state:** `description: ""`, `homepageUrl: ""`, `repositoryTopics: null`. All three empty.

**Do this** — the repository page → the gear icon next to *About* → paste:

- **Description:**
  > Austrian e-invoicing platform in Java 25 / Spring Boot 4 — generates, validates and converts ebInterface 6.1 and Peppol BIS 3.0 (UBL), with a German-language validation report.
- **Website:** `https://einvoice.sebastiankern.net` *(leave empty until B is done, then come back)*
- **Topics:** `e-invoicing` `ebinterface` `peppol` `ubl` `en16931` `austria` `java` `spring-boot` `postgresql` `keycloak`

Or from the terminal:

```bash
gh repo edit Stoicera/einvoice_at \
  --description "Austrian e-invoicing platform in Java 25 / Spring Boot 4 — generates, validates and converts ebInterface 6.1 and Peppol BIS 3.0 (UBL), with a German-language validation report." \
  --add-topic e-invoicing --add-topic ebinterface --add-topic peppol --add-topic ubl \
  --add-topic en16931 --add-topic austria --add-topic java --add-topic spring-boot \
  --add-topic postgresql --add-topic keycloak
```

**Done when:** `gh repo view Stoicera/einvoice_at --json description,repositoryTopics` prints them back.

---

### A2 · Turn on branch protection for `main` · 3 min

**What:** Require the CI checks to pass and forbid force-pushes on `main`.

**Why:** `main` is currently unprotected — I verified it returns "Branch not protected". Your CI is
genuinely good, and right now nothing makes it binding: a tired evening `git push --force` would
rewrite the history of a public portfolio repository with no confirmation. Protection is also
something reviewers look for; an unprotected main branch on a repo that advertises a five-job
pipeline is a small contradiction.

**Do this:**

```bash
gh api -X PUT "/repos/Stoicera/einvoice_at/branches/main/protection" \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "Build, lint, test",
      "Mutation tests (core, mapping, validation, formats-ebinterface, formats-ubl, ai-assist)",
      "Security scan (OWASP Dependency-Check)",
      "Browser E2E + load scenario",
      "Docker image build and publish"
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

Those five names are the exact check names from your last run on `main`. **Do not add
`Deploy (Dokploy webhook)`** — that job only runs on a push to `main`, never on a pull request, so
requiring it would leave every future PR waiting forever for a check that cannot report.

`"enforce_admins": false` and `"required_pull_request_reviews": null` are deliberate: you are a solo
maintainer, so requiring a second reviewer would block you permanently, and admin enforcement would
stop you fixing a broken `main` directly. The protection that matters here is *checks must pass* and
*no force-push*, both of which apply.

**Done when:**

```bash
gh api "/repos/Stoicera/einvoice_at/branches/main/protection" --jq '.required_status_checks.contexts'
```

prints the five names instead of a 404.

---

### A3 · Confirm nothing else is waiting on you · 2 min

Checked so you do not have to wonder:

| Item | State |
|---|---|
| `NVD_API_KEY` repository secret | ✅ Present since 2026-07-25 — the CVE scan is a real gate, as the README claims |
| ebInterface portal Abnahme | ✅ Still valid — you re-confirmed 2026-07-25, and the sample XML has not changed since 2026-07-24 |
| CI on `main` | ✅ Green at `9a4c04e`, all six jobs |
| `DOKPLOY_DEPLOY_WEBHOOK` | ❌ Absent — this is task **B11**, not a separate item |

---

## B · Deploy the live instance — 2 to 3 hours

**What:** A running `https://einvoice.sebastiankern.net` on your production VPS, managed from your
existing Dokploy panel.

**Why it matters:** This is the last open acceptance criterion of milestone M6 ("Live-Instanz
erreichbar"). It also gives you a demo link for the About box, and it is the only way to observe the
one half of the M6 security fix that cannot be proven on a laptop — that a forged `X-Forwarded-For`
buys a caller no extra rate-limit allowance. Traefik's source says it will not; two commands will
show it.

**Everything on the code side is ready and waiting.** CI builds the image, pushes it to GHCR as both
`main` and `sha-<commit>`, and calls the Dokploy webhook the moment the secret exists. No workflow
edit is needed.

> ### Follow **[deployment.md](deployment.md)** top to bottom.
> Eleven steps, each with a check to run before you move on. Do not skip the checks — they exist so
> that a mistake surfaces one step after you make it rather than five.

The steps, so you can see the shape and estimate your evening:

| # | Step | Time | Note |
|---|---|---|---|
| 1 | Check the production VPS has room | 5 min | Needs ~2 GB RAM free |
| 2 | Hetzner firewall: 22, 80, 443 only | 5 min | Keeps the databases off the internet |
| 3 | Make the GHCR image public | 5 min | **It is private right now** — Dokploy cannot pull it until you do this |
| 4 | Two Cloudflare `A` records, DNS-only | 5 min | Must be done *before* Dokploy, or certificates fail |
| 5 | Dokploy project, production server selected | 5 min | The easiest thing to get silently wrong |
| 6 | Two PostgreSQL services | 10 min | One for the app, one for Keycloak |
| 7 | Keycloak: deploy, then realm and clients | 45 min | The longest step by far |
| 8 | The application | 20 min | One environment block to paste |
| 9 | Prove it works | 15 min | Five checks, including the `X-Forwarded-For` proof |
| 10 | Backups and one rehearsed restore | 20 min | An untested backup is a hope |
| 11 | `DOKPLOY_DEPLOY_WEBHOOK` → automatic deploys | 10 min | After this, every merge is live in ~6 min |

**Three things that will bite you if you skim** — all three are called out in place, but they are the
ones worth knowing before you start:

1. **DNS points at the production VPS, not the Dokploy panel VPS.** Traefik runs on the production
   server. This is the single most common way this setup goes wrong.
2. **The GHCR package is private.** I verified an anonymous pull is refused today. Dokploy will fail
   with a message that sounds like a typo in the image name.
3. **Keycloak's run command is `start`, not `start --optimized`.** The stock image has no pre-built
   configuration, so `--optimized` fails. An earlier version of the deployment doc told you to use
   it — that was wrong, and it is fixed.

**Done when:** all five checks in [deployment.md §9](deployment.md#9-prove-it-actually-works) pass,
including the browser login, and an empty commit pushed to `main` reaches the live instance by itself.

**Then go back to A1** and set the Website field to `https://einvoice.sebastiankern.net`.

---

## C · Tag and release v0.1.0 — 10 minutes

**What:** The `v0.1.0` git tag and a GitHub Release.

**Why after B:** `MILESTONES.md` closes M6 on a live instance *and* the release, and the release notes
are considerably better with a working demo link in them. There is no technical dependency — do it
before B if you prefer — but the version that ships with a live link is the one worth publishing.

**Verified state:** no tags exist yet. `CHANGELOG.md` is written, with a complete `[0.1.0] — 2026-07-27`
section ready to become the release body.

**Do this** — from a clean, up-to-date `main`:

```bash
git checkout main && git pull
git status --porcelain          # must print nothing

git tag -a v0.1.0 -m "einvoice-at v0.1.0 — first release"
git push origin v0.1.0
```

Then create the release, taking the notes from the CHANGELOG section you already wrote:

```bash
# Extract the [0.1.0] section; the sed drops the link-reference footer, which belongs to the
# CHANGELOG file and not to a release body.
awk '/^## \[0\.1\.0\]/{f=1; next} /^## \[/{f=0} f' CHANGELOG.md \
  | sed '/^\[.*\]: http/d' > /tmp/v0.1.0-notes.md

gh release create v0.1.0 \
  --title "v0.1.0 — Austrian e-invoicing: generate, validate, convert" \
  --notes-file /tmp/v0.1.0-notes.md \
  --verify-tag
```

Open `/tmp/v0.1.0-notes.md` first and read it — it is what the world sees. If B is done, add a line
at the top linking the live validator.

**Done when:** `gh release view v0.1.0` shows the release, and the repository page shows
*Releases · 1* in the sidebar.

---

## After all three

Milestone M6 is closed on both halves of its acceptance criterion: a reachable live instance, and a
quickstart a stranger can follow in five minutes.

**The next hard date is not on this list and is not yours:** the Peppol 2026.5 rule-set upgrade,
before **2026-08-17**. That is a code change, and it belongs to the next working session.
