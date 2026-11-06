# Launch package — order, roles, checklist

Status: **Entwurf** (2026-11-06, replaces `checklist.md` of 2026-10-15). No date is set; the
founders choose the day. Nothing in `docs/launch/` has been posted, submitted, scheduled or
published.

| File | Channel | Who posts | Who answers |
|---|---|---|---|
| `show-hn.md` | Hacker News, Show HN | Sebastian | Sebastian, six hours, then daily |
| `reddit.md` | r/selfhosted, r/opensource, r/java, r/Austria (rules unread, see file) | Sebastian | Sebastian |
| `devto.md` | Dev.to article (canonical to the Werkbank issue if that runs first) | Sebastian | Sebastian |
| `linkedin.md` | Post A Sebastian, Post B Raphael, Post C day 2, Company Page reposts A | each his own | each his own |
| `werkbank.md` | Werkbank issue (Ghost draft still to be created from this file) | Sebastian | by mail |
| `release-notes.md` | GitHub release `v0.3.0` (tag not yet cut) | Sebastian or Raphael | issues |

All channels go out on **the same day**. Times are Vienna local time and an assumption, not a
measurement: Show HN in the US morning, LinkedIn in the Austrian morning. Pick a Tuesday,
Wednesday or Thursday that both founders keep free.

## The week before

| Done | Step | Who |
|---|---|---|
| [ ] | Merge the branch `launch/2026-11-06` (PR against `main`); watch the `main` run to the end, including the deploy job; then `curl -fsS https://einvoice.sebastiankern.net/actuator/health` | Sebastian |
| [ ] | CI badge green: as of 2026-11-06 it shows **failing** (scheduled OWASP run 41430102198 of 2026-11-02, DOMPurify in the swagger-ui webjar — fixed on this branch). The badge turns green with the first completed push run on `main` after the merge | Sebastian |
| [ ] | Cut `v0.3.0` from `release-notes.md`: `git tag -a v0.3.0`, GitHub release with that text | Sebastian |
| [ ] | Merge or park PR #61 (docs, green, mergeable) so nothing has to be deployed on launch day; PR #62 stays draft | Sebastian |
| [ ] | Social preview uploaded: Settings → General → Social preview → `docs/assets/social-preview.png` (no API for this) | Sebastian |
| [ ] | Every draft in `docs/launch/` read and changed where it does not sound like the person posting | Sebastian, Raphael |
| [ ] | Rules and sidebar of r/selfhosted, r/opensource, r/java and r/Austria read in a browser; `reddit.md` adapted or the sub dropped (the agent could not fetch them: HTTP 403 on 2026-10-15 and 2026-11-06) | Sebastian |
| [ ] | Werkbank: create the Ghost draft from `werkbank.md`, set the Dev.to `canonical_url` to it | agent, then Sebastian |
| [x] | Quickstart run from a fresh clone on a machine profile that had never built the project (2026-11-06, see baseline below); the port collision it found is in the README | agent |
| [ ] | Baseline re-read on the morning of the launch (below) | agent |

## Launch day

| Time | Step | Who |
|---|---|---|
| 08:00 | Pre-flight: health `UP`; one upload in the browser shows a German finding; the README curl line answers `AT-B2G-01`; CI on `main` green; README renders with the screenshot | Sebastian |
| 08:00 | No merges to `main` until the next morning (a push redeploys and resets the request counters) | both |
| 08:30 | LinkedIn post A (`linkedin.md`), link in the first comment right away; Company Page reposts | Sebastian |
| 10:00 | LinkedIn post B | Raphael |
| 14:30 | Werkbank issue published (`werkbank.md`) | Sebastian |
| 15:00 | Show HN submitted (`show-hn.md`), author's first comment within two minutes | Sebastian |
| 15:15 | r/selfhosted post | Sebastian |
| 16:00 | Dev.to article published (`devto.md`), canonical URL set to the Werkbank issue | Sebastian |
| 16:15 | r/opensource post | Sebastian |
| 16:30 | r/Austria post, only if its rules allow it | Sebastian |
| 15:00–21:00 | **Six hours of answers:** every comment on HN, Reddit and Dev.to answered by the author; critique gets thanks plus a fix or an issue link | Sebastian |
| all day | LinkedIn comments on each profile answered by the person who posted; demo health checked every two hours; if the demo fails, fix it before answering anything else | Raphael |
| 21:00 | Measurement T+6h (below); issues opened from comments, labelled | Sebastian |
| day 2, 09:00 | r/java post (its rule may require a write-up — then link the Dev.to article); LinkedIn post C from the real numbers | Sebastian |

