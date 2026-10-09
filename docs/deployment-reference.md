# Deployment reference — why the deployment is shaped this way

Status: 2026-07-30 · Milestone M6
Step-by-step instructions live in **[deployment.md](deployment.md)**. This document is the *why*.

Read this when a setting surprises you, when something breaks, or when you are reviewing the
deployment rather than performing it. It is organised by topic, not by order of operations.

> **What is verified and what is not.** Every command in *Backup and restore* was executed against a
> real PostgreSQL 17 and its output is reproduced below; the drill is additionally automated as
> `BackupRestoreDrillIT` and runs on every build. Traefik's handling of client-supplied
> `X-Forwarded-*` headers is verified against Traefik's source, and [deployment.md
> §9](deployment.md#the-fifth-check-the-x-forwarded-for-fix) gives the two commands that confirm it
> on a live instance. The provisioning steps themselves are the intended procedure; see
> `docs/worklog.md` for the milestone's honest accounting of what has been executed on real hardware.

---

## 1. Why Hetzner + Dokploy

Hetzner Cloud plus Dokploy is chosen for the same reason the rest of this repository is boring: it is
Docker hosts, Traefik for TLS, and a webhook for deploys. There is no Kubernetes, no service mesh and
no managed control plane for an application that is deliberately a modular monolith
([ADR-0002](adr/0002-modular-monolith.md)). `ENGINEERING_STANDARDS.md` §7 names this target.

Nuremberg, Falkenstein and Helsinki are all inside the EU/EEA, which matters — see
[privacy.md](privacy.md).

**Sizing.** The application is CPU-bound in exactly one place: the Peppol Schematron run, which is
XSLT and measured at ~29 ms p95 for a single document. A CX22 (2 vCPU / 4 GB) is enough for the
application, PostgreSQL and Keycloak together on a demonstration instance. Give Keycloak its memory
headroom before giving it to the JVM — it is the heaviest process here.

### The two-server topology

This deployment uses a Dokploy **panel** server and a separate **deploy** server:

| Server | Runs | DNS points at it? |
|---|---|---|
| Dokploy panel VPS | The Dokploy UI and its own control-plane database | No |
| Production VPS | Traefik, the app, Keycloak, both PostgreSQL instances | **Yes** |

Dokploy connects to the production server over SSH and deploys there. Its documentation notes that
for remote instances "we install only a Traefik instance" during *Setup Server* — which is the point
that matters: **the Traefik that terminates your TLS runs on the production server**, so that is
where the `A` records must resolve.

### What is deliberately not deployed

| Component | Status | Reason |
|---|---|---|
| Prometheus / Tempo / Grafana | **Not deployed** | The compose `observability` profile is a development aid — its Grafana runs with anonymous admin access, which is right for a laptop and wrong for anything reachable. See §5 |
| Mailpit | **Not deployed** | A local SMTP sink for development only |

---

## 2. `SERVER_FORWARD_HEADERS_STRATEGY=native` — and why not `framework`

Traefik terminates TLS and proxies to the container, so without this setting every request arrives
carrying Traefik's address. Two things break:

1. The per-IP rate limit on the anonymous validator becomes **one global bucket** — the first abusive
   client throttles the whole internet.
2. Generated absolute URLs and redirects come out as `http://`.

The setting is `none` by default precisely because turning it on **without** a proxy is the mirror
hazard: `X-Forwarded-For` is then caller-supplied text and anyone can mint themselves unlimited
buckets. Both directions are covered by tests (`ForwardedHeadersTrustedIT`,
`ForwardedHeadersUntrustedIT`). There is no value that is correct in both topologies, which is why
the deployment states which one it is.

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
Traefik on a Docker network (`172.16.0.0/12`) is matched without further configuration. Set
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` only if the proxy reaches the container from a public
address, which the Dokploy topology does not.

`native` does not read `X-Forwarded-Prefix`, which is the one thing `framework` offers on top. This
application is served at the root of its own host, so there is no prefix to honour. If that ever
changes, the answer is a prefix-aware valve — not `framework`.

### Defence in depth on the proxy side

`native` makes the application correct regardless of what Traefik does, which is the point of
choosing it. Traefik's own default is also correct here, and this is verifiable rather than assumed:
in `pkg/middlewares/forwardedheaders/forwarded_header.go`, the guard

```go
if !x.insecure && !x.isTrustedIP(r.RemoteAddr) {
    DeleteXForwardedHeaders(r.Header)
}
```

removes every `X-Forwarded-*` header — `For`, `Proto`, `Host`, `Port`, `Prefix`, `X-Real-Ip` and the
rest — before Traefik sets its own. With `forwardedHeaders.trustedIPs` unset, which is Dokploy's
default, no external caller is trusted, so a forged header is discarded at the edge and never reaches
the application at all.

**Do not set `--entrypoints.web.forwardedHeaders.insecure=true`.** It is a common answer to "I cannot
see client IPs" and it makes the header trusted from anyone, disabling exactly the deletion above.

---

## 3. Running behind the Cloudflare proxy

The deployment uses Cloudflare in **DNS-only** mode (grey cloud). If you ever turn the orange cloud
on, two things change and both need action.

**The client IP changes.** Traffic then arrives at Traefik from a Cloudflare edge address, not from
the visitor. The chain reaching the application becomes `<visitor>, <cloudflare-edge>`, and because
`RemoteIpValve` walks right to left and stops at the first address that is not an internal proxy, it
would stop at the *Cloudflare* address. The per-IP rate limit would then bucket by Cloudflare data
centre — many visitors sharing one allowance, which is a different failure from the one `native`
fixes but just as invisible.

The fix is to tell Tomcat that Cloudflare's addresses are also proxies, by extending
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` with Cloudflare's published IPv4 and IPv6 ranges
(<https://www.cloudflare.com/ips/>). That list changes occasionally, so it becomes something you own
and must maintain — which is the real cost of the orange cloud here, and the reason the deployment
does not start there.

**Certificates change.** A proxied hostname is served with Cloudflare's certificate, and the free
Universal SSL certificate covers the apex plus **one** level of subdomain. Dokploy's own
documentation states the limit plainly: "with a free Cloudflare account, this method works only for
the main domain and subdomains, not for sub-subdomains. E.g. `api.dokploy.com` works but
`staging.api.dokploy.com` does not work." This is why the deployment uses
`auth-einvoice.sebastiankern.net` rather than `auth.einvoice.sebastiankern.net`: the flat name stays
valid whichever mode you choose.

Set the Cloudflare SSL/TLS mode to **Full (Strict)** if you enable the proxy — Traefik already holds
a real Let's Encrypt certificate, so there is no reason to accept anything weaker. *Flexible* would
make the Cloudflare-to-origin hop plain HTTP, which is worse than not proxying at all.

---

## 4. Images, deploying, and rolling back

CI builds and pushes the image on every push to `main` and then calls Dokploy's deploy webhook. Both
steps are skipped — loudly, with a job-summary note — when their secrets are absent, so a fork or a
clone does not get a red pipeline for a deployment it does not have.

```
ghcr.io/stoicera/einvoice_at:main           # what the deployment pulls; the webhook redeploys it
ghcr.io/stoicera/einvoice_at:sha-<commit>   # immutable, always published alongside
```

Repository secrets (owner action):

| Secret | What it is |
|---|---|
| `DOKPLOY_DEPLOY_WEBHOOK` | The application's deploy webhook URL, from Dokploy → Application → Deployments |
| `NVD_API_KEY` | Free key from <https://nvd.nist.gov/developers/request-an-api-key>; turns the dependency scan from skip-with-warning into a real gate |

Pushing to GHCR uses the built-in `GITHUB_TOKEN` and needs no secret. The GHCR *package*, however, is
private by default even for a public repository — see [deployment.md §3](deployment.md#3-make-the-container-image-pullable).

### Which build is running?

`/actuator/info` reports the commit the running container was built from, so the question is
answerable without trusting a mutable tag:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  https://einvoice.sebastiankern.net/actuator/info | jq .git.commit.id.full
```

A CI step asserts the built image can identify itself — it extracts `git.properties` from the jar and
compares it to the commit being built — so this procedure cannot silently stop working because `.git`
slipped back into `.dockerignore`.

### Pinning an exact build

The deployment tracks the `main` tag so the CI webhook can do its job: a webhook says "redeploy", it
cannot change which tag Dokploy pulls. If you want a pinned build instead — worth doing once real
users depend on the instance:

1. Dokploy → `einvoice-app` → **General** → change Docker Image to `ghcr.io/stoicera/einvoice_at:sha-<40-char commit>`.
2. Turn **Auto Deploy** off, and remove the `DOKPLOY_DEPLOY_WEBHOOK` secret (or leave it; the deploy
   job would then redeploy the same pinned tag, which is harmless but pointless).
3. Every release becomes: read the commit id, edit the image tag, click Deploy.

### Migrations and rollback

**Migrations run at start.** Flyway applies pending migrations and then validates; a container that
starts is a container whose schema matches its code. A migration that cannot be applied fails the
start, which is the correct outcome — a half-migrated database serving traffic is worse than a
deployment that did not happen.

**Rolling back** means deploying an earlier `sha-` tag. Note that Flyway does not roll *back*: if the
newer build added a migration, the older image will refuse to start against the migrated schema. Plan
schema changes to be backward-compatible for one release, which this schema's history has been.

---

## 5. Keycloak in production

The compose Keycloak is `start-dev` with an in-memory database and an imported dev realm. **None of
that is for production.** The production shape, and the reason for each part:

| Setting | Value | Why |
|---|---|---|
| Run Command | `/opt/keycloak/bin/kc.sh start` | **The full path, not a bare `start`.** Dokploy writes this field into the Swarm service as `ContainerSpec.Command`, which *replaces* the image `ENTRYPOINT` — so `start` alone discards `kc.sh` and asks Docker to exec a binary that does not exist. Compose's `command:` is `CMD` and behaves the opposite way; that is the trap. Also **not `--optimized`**: that flag requires an image built with `kc.sh build`, and against the plain image it fails because the build artefacts do not exist |
| `KC_DB` | `postgres` | Its own database, separate from the application's — see [deployment.md §6](deployment.md#6-create-the-two-databases) |
| `KC_HOSTNAME` | `https://auth-einvoice.sebastiankern.net` | Keycloak stamps this into every token's `iss` and every redirect. It must be what the browser sees |
| `KC_HOSTNAME_STRICT` | `true` | Keycloak's default is already `true`; set explicitly so nobody "fixes" a hostname problem by relaxing it |
| `KC_HTTP_ENABLED` | `true` | **Defaults to `false`.** Traefik terminates TLS and speaks plain HTTP to the container; without this, Keycloak listens on HTTPS only and every request is a 502 |
| `KC_PROXY_HEADERS` | `xforwarded` | Keycloak needs the same forwarded-headers treatment the application does, for the same reason. Its docs warn that without a proxy that overwrites these headers, "clients can spoof their IP address, protocol, or host" |
| `KC_HEALTH_ENABLED` | `true` | Exposes `/health/ready` — on the **management port 9000**, not 8080. Do not route a public domain at 9000 |
| `KC_BOOTSTRAP_ADMIN_*` | first boot only | Keycloak uses these "only when the master realm is created". Remove them once a real admin user exists |

The committed `keycloak/dev-realm.json` is a **reference for what the finished configuration looks
like**, not something to import into a reachable server: every secret in it ends in
`-not-for-production` and is compromised by virtue of being in a public repository. If you do import
it as a starting point, then at minimum: change every client secret, turn **off** direct access
grants on `einvoice-api`, narrow `einvoice-web`'s redirect URIs to the exact callback with no
wildcard, and delete `testuser`.

---

## 6. Scaling, and the one thing that does not scale

The application container is stateless: no session affinity is needed for the API, and the browser
session is a cookie against a single instance (add sticky sessions in Traefik, or accept a re-login,
before running more than one replica for the dashboard).

Two things are genuinely per instance, stated here rather than discovered:

1. **The rate limiter is in-memory.** Two replicas mean two allowances. A real multi-replica
   deployment needs a distributed bucket store; bucket4j ships one.
2. **The retention job elects a single purger** via a PostgreSQL advisory lock, so *that* is safe to
   run on every replica — one wins, the rest skip. This was the M5 gap and is closed
   (`RetentionService`, [ADR-0011](adr/0011-retention-and-erasure.md)).

---

## 7. Observability in production

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
the application deliberately does not do those jobs ([ADR-0012](adr/0012-observability.md)).

With `SPRING_PROFILES_ACTIVE=prod` the logs are ECS-shaped JSON on stdout and carry `traceId`/`spanId`
for every line emitted inside a span, so a log pipeline links each line to its trace with no extra
configuration.

The application does **not** fail on an unreachable collector — by design. It logs and carries on.

---

## 8. Backup and restore

### The backup

`scripts/backup.sh` takes a compressed custom-format dump, **verifies it is readable** with
`pg_restore --list` before reporting success, writes a SHA-256 sidecar, and prunes dumps older than
`BACKUP_KEEP_DAYS` (default 30).

Real output, run against PostgreSQL 17:

```
Dumping einvoice@127.0.0.1:5432 -> ./backups/einvoice-20260727T055812Z.dump
Verifying archive
OK: ./backups/einvoice-20260727T055812Z.dump (16K, 31 entries)
Pruning dumps older than 30 days in ./backups
```

Custom format (`-Fc`) rather than plain SQL: it is compressed, it can be restored selectively, and
`pg_restore` can read it in parallel. A plain-SQL dump of a database whose largest column is JSONB is
several times the size for no benefit.

> **Copy the dumps off the machine.** A backup on the same disk as the database is a copy, not a
> backup. This *is* scripted now — `scripts/offsite-sync.sh`, chained into the nightly cron after the
> dump and walked through in `deployment.md` §10.4. It rsyncs to a Hetzner Storage Box (or any
> rsync-over-SSH destination) and then verifies the copy by downloading the newest dump back and
> comparing its SHA-256 against the sidecar, because an rsync exit code proves bytes were sent and
> not that they can be read.
>
> The destination is still yours to choose, which is why the script ships **disarmed**: with no
> `OFFSITE_TARGET` it reports `NOT CONFIGURED` and exits 0. Set `OFFSITE_REQUIRED=1` once storage
> exists, or a typo'd destination and a missing one look identical in the log.
>
> Two things it deliberately does not do, because the obvious `rsync -a --delete` recipe can destroy
> what it protects: it never passes `--delete` (an emptied local directory would otherwise propagate
> off-site and delete the last surviving copy), and it refuses to sync an empty source directory at
> all rather than reporting success over an upstream failure.
>
> Hetzner's own **snapshots and backups** are worth enabling as well, and are not a substitute: a
> snapshot restores a machine, `pg_dump` restores a database into a machine you already trust.

### The restore drill

Rehearse it. A restore procedure is only known to work on the day someone runs it, and that is the
worst possible day to find out a flag is wrong. Restore into a **fresh database** rather than over
the live one — that proves the dump without risking the thing you are protecting.

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
  audit_event   57
```

Compare those counts against the source. They matched.

`scripts/restore.sh` refuses to run without either typing the database name or passing `--force`,
prints the target before touching it, and checks the SHA-256 sidecar first. **Stop the application
before restoring over a live database:** Flyway validates the schema at startup and the application
holds connections, so restoring underneath a running instance leaves a half-dropped schema and an
application that believes otherwise.

### The drill is also a test

`BackupRestoreDrillIT` runs the same dump-and-restore round trip on every build, inside the
Testcontainers PostgreSQL, and asserts that every table's row count survives, that Flyway's own
history table is part of the archive, and that the canonical JSON column comes back with its content
intact. Row counts alone would pass on a restore that produced the right number of empty rows.

---

## 9. Troubleshooting

Ordered roughly by how often each occurs.

| Symptom | Likely cause |
|---|---|
| Traefik returns 404 for your hostname, certificate never issues | DNS points at the Dokploy **panel** VPS instead of the production VPS. Traefik runs on the production server |
| A service is green in Dokploy but 502s, and its containers have **no logs** (or `No such container`) | The container is dying at `exec`; Swarm recreates it and Dokploy shows you the dead ones. Dokploy's green tick only means "image pulled, service updated". `docker service ps --no-trunc <service>` carries the real error. For Keycloak this is nearly always a Run Command of `start` instead of `/opt/keycloak/bin/kc.sh start` — Dokploy's Command field is the entrypoint, not the arguments |
| `502 Bad Gateway` from Keycloak, with a normal startup in its logs | `KC_HTTP_ENABLED=true` is missing (it defaults to `false`), or the domain's Container Port is not `8080` |
| Keycloak logs complain about a missing build / `--optimized` | The run command carries `--optimized` against the stock image. Use `/opt/keycloak/bin/kc.sh start` |
| Dokploy cannot pull the image: "not found" / `denied` | The GHCR package is private. Make it public, or give Dokploy a registry credential ([deployment.md §3](deployment.md#3-make-the-container-image-pullable)) |
| Login redirects to Keycloak and then fails with an issuer mismatch | `KC_HOSTNAME` and `OAUTH2_ISSUER_URI` disagree. Keycloak derives URLs from the request `Host` unless `KC_HOSTNAME` is pinned |
| Login succeeds at Keycloak but the app rejects the token | `OAUTH2_AUDIENCE=einvoice-api` is set but the audience mapper is not attached to `einvoice-web` ([deployment.md §7.4d](deployment.md#74-create-the-realm-and-its-two-clients)) |
| The container starts and immediately exits, log mentions `ClientRegistrations.fromIssuerLocation` | A provider `issuer-uri` was set. Remove it; give the four endpoint URLs explicitly |
| Every anonymous caller shares one rate-limit bucket | `SERVER_FORWARD_HEADERS_STRATEGY` is still `none` behind Traefik (§2) |
| A forged `X-Forwarded-For` gets a fresh rate-limit bucket | The strategy is `framework` rather than `native`, or Traefik has `forwardedHeaders.insecure` enabled (§2) |
| Flyway fails validation at start | The image is older than the database's schema. Deploy the newer `sha-` tag, or restore a dump from before the migration |
| `/actuator/info` returns `{}` | The image was built without `.git` in the build context. `.dockerignore` must not exclude it |
| Traces never appear | `OTEL_ENABLED` is false, or the endpoint is unreachable. The application does not fail on an unreachable collector — by design |
| A container is killed with no error in its own log | Out of memory on the host. `dmesg \| grep -i oom` on the production VPS confirms it |

---

## Sources

The behaviour described above is taken from primary sources rather than memory:

- Dokploy — [remote servers](https://docs.dokploy.com/docs/core/remote-servers), [going to production](https://docs.dokploy.com/docs/core/applications/going-production), [domains](https://docs.dokploy.com/docs/core/domains), [Cloudflare](https://docs.dokploy.com/docs/core/domains/cloudflare), [Docker registry](https://docs.dokploy.com/docs/core/Docker), [databases](https://docs.dokploy.com/docs/core/databases)
- Keycloak — [hostname](https://www.keycloak.org/server/hostname), [reverse proxy](https://www.keycloak.org/server/reverseproxy), [containers](https://www.keycloak.org/server/containers), [management interface](https://www.keycloak.org/server/management-interface), [all configuration](https://www.keycloak.org/server/all-config)
- Traefik — [entrypoints](https://doc.traefik.io/traefik/reference/install-configuration/entrypoints/) and `pkg/middlewares/forwardedheaders/forwarded_header.go`
- Hetzner — [creating a firewall](https://docs.hetzner.com/cloud/firewalls/getting-started/creating-a-firewall/)
- Cloudflare — [creating DNS records](https://developers.cloudflare.com/dns/manage-dns-records/how-to/create-dns-records/)
- GitHub — [package access control and visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)
