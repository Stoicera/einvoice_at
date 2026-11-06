# Release notes — v0.3.0 (draft)

Status: **Entwurf** (2026-11-06). No tag exists yet; the founders cut the tag and paste this text
into the GitHub release. Written from the first-parent history `v0.2.0..main` plus the branch
`launch/2026-11-06`, each item with its pull request or commit so it can be checked. Version
reasoning: a new public endpoint (`/.well-known/security.txt`) and a security fix without an
API-visible change — a minor version under SemVer, so `v0.3.0`, not `v0.2.1`.

## v0.3.0 — Launch readiness

Everything a stranger meets first — README, security contact, community files, the public
`security.txt`, the launch material — and one dependency fix that turned the scan red on its own.

### Added

- **`/.well-known/security.txt`** (RFC 9116) on the public host, naming the same two contacts as
  `SECURITY.md` (GitHub private vulnerability reporting, office@stoicera.com), `Expires`
  2027-10-01. The anonymous filter chain now permits `/.well-known/**`; `.txt` static files are
  served as `text/plain;charset=utf-8`. Integration test in `PublicWebIT`. (#48)
- **README first screen**: the validator report with a German finding directly under the one-line
  summary, "Try it in 30 seconds" (browser, one curl line against the live API, self-host), a
  release badge. The curl line was run verbatim on 2026-10-15 and 2026-11-06. (branch
  `launch/2026-11-06`, cherry-picked from `marketing/2026-10-15`)
- README sections **Why this exists**, **How it compares**, **Roadmap and non-goals**,
  **For companies / Für Unternehmen**. (#46)
- **`docs/enterprise.md`** (German): three operating models, security and privacy properties as
  measured, backup with read-back, service hours, limits, integration path, IT checklist. No
  prices. (#46)
- **`docs/demo.md`** (German): the ten-minute demo script with sample files and expected verdicts,
  a numbers sheet with measurement dates, twelve objections with answers. (#46)
- **`scripts/social-preview.mjs`** renders the GitHub social preview (`docs/assets/social-preview.png`,
  1280×640) with playwright-core; the PNG is committed so the next release can regenerate it.
  (branch `launch/2026-11-06`)
- **Launch package in `docs/launch/`**: Show HN, Reddit, Dev.to, LinkedIn, Werkbank drafts, these
  release notes and the launch-day checklist (`docs/launch/README.md`). Status Entwurf, nothing
  posted. (branch `launch/2026-11-06`)

### Changed

- **CI: the OWASP Dependency-Check job runs weekly on `main`** (Monday 03:00 UTC, that job alone) so
  the week-scoped NVD cache is warm before the first pull request of the week; a cold sync took
  57 min on 2026-10-08, a warm one 1–3 min. (#48)
- **CI: superseded runs are cancelled on pull-request branches only.** On `main` every push now
  runs to the end. Before, three Dependabot merges within 16 minutes on 2026-10-22 cancelled each
  other's runs, so the newest `main` commits were never built, verified or deployed. (branch
  `launch/2026-11-06`)
- README quickstart notes the one thing that broke it on a developer machine: a PostgreSQL already
  listening on 5432. `POSTGRES_PORT` in `.env` moves the published port. (branch `launch/2026-11-06`)
- `docs/VERMARKTUNG.md` removed from the public repository; it belongs in the private SSOT. (#49)

### Fixed

- **Security scan red on `main` since 2026-11-02** (scheduled run 41430102198): `swagger-ui-bundle.js`
  inside the swagger-ui 5.32.11 webjar embeds DOMPurify 3.4.12, flagged with CVE-2026-116803 and
  CVE-2026-116804 (CVSS 10.0, fixed in DOMPurify 3.4.17). The webjar `org.webjars:swagger-ui` is
  now pinned to 5.34.1, which embeds DOMPurify 3.4.17 (verified by extracting the jar on
  2026-11-06); springdoc stays at 3.1.0. No suppression was added. (branch `launch/2026-11-06`)

### Security and dependencies

Dependabot, each merged alone behind the OWASP gate (2026-10-22): ph-ebinterface 8.1.0 → 8.1.1
(#53), opentelemetry-semconv 1.43.0 → 1.44.0 (#56), gatling-charts-highcharts 3.15.1 → 3.16.0
(#57), actions/setup-java 5.7.0 → 6.1.0 (#60), actions/upload-artifact 4.6.2 → 7.0.2 (#58),
docker/setup-buildx-action 4.3.0 → 4.4.1 (#59), docker/build-push-action 7.3.0 → 7.4.0 (#54),
eclipse-temurin base image digest (#52).

### What breaks

Nothing for API callers: no endpoint, field or problem `type` changed since v0.2.0. Self-hosters
who pinned `org.webjars:swagger-ui` themselves get 5.34.1 from the root POM now; the Swagger UI
path (`/swagger-ui/index.html`) is unchanged.

### Not in this release

Pull request #62 (correction proposals with provenance for `AT-B2G-01`, `PEPPOL-EN16931-R010/R020`)
is a draft and stays out until it is reviewed. The remaining Dependabot majors (#55 phive 12.2.0,
#63 pitest 1.30.1, #64–#67) are open and need their own run each.
