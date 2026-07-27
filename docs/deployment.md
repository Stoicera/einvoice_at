# Deployment — einvoice-at on Hetzner + Dokploy

Status: 2026-07-27 · Milestone M6 · Target: one Hetzner Cloud VPS running [Dokploy](https://dokploy.com)

This document describes how this application is deployed, how it is backed up, and how a restore is
rehearsed. It is written to be followed by someone who has not read the source.

> **What is verified and what is not.** Every command in the *Backup and restore* section was
> executed against a real PostgreSQL 17 and its output is reproduced below; the drill is additionally
> automated as `BackupRestoreDrillIT` and runs on every build. The **provisioning steps** are the
> intended procedure and have not been executed against a live Hetzner VPS at the time of writing —
> that is the one part of M6 that needs a machine and a card. The document says so here rather than
> implying otherwise; see `docs/worklog.md` for the milestone's honest accounting.

---

## 1. Why this target

Hetzner Cloud plus Dokploy is chosen for the same reason the rest of this repository is boring: it is
one VPS, one Docker host, Traefik for TLS, and a webhook for deploys. There is no Kubernetes, no
service mesh and no managed control plane for an application that is deliberately a modular monolith
(ADR-0002). ENGINEERING_STANDARDS §7 names this target.

**Sizing.** The application is CPU-bound in exactly one place — the Peppol Schematron run, which is
XSLT and measured at ~29 ms p95 for a single document. A CX22 (2 vCPU / 4 GB) is enough for the
application, PostgreSQL and Keycloak together on a demonstration instance. Give Keycloak its own
memory headroom before giving it to the JVM: it is the heaviest process here.

---

## 2. What runs where

| Component | Where | Notes |
|---|---|---|
| `einvoice-at` app | Dokploy application, image from GHCR | Stateless; scale it by adding replicas — but read §7 first |
| PostgreSQL 17 | Dokploy database service | The only stateful component |
| Keycloak | Dokploy application | **Not** `start-dev`; see §5 |
| Traefik | Dokploy's own | TLS via Let's Encrypt |
| Prometheus / Tempo / Grafana | **Not deployed** | The compose `observability` profile is a development aid — Grafana there runs with anonymous admin access. See §8 |
| Mailpit | **Not deployed** | Local SMTP sink only |

---

## 3. One-time provisioning

```bash
# 1. A Hetzner Cloud server: CX22, Ubuntu 24.04, in a location that suits your users (Nuremberg,
#    Falkenstein and Helsinki are all inside the EU/EEA — which matters, see docs/privacy.md).
#    Attach an SSH key; do not enable password login.

# 2. Dokploy, on the fresh server:
ssh root@<server-ip>
curl -sSL https://dokploy.com/install.sh | sh

# 3. Open the Dokploy UI at https://<server-ip>:3000, create the admin account IMMEDIATELY —
#    it is unauthenticated until you do.

# 4. Point a DNS A record at the server:
#      einvoice.example.at   ->  <server-ip>
#      auth.einvoice.example.at -> <server-ip>     (Keycloak)
```

Then, in Dokploy:

1. **Create a project** `einvoice-at`.
2. **Add a PostgreSQL service** (17). Note the internal hostname Dokploy assigns it; that is
   `POSTGRES_HOST`. Do **not** publish its port.
3. **Add the Keycloak application** (see §5).
4. **Add the application** (see §4).

---

## 4. The application

**Source:** the GHCR image the CI workflow publishes on every push to `main`:

```
ghcr.io/stoicera/einvoice_at:main
ghcr.io/stoicera/einvoice_at:sha-<commit>     # pin this in production
```

Prefer the `sha-` tag. `/actuator/info` reports the commit id the running container was built from,
so "which build is this?" is answerable without trusting a mutable tag:

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://einvoice.example.at/actuator/info | jq .git.commit.id.full
```

**Domain:** `einvoice.example.at`, TLS on, HTTP→HTTPS redirect on. Dokploy configures Traefik.

**Health checks:** `/actuator/health/readiness` for readiness, `/actuator/health/liveness` for
liveness. Both are anonymous by design; everything else under `/actuator` requires a credential.

### Environment

Set these in Dokploy's environment editor. `.env.example` documents every variable in full; this is
the subset a production deployment must think about, with the values that differ from the defaults.

```bash
# --- Database ------------------------------------------------------------------
POSTGRES_HOST=<dokploy postgres service host>
POSTGRES_PORT=5432
POSTGRES_DB=einvoice
POSTGRES_USER=einvoice
POSTGRES_PASSWORD=<generated, 32+ chars>

# --- Identity ------------------------------------------------------------------
# The issuer MUST be the URL a browser and an API client both see. With Keycloak on its own
# hostname (§5) there is no split-horizon problem to work around.
OAUTH2_ISSUER_URI=https://auth.einvoice.example.at/realms/einvoice
OAUTH2_JWK_SET_URI=https://auth.einvoice.example.at/realms/einvoice/protocol/openid-connect/certs
# SET THIS. Signature and issuer prove a token is genuine, not that it was minted FOR this API;
# without it any client in the realm can present its own token and be authenticated (ADR-0006).
OAUTH2_AUDIENCE=einvoice-api

SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_ID=einvoice-web
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_SECRET=<generated>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_PROVIDER=keycloak
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_AUTHORIZATION_GRANT_TYPE=authorization_code
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_REDIRECT_URI={baseUrl}/login/oauth2/code/{registrationId}
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE=openid,profile,email
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_AUTHORIZATION_URI=https://auth.einvoice.example.at/realms/einvoice/protocol/openid-connect/auth
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_TOKEN_URI=https://auth.einvoice.example.at/realms/einvoice/protocol/openid-connect/token
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_JWK_SET_URI=https://auth.einvoice.example.at/realms/einvoice/protocol/openid-connect/certs
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_USER_INFO_URI=https://auth.einvoice.example.at/realms/einvoice/protocol/openid-connect/userinfo
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_USER_NAME_ATTRIBUTE=preferred_username
```

> **Do not add `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI`.** It reads as the
> harmless completion of that block. Spring Boot treats a provider `issuer-uri` as a request to
> perform OIDC **discovery at startup**, and a discovery failure fails bean creation and therefore
> the whole application — public validator included. This exact line stopped the compose stack from
> booting once; `docker-compose.yml` carries the story.

```bash
# --- Runtime -------------------------------------------------------------------
SPRING_PROFILES_ACTIVE=prod          # structured JSON logs, ECS field names
SERVER_FORWARD_HEADERS_STRATEGY=native      # REQUIRED behind Traefik, and NOT `framework` — see below
API_DOCS_ENABLED=false               # consider: do not publish the full API description anonymously

# --- Limits --------------------------------------------------------------------
# The shipped defaults are generous for a portfolio demo and not tuned against real traffic.
RATE_LIMIT_VALIDATE_CAPACITY=60
RATE_LIMIT_VALIDATE_REFILL_PER_MINUTE=60
RATE_LIMIT_CONVERT_CAPACITY=20
RATE_LIMIT_CONVERT_REFILL_PER_MINUTE=20
RATE_LIMIT_EXPLAIN_CAPACITY=30
RATE_LIMIT_EXPLAIN_REFILL_PER_MINUTE=30

# --- Observability (optional) ---------------------------------------------------
OTEL_ENABLED=false                   # true only with a collector to send to; see §8
OTEL_SAMPLING_PROBABILITY=0.1
OTEL_TRACES_ENDPOINT=https://<your collector>/v1/traces
OTEL_METRICS_ENDPOINT=https://<your collector>/v1/metrics

# --- AI (optional) --------------------------------------------------------------
FEATURES_AI_EXPLANATIONS=false       # costs money per click and sends text to a third party
AI_API_KEY=
AI_MODEL=anthropic/claude-opus-5     # ADR-0010's recommendation where cost sits against a contract
```

### `SERVER_FORWARD_HEADERS_STRATEGY=native` is not optional here — and it is not `framework`

Traefik terminates TLS and proxies to the container, so without this every request arrives with
Traefik's address. Two things break:

1. The per-IP rate limit on the anonymous validator becomes **one global bucket** — the first abusive
   client throttles the whole internet.
2. Generated absolute URLs and redirects come out as `http://`.

The setting is `none` by default precisely because turning it on **without** a proxy is the mirror
hazard: `X-Forwarded-For` is then caller-supplied text and anyone can mint themselves unlimited
buckets. Both directions are covered by tests (`ForwardedHeadersTrustedIT`,
`ForwardedHeadersUntrustedIT`).

**Use `native`. Do not use `framework`, even though Boot accepts it.** This is the M6 hostile
review's finding F1, and it is the difference between a rate limit and the appearance of one.

A proxy **appends**. It takes whatever `X-Forwarded-For` arrived and adds the peer it actually
observed, so a request a client sends as

```
X-Forwarded-For: 198.51.100.1
```

reaches the application as

```
X-Forwarded-For: 198.51.100.1, 203.0.113.77
                 ^^^^^^^^^^^^  ^^^^^^^^^^^^
                 written by    written by
                 the caller    the proxy
```

Only the rightmost entry was observed by anything trustworthy. The two strategies read opposite ends
of that chain:

| Strategy | Implementation | Reads | Consequence |
|---|---|---|---|
| `framework` | Spring `ForwardedHeaderFilter` | The **leftmost** entry (`ForwardedHeaderUtils.parseForwardedFor` → `tokenizeToStringArray(header, ",")[0]`) | The caller picks their own bucket. One header line per request bypasses the limit, and the limiter still looks like it is working |
| `native` | Tomcat `RemoteIpValve` | Walks **right to left**, discarding entries matching `server.tomcat.remoteip.internal-proxies`, stopping at the first that is not | Anything prepended by the caller is left behind |

Boot's default `internal-proxies` regex already covers loopback and every RFC 1918 range, so a
Traefik on a Docker bridge network (`172.16.0.0/12`) is matched without further configuration. Set
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` only if the proxy reaches the container from a public
address, which the Dokploy topology does not.

`native` does not read `X-Forwarded-Prefix`, which is the one thing `framework` offers on top. This
application is served at the root of its own host, so there is no prefix to honour. If that ever
changes, the answer is a prefix-aware valve — not `framework`.

**Defence in depth on the proxy side.** `native` makes the application correct regardless of what
Traefik does, which is the point of choosing it. Traefik's own default is also correct here — with
`forwardedHeaders.trustedIPs` unset it *strips* client-supplied `X-Forwarded-*` before setting its
own. Do not set `--entrypoints.web.forwardedHeaders.insecure=true`; it is a common answer to "I
cannot see client IPs" and it makes the header trusted from anyone.

---

## 5. Keycloak

The compose Keycloak is `start-dev` with an in-memory database and an imported dev realm. **None of
that is for production.** Deploy Keycloak as its own Dokploy application:

- Image: `quay.io/keycloak/keycloak:26.7.0`, command `start --optimized`
- Its **own** PostgreSQL database (`KC_DB=postgres`, `KC_DB_URL`, `KC_DB_USERNAME`, `KC_DB_PASSWORD`)
- `KC_HOSTNAME=https://auth.einvoice.example.at`
- `KC_PROXY_HEADERS=xforwarded` — Keycloak needs the same forwarded-headers treatment the
  application does, for the same reason
- Bootstrap admin credentials on first start only, then **remove them from the environment** and
  manage the admin user in the realm

Then create the realm by hand or import `keycloak/dev-realm.json` **as a starting point** and:

1. Change every client secret. The committed ones end in `-not-for-production` and are compromised
   by virtue of being in a public repository.
2. Turn **off** direct access grants (the password grant) on `einvoice-api` unless a machine client
   genuinely needs it — API keys exist for machine access.
3. Set the `einvoice-web` client's valid redirect URIs to `https://einvoice.example.at/login/oauth2/code/keycloak`
   and nothing wider.
4. Delete `testuser`.

---

## 6. Deploying

CI builds and pushes the image on every push to `main` and then calls Dokploy's deploy webhook. Both
steps are skipped — loudly, with a job-summary note — when their secrets are absent, so a fork or a
clone does not get a red pipeline for a deployment it does not have.

Repository secrets to configure (owner action):

| Secret | What it is |
|---|---|
| `DOKPLOY_DEPLOY_WEBHOOK` | The application's deploy webhook URL, from Dokploy → Application → Deployments |
| `NVD_API_KEY` | Free key from <https://nvd.nist.gov/developers/request-an-api-key>; turns the dependency scan from skip-with-warning into a real gate |

Pushing to GHCR uses the built-in `GITHUB_TOKEN` and needs no secret.

**Migrations run at start.** Flyway applies pending migrations and then validates; a container that
starts is a container whose schema matches its code. A migration that cannot be applied fails the
start, which is the correct outcome — a half-migrated database serving traffic is worse than a
deployment that did not happen.

**Rolling back** means deploying an earlier `sha-` tag. Note that Flyway does not roll *back*: if the
newer build added a migration, the older image will refuse to start against the migrated schema. Plan
schema changes to be backward-compatible for one release, which this schema's history has been.

---

## 7. Scaling, and the one thing that does not scale

The application container is stateless: no session affinity is needed for the API, and the browser
session is a cookie against a single instance (add sticky sessions in Traefik, or accept a re-login,
before running more than one replica for the dashboard).

Two things are genuinely per instance and stated here rather than discovered:

1. **The rate limiter is in-memory.** Two replicas mean two allowances. A real multi-replica
   deployment needs a distributed bucket store; bucket4j ships one.
2. **The retention job elects a single purger** via a PostgreSQL advisory lock, so *that* is safe to
   run on every replica — one wins, the rest skip. This was the M5 gap and is closed
   (`RetentionService`, ADR-0011).

---

## 8. Observability in production

The `observability` compose profile (Prometheus + Tempo + Grafana) is a **development aid** and is
deliberately not deployed: its Grafana runs with anonymous admin access and no login form, which is
right for a laptop and wrong for anything reachable.

For a real deployment, point the OTLP exporters at a collector you control:

```bash
OTEL_ENABLED=true
OTEL_SAMPLING_PROBABILITY=0.1
OTEL_TRACES_ENDPOINT=https://otlp.example.com/v1/traces
OTEL_METRICS_ENDPOINT=https://otlp.example.com/v1/metrics
```

Grafana Cloud, a self-hosted OpenTelemetry Collector and a managed Tempo/Prometheus all speak this.
If you need tail sampling, redaction or fan-out to more than one backend, put a Collector in front —
the application deliberately does not do those jobs (ADR-0012).

With `SPRING_PROFILES_ACTIVE=prod` the logs are ECS-shaped JSON on stdout and carry `traceId`/`spanId`
for every line emitted inside a span, so a log pipeline links each line to its trace with no extra
configuration.

---

## 9. Backup and restore

### The backup

`scripts/backup.sh` takes a compressed custom-format dump, **verifies it is readable** with
`pg_restore --list` before reporting success, writes a SHA-256 sidecar, and prunes dumps older than
`BACKUP_KEEP_DAYS` (default 30).

Install it as a cron job on the VPS:

```cron
# /etc/cron.d/einvoice-backup
15 2 * * * root cd /opt/einvoice-at && \
  POSTGRES_HOST=<db host> POSTGRES_USER=einvoice POSTGRES_DB=einvoice \
  PGPASSWORD=<password> BACKUP_KEEP_DAYS=30 \
  ./scripts/backup.sh /var/backups/einvoice >> /var/log/einvoice-backup.log 2>&1
```

Real output, run against PostgreSQL 17:

```
Dumping einvoice@127.0.0.1:5432 -> ./backups/einvoice-20260727T055812Z.dump
Verifying archive
OK: ./backups/einvoice-20260727T055812Z.dump (16K, 31 entries)
Pruning dumps older than 30 days in ./backups
```

> **Copy the dumps off the machine.** A backup on the same disk as the database is a copy, not a
> backup. Hetzner Storage Box over `rsync`/`rclone`, or any S3-compatible bucket, is enough — that
> step is deliberately not scripted here because the destination is yours and a wrong one is worse
> than none.
>
> Hetzner's own **snapshots and backups** are worth enabling as well, and are not a substitute: a
> snapshot restores a machine, `pg_dump` restores a database into a machine you already trust.

### The restore drill

Rehearse it. A restore procedure is only known to work on the day someone runs it, and that is the
worst possible day to find out a flag is wrong. Restore into a **fresh database** rather than over
the live one — that proves the dump without risking the thing you are protecting.

```bash
# On the VPS, or anywhere with network access to the database:
export PGPASSWORD=<password>
export POSTGRES_HOST=<db host> POSTGRES_USER=einvoice

createdb -h "$POSTGRES_HOST" -U "$POSTGRES_USER" einvoice_drill
POSTGRES_DB=einvoice_drill ./scripts/restore.sh /var/backups/einvoice/einvoice-<stamp>.dump
```

Real output from that exact sequence:

```
Verifying checksum
Archive contents:
  221; 1259 16434 TABLE public api_key einvoice
  ...
ABOUT TO OVERWRITE: einvoice_drill on 127.0.0.1:5432 as einvoice
Restoring
Restored. Row counts:
  tenant         1
  invoice        2
  report         2
  api_key        0
  audit_event    57
```

Compare those counts against the source. They matched.

`scripts/restore.sh` refuses to run without either typing the database name or passing `--force`,
prints the target before touching it, and checks the SHA-256 sidecar first. **Stop the application
before restoring over a live database**: Flyway validates the schema at startup and the application
holds connections, so restoring underneath a running instance leaves a half-dropped schema and an
application that believes otherwise.

### The drill is also a test

`BackupRestoreDrillIT` runs the same dump-and-restore round trip on every build, inside the
Testcontainers PostgreSQL, and asserts that every table's row count survives, that Flyway's own
history table is part of the archive, and that the canonical JSON column comes back with its content
intact. Row counts alone would pass on a restore that produced the right number of empty rows.

---

## 10. Smoke test after a deploy

```bash
BASE=https://einvoice.example.at

# 1. Alive, and which build?
curl -fsS $BASE/actuator/health | jq -r .status                       # UP
curl -fsS -H "Authorization: Bearer $TOKEN" $BASE/actuator/info | jq -r .git.commit.id.full

# 2. The public validator works without a credential and stores nothing.
curl -fsS -F "file=@samples/invoice-b2g-sample.ebinterface.xml" $BASE/api/v1/validate | jq '{id, valid: .report.valid}'
#   -> { "id": null, "valid": true }

# 3. The browser surface renders and the login redirects to the real Keycloak.
curl -fsS -o /dev/null -w '%{http_code}\n' $BASE/                     # 200
curl -fsS -o /dev/null -w '%{http_code} %{redirect_url}\n' $BASE/app  # 302 https://auth.…/auth?…

# 4. TLS is actually terminated and the redirect is https.
curl -sSI http://einvoice.example.at | grep -i '^location'            # https://…
```

If step 2 answers `"id": null` you have also confirmed the DSGVO promise the landing page makes:
an anonymous upload is not stored. `ReportService` has one implementation of that and the public page
and the API share it.

---

## 11. Troubleshooting

| Symptom | Likely cause |
|---|---|
| The container starts and immediately exits, log mentions `ClientRegistrations.fromIssuerLocation` | A provider `issuer-uri` was set. Remove it; give the four endpoint URLs explicitly (§4) |
| Login redirects to Keycloak and then fails with an issuer mismatch | `KC_HOSTNAME` and `OAUTH2_ISSUER_URI` disagree. Keycloak derives URLs from the request `Host` unless `KC_HOSTNAME` is pinned |
| Every anonymous caller shares one rate-limit bucket | `SERVER_FORWARD_HEADERS_STRATEGY` is still `none` behind Traefik (§4) |
| Flyway fails validation at start | The image is older than the database's schema. Deploy the newer `sha-` tag, or restore a dump from before the migration |
| `/actuator/info` returns `{}` | The image was built without `.git` in the build context. `.dockerignore` must not exclude it |
| Traces never appear | `OTEL_ENABLED` is false, or the endpoint is unreachable. The application does not fail on an unreachable collector — by design; it logs and carries on |