Never: ask anyone to upvote or star, share the HN link with a request to vote, post the same text
to further subs the same day, promise features in comments, name competitors, claim user or
customer numbers.

Who may offer a conversation: anyone asking about running it in a company gets the Erstgespräch
link (https://termin.stoicera.com/sebastian, 20 minutes, online) or office@stoicera.com — as a
reply to their question, never unprompted in a thread.

## Baseline (measured 2026-11-06, 22:30 Vienna)

| Measure | Value | How |
|---|---|---|
| Stars, forks, watchers | **1 / 0 / 0** | `gh api repos/Stoicera/einvoice_at` |
| Open issues from outside | 0 (12 open items are all pull requests: 10 Dependabot, #61, #62) | `gh pr list`, `gh issue list` |
| Live health | `UP`, 0.68 s | `curl https://einvoice.sebastiankern.net/actuator/health` |
| README curl line | `valid: false`, `AT-B2G-01` at `/eb:Invoice`, 1.0 s round trip | verbatim from the README |
| Public pages | `/validator` 200, `/swagger-ui.html` → 302 → `/swagger-ui/index.html`, `/.well-known/security.txt` 200, `/app` → 302 to login, `/problems/rate-limited` → 302 to `docs/problems.md` | `curl -o /dev/null -w` |
| Lighthouse (desktop, Lighthouse 12, headless Chrome from a laptop) | `/` 100/100/100/100 · `/validator` 99/100/100/100 (performance, accessibility, best practices, SEO) | `npx lighthouse@12` |
| Quickstart from a fresh clone (`git clone` of `main` at edcd3e7, macOS, OrbStack, no image of this project present) | 312 s from clone to `"status":"UP"`, image build included; first attempt failed on `Bind for 0.0.0.0:5432 failed: port is already allocated` (another PostgreSQL on the laptop), second with `POSTGRES_PORT=5433` healthy after 61 s; both `samples/` files `valid: true`; dev-realm token issued; `GET /api/v1/invoices` 200 | `/tmp` script, stack removed afterwards |
| CI | last push run on `main` **cancelled** (2026-10-22, three Dependabot merges in 16 min cancelled each other; last completed `main` run is for b64b402); scheduled run 2026-11-02 **failed** (DOMPurify CVEs in swagger-ui 5.32.11); PR #61 fully green against `main` on 2026-10-21 | `gh run list` |
| Badge | **failing** | `badge.svg` |
| Community profile | 100 % | `gh api .../community/profile` |

## What to measure after posting

Write each number with date and time.

| Metric | How | When |
|---|---|---|
| Stars, forks | `gh repo view Stoicera/einvoice_at --json stargazerCount,forkCount` | before posting, T+1h, T+6h, T+24h, T+7d |
| Issues and PRs from outside | `gh issue list --state all`, `gh pr list --state all`, count authors other than the founders | T+24h, T+7d |
| Repo views, clones, referrers | `gh api repos/Stoicera/einvoice_at/traffic/views`, `.../traffic/clones`, `.../traffic/popular/referrers` (GitHub keeps 14 days) | T+24h, T+7d |
| Validator calls | `/actuator/metrics/http.server.requests` with `tag=uri:/api/v1/validate` and `tag=uri:/validator/pruefen`, `tag=method:POST`. Not public (401 without credential, checked 2026-11-06); read it with operator access and confirm the access path in the week before. The counter is in memory and starts at zero after every deploy, so read it before posting and after | before posting, T+6h, T+24h |
| HN position and comments | the submission page | T+1h, T+6h |
| Reddit, LinkedIn, Dev.to, Werkbank | upvotes, comments, impressions, members as each platform shows them | T+24h |
| Erstgespräche booked, mails to office@ | Cal dashboard, office mailbox — the number this launch exists for | T+24h, T+7d |

After seven days: one paragraph — what brought visitors (referrers), which question came up most,
what was fixed because of the launch, how many conversations were booked — in `docs/worklog.md`
and in the SSOT scoreboard.
