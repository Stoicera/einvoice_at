# Launch day — checklist

Status: **Entwurf** (2026-10-15). No date is set; the founders choose the day. Nothing in
`docs/launch/` has been posted.

All channels go out on **the same day**. The agent prepares; the founders post and answer. Times
are Vienna local time and an assumption, not a measurement: Show HN in the US morning, LinkedIn in
the Austrian morning. Pick a Tuesday, Wednesday or Thursday that both founders keep free.

## The week before

| Done | Step | Who |
|---|---|---|
| [ ] | Social preview uploaded: Settings → General → Social preview → `docs/assets/social-preview.png` | Sebastian |
| [ ] | Every draft in `docs/launch/` read and changed where it does not sound like the person posting | Sebastian, Raphael |
| [ ] | Rules and sidebar of r/selfhosted and r/Austria read; `reddit.md` adapted or the sub dropped | Sebastian |
| [ ] | Quickstart run from a fresh clone on a machine that has never built the project; what breaks is fixed first | anyone but the author |
| [ ] | Open PRs merged or parked, so nothing has to be deployed on launch day | Sebastian |
| [ ] | Baseline written down (below) | agent |

## Launch day

| Time | Step | Who |
|---|---|---|
| 08:00 | Pre-flight: `curl -fsS https://einvoice.sebastiankern.net/actuator/health` is `UP`; one upload in the browser shows a German finding; the README curl line answers `AT-B2G-01`; CI on `main` green; README renders with the screenshot | Sebastian |
| 08:00 | No merges to `main` until the next morning (a push redeploys and resets the request counters) | both |
| 08:30 | LinkedIn post A (`linkedin.md`), link in the first comment right away | Sebastian |
| 10:00 | LinkedIn post B | Raphael |
| 15:00 | Show HN submitted (`show-hn.md`), author's first comment within two minutes | Sebastian |
| 15:15 | r/selfhosted post | Sebastian |
| 15:30 | r/Austria post | Sebastian |
| 16:00 | Dev.to article published (`devto.md`), canonical URL set if it ran in the Werkbank first | Sebastian |
| 15:00–21:00 | **Six hours of answers:** every comment on HN, Reddit and Dev.to answered by the author; critique gets thanks plus a fix or an issue link | Sebastian |
| all day | LinkedIn comments on each profile answered by the person who posted; demo health checked every two hours; if the demo fails, fix it before answering anything else | Raphael |
| 21:00 | Measurement T+6h (below); issues opened from comments, labelled | Sebastian |

Never: ask anyone to upvote or star, share the HN link with a request to vote, post the same text to
further subs the same day, promise features in comments.

## What to measure

Write each number with date and time. Baseline (2026-10-15): **1 star, 0 forks, 0 open issues**
(`gh repo view`, `gh issue list`).

| Metric | How | When |
|---|---|---|
| Stars, forks | `gh repo view Stoicera/einvoice_at --json stargazerCount,forkCount` | before posting, T+1h, T+6h, T+24h, T+7d |
| Issues and PRs from outside | `gh issue list --state all`, `gh pr list --state all`, count authors other than the founders | T+24h, T+7d |
| Repo views, clones, referrers | `gh api repos/Stoicera/einvoice_at/traffic/views`, `.../traffic/clones`, `.../traffic/popular/referrers` (GitHub keeps 14 days) | T+24h, T+7d |
| Validator calls | the application's request counter: `/actuator/metrics/http.server.requests` with `tag=uri:/api/v1/validate` (API) and `tag=uri:/validator/pruefen` (browser form), `tag=method:POST`. Not public (401 without credential, checked 2026-10-15) — read it with operator access; confirm the exact access path in the week before. The counter is in memory and starts at zero after every deploy, so read it before posting and after. The validator itself logs nothing per upload, by design | before posting, T+6h, T+24h |
| HN position and comments | the submission page | T+1h, T+6h |
| Reddit, LinkedIn, Dev.to | upvotes, comments, impressions as each platform shows them | T+24h |

After seven days: one paragraph — what brought visitors (referrers), which question came up most,
what was fixed because of the launch — in `docs/worklog.md`.
