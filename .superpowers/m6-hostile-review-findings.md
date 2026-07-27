# M6 hostile review — findings

**Reviewed:** M6 as merged — `dddc664..ca2cc09` (PR #9, `feat/m6-operations-polish`), 66 files,
+22 995/−102. Of those insertions, **17 905 are two Lighthouse JSON reports committed to the
repository root** (F2), which is itself the first finding.
**Date:** 2026-07-27. **Stance:** due diligence by a Viennese enterprise Java shop deciding whether
this repository is evidence of senior engineering. Local `./mvnw verify` is green on this checkout —
10 modules, **1078 tests** — counted from the build's own summary lines rather than taken from the
worklog. That is the starting point, not the verdict.

Findings are ordered by what would block a merge, not by how hard they are to fix.

---

## What is genuinely good, so the criticism has calibration

- **The observability seam is the right design and it is argued, not asserted.** `ValidationObserver`
  is a plain-Java port in `validation`; `MicrometerValidationObserver` is the `app`-side adapter.
  SPEC §2's "no Spring below `app`" survives, and the ArchUnit whitelist would fail if it did not.
  The same shape `ai-assist` already used for token cost — a repeated idiom, not a one-off.
- **`ObservabilityIT` tests the thing that would actually break.** It asserts the stages appear
  *through the real `ObservationRegistry` of the running context*, in pipeline order, nested under
  their caller. The named failure mode — `InvoicePipelineConfig` calling `new InvoiceValidator()`
  and every unit test still passing while the traces are empty — is exactly the one an eyeballed
  Grafana screenshot cannot catch.
- **Two observability defects were found by measurement, not reasoning, and both are recorded.**
  `tracing.enabled: false` not stopping the exporter (`54a4917`), and missing percentile histograms
  making every latency panel `NaN` (`da9b1cf`). The second is a subtlety most teams ship: a
  Micrometer timer publishes count/sum/max and no buckets, so `histogram_quantile` answers NaN
  forever. Both comments say "measured on the running stack", and the commits back that up.
- **`.owasp-suppressions.xml` is honest.** Two entries, each with a dated block comment, a stated
  reason, and — for the one that can decay — an `<until>`. The milestone's other CVE was *upgraded*
  (`opentelemetry-semconv` 1.41.1 → 1.43.0), which is the order the file's own policy demands. This
  is the file where portfolio repositories usually cheat, and this one does not.
- **The retention advisory lock picks `pg_try_advisory_xact_lock` for the right reason** — the
  session-scoped variant's unlock would run on a different pooled connection and silently fail,
  wedging the job. The IT proves it with a *real second session* holding the lock, not a mock.
- **The `AbstractPostgresIT` pool cap** (`292ee8a`) diagnoses a failure whose symptom is nothing like
  its cause: cached contexts × 10 connections exhausting `max_connections`, surfacing as an
  unrelated IT failing at startup. Fixed on the consuming side rather than by raising the limit.
- **`BuildInfoEndpointIT` asserts the negative surface** — no branch, no committer, no commit
  message, no environment — and caught `mode: simple` publishing an abbreviated (collidable) commit
  id. Asserting the *shape* of the value rather than its presence is what made that visible.
- **CI's two loud skips** (NVD key, Dokploy webhook) write a job-summary block rather than passing
  silently. A permanently red stage teaches people to ignore red; this is the correct trade.

The five findings below sit against that. None of them is the design; three are hygiene and two are
the gap between what a document promises and what the code does.

---

## P1 — Blocking

### F1 · Behind the documented reverse proxy, the rate limit is bypassable with one header line

`docs/deployment.md` §4 was titled "`SERVER_FORWARD_HEADERS_STRATEGY=framework` is not optional
here" and the production `.env` block set exactly that. `SECURITY.md` lists the resulting control
under **Spoofing**: "Forged client IP to escape rate limiting — `X-Forwarded-For` is ignored unless
the deployment explicitly declares a trusted proxy. Both directions are tested."

Both directions are tested. There is a third.

A proxy **appends**. It takes whatever `X-Forwarded-For` arrived and adds the peer it actually
observed, so a request the client sends as `X-Forwarded-For: 198.51.100.1` reaches the application
as `X-Forwarded-For: 198.51.100.1, 203.0.113.77` — rightmost written by the proxy, everything left
of it written by the caller. Spring's `ForwardedHeaderFilter`, which is what `framework` installs,
resolves the client from the **leftmost** entry:

```java
// org.springframework.web.util.ForwardedHeaderUtils#parseForwardedFor  (spring-web 7.0.8)
String forHeader = headers.getFirst("X-Forwarded-For");
if (StringUtils.hasText(forHeader)) {
    String host = StringUtils.tokenizeToStringArray(forHeader, ",")[0];
```

`RateLimitFilter` keys its buckets on `getRemoteAddr()`, which is now whatever the caller wrote. In
the production topology the repository documents, the per-IP limit on the anonymous validator — an
open, CPU-heavy endpoint, the platform's front door — costs one header line per request to bypass,
and it does so **silently**: the limiter still runs, still logs, still looks like it is working.

`ForwardedHeadersTrustedIT` could not see this because every request it sent carried a
single-entry header, which is the one case where leftmost and rightmost agree.

Severity is P1 rather than P2 because of where the claim lives. A missing control is a gap; a
control that `SECURITY.md` tabulates, that two integration tests appear to pin, and that a reader
would reasonably take at face value is worse than a gap — it spends the reviewer's trust.

**Fixed.** Test first: `ignoresAddressesPrependedByTheClient` sends three requests from one real
client, each claiming a different upstream, and demands they share a bucket. Under `framework` it
fails with all three admitted (verified: `expected: 429 but was: 200`). The strategy is now
`native` — Tomcat's `RemoteIpValve`, which walks the chain **right to left**, discarding entries
matching `server.tomcat.remoteip.internal-proxies` (Boot's default already covers loopback and every
RFC 1918 range, so a Traefik on a Docker bridge network needs no further configuration) and stopping
at the first address that is not one. What the caller prepended is left behind. The pre-existing
per-client test passes unchanged under `native`, which is what shows the fix costs no coverage.

What `native` gives up is `X-Forwarded-Prefix`, which `RemoteIpValve` does not read. This
application is served at the root of its own host, so there is no prefix to honour; if that ever
changes the answer is a prefix-aware valve, not `framework`. Documented in `application.yml`,
`.env.example`, `docker-compose.yml`, `RateLimitFilter`, `README.md`, `docs/SPEC.md`,
`docs/deployment.md` §4 (with the chain diagram and the Traefik-side note that
`forwardedHeaders.insecure=true` must not be set) and `SECURITY.md`.

---

## P2 — Fix before this is shown to anyone

### F2 · 1.2 MB of Lighthouse CI output committed to the repository root

`lighthouse--.json` (8 925 lines) and `lighthouse-validator-.json` (8 980 lines) — run-scoped
reports against `http://localhost:8080/`, fetched `2026-07-27T06:09:20Z`, Lighthouse 12.8.2. They
are in `.gitignore` under no pattern, in `.dockerignore` under no pattern, and therefore in every
Docker build context as well as in `git log` forever.

This repository's README opens by calling itself a public portfolio piece. The root directory is the
first thing a reviewer sees, and what it shows is two machine-generated JSON blobs with malformed
names sitting beside `pom.xml`. The CI job that produces them already uploads them as build
artefacts with a 14-day retention — the correct home — so nothing is lost by removing them.

The malformed names are their own small defect. The workflow computes:

```bash
name="$(echo "$path" | tr -c 'a-zA-Z0-9' '-')"
```

`tr -c` translates every byte outside the set, and `echo`'s trailing **newline** is one of them. So
`/` becomes `--` and `/validator` becomes `-validator-`. Harmless, and precisely the kind of detail
a due-diligence reviewer reads as "nobody looked at the output".

**Fixed.** Both files removed from the tree; `lighthouse-*.json` added to `.gitignore` and
`.dockerignore`; the workflow uses `printf '%s'`.

### F3 · The one unpinned dependency in a workflow that SHA-pins everything else

Every GitHub Action in `ci.yml` is pinned to a 40-character commit SHA with the tag in a trailing
comment — `actions/checkout@3d3c42e5…  # v7.0.1` — which is careful, deliberate supply-chain
discipline. Then:

```bash
npx --yes lighthouse@12 "http://localhost:8080${path}" ...
```

`lighthouse@12` resolves to whatever npm currently publishes for 12.x, downloaded and executed on a
runner that holds `GITHUB_TOKEN`, with `--yes` suppressing the one prompt that would otherwise ask.
The committed report says the last resolution was 12.8.2; nothing in the repository says it will be
tomorrow. A repository that pins `actions/checkout` to a SHA and leaves this open has a stated
threat model it does not apply uniformly.

**Fixed.** Pinned to `12.8.2` via a `LIGHTHOUSE_VERSION` env var, so a bump is a reviewable diff.

### F4 · The backup drill verifies five tables because five is what existed when it was written

`BackupRestoreDrillIT.TABLES` and `scripts/restore.sh`'s post-restore report both carry the literal
list `tenant invoice report api_key audit_event`. Nothing ties either to the schema.

Add a sixth table in `V3__*.sql` and both go quietly narrower than the database: the drill still
passes, having verified everything it was told about, and `restore.sh` prints five row counts and
says nothing about the missing one. The first person to learn the new table was outside the backup
verification finds out during a restore — which is the exact failure automating a drill exists to
remove, reintroduced by the drill's own constant.

**Fixed.** `coversEveryTableTheSchemaActuallyHas` reads `information_schema` and asserts it matches
`TABLES`, with a failure message naming the fix. Verified by removing `audit_event` from the list
and watching it fail with `["api_key", "audit_event", "invoice", "report", "tenant"]` against
`["audit_event"]`. `restore.sh` now derives its list from the restored database instead of carrying
a literal — confirmed end to end against a scratch Postgres: adding a `peppol_dispatch` table made
it appear in the report with no script change.

`restore.sh` also checked `pg_restore` on `PATH` but called `psql` for the row counts without the
same check. On a host with one and not the other, `set -e` turns a **successful** restore into a
non-zero exit — the wrong signal at the worst possible moment. Both binaries are now checked before
anything destructive runs.

---

## P3 — Worth correcting, not worth blocking

### F5 · A compose comment describes a port binding that does not exist

```yaml
    ports:
      # 3200 is Tempo's query API (Grafana reads it); 4318 is the OTLP/HTTP receiver the app writes
      # to. Both bound to loopback — nothing here authenticates.
      - "127.0.0.1:${TEMPO_PORT:-3200}:3200"
```

Only 3200 is published. 4318 is reached over the compose network by service name and is not exposed
to the host at all — which is *better* than the comment claims, and that is the problem: the
sentence reads as a deliberate decision to expose an unauthenticated receiver on loopback, in a file
whose neighbouring comments are load-bearing security statements a reader is being asked to trust.

**Fixed.** The comment now says what the file does and why 4318 is deliberately not published.

### F6 · No M6 findings file, in a repository that carved out a `.gitignore` exception for one

`.gitignore` carries `!.superpowers/m*-hostile-review-findings.md` with a comment explaining that
this exception exists *because* M5 discovered every previous findings file had been silently
swallowed while three commit messages claimed otherwise. M1, M4 and M5 are committed. M6 had none.

**Fixed.** This document.

---

## Checked and found sound

Recorded so the absence of a finding is visible as a decision rather than as an oversight:

- **Bulk deletes.** `deleteByCreatedAtBefore` / `deleteByOccurredAtBefore` are `@Modifying @Query`
  JPQL deletes returning row counts, not derived `deleteBy…` methods — so the retention purge does
  not load every expiring row into the persistence context first. The `AuditEventRepository` Javadoc
  says exactly why. Suspected, verified, wrong.
- **Grafana metric names.** The dashboard queries `einvoice_pipeline_milliseconds_bucket`, not
  `_seconds_`. Correct: Micrometer's `OtlpMeterRegistry` uses milliseconds as its base time unit,
  unlike the Prometheus registry. The commit history shows this was measured.
- **`PipelineObservations.observe` overload resolution.** The block lambda in `InvoiceService.create`
  is void-compatible only, so it binds to the `Runnable` overload unambiguously.
- **`.git` in the Docker build context.** Deliberate (F-less): `git-commit-id-maven-plugin` runs
  inside the builder stage, and only the jar is copied into the runtime stage, so no history ships.
  `.env` remains excluded. CI asserts the built image can name its own commit.
- **`InvoiceValidator`'s size guard is not observed.** Correct — it is a length comparison, and the
  span would cost more than the check.
- **README's "1078 tests".** Re-counted from this checkout's build output: 1078.
- **Grafana anonymous admin and Tempo running as root.** Both loopback-only, both in a profile
  `docs/deployment.md` explicitly does not deploy, both justified in place and in `SECURITY.md`'s
  out-of-scope list. Acceptable as scoped.
- **No `TODO`/`FIXME`/`XXX` anywhere in the tree.** ENGINEERING_STANDARDS §1.6 satisfied.

---

## Verdict

M6 is the strongest milestone in this repository. The observability work is properly layered, the
two defects it found in itself were found by running the stack rather than by reading the code, and
the CVE handling is the honest version of a thing most repositories fake.

F1 is the finding that matters, and it is instructive rather than embarrassing: the design was
right, the tests were right for what they tested, and the gap was a single assumption about which
end of a forwarded chain is evidence. It is also the second time in this repository that a control
was correct in the abstract and applied to the wrong scope (M5's F4 argued the money case and then
exempted the authenticated routes). That pattern is worth naming: **when a control is derived from
an argument, re-check that it covers every case the argument covers.**

The two milestone items the worklog names as owner-blocked — the unprovisioned VPS and the untagged
`v0.1.0` — are honestly stated in `docs/MILESTONES.md` and were not re-litigated here.
