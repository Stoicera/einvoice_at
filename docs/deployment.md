# Deploying einvoice-at — a step-by-step walkthrough

Status: 2026-07-30 · For: the repository owner, deploying to an existing Dokploy setup
Reference material (the *why* behind the choices): **[deployment-reference.md](deployment-reference.md)**

This document is a walkthrough, not a reference. It assumes you have **never used Dokploy for a
Java application before** and it tells you what to click, what to paste, and — after every step —
how to prove the step worked before you move on. If a step's check fails, stop there. Debugging one
broken step is easy; debugging six at once is not.

**Time:** 2–3 hours the first time, most of it waiting for containers to start.
**You do not need to write any code.** Everything the application side needs is already built and
tested; what is missing is a machine to run it on.

---

## 0. The picture you are building

Read this once. Every later step makes sense in terms of it, and the single most common way this
deployment goes wrong is not knowing which machine a thing belongs on.

```
                    ┌──────────────────────────┐
   your browser ───▶│  Dokploy panel VPS       │   You click here.
                    │  (control plane)         │   Nothing of einvoice-at runs here.
                    └───────────┬──────────────┘
                                │ SSH — Dokploy pushes deployments over this
                                ▼
  ┌────────────────────────────────────────────────────────────────────┐
  │  Production VPS  ("Deploy Server" in Dokploy)                      │
  │                                                                    │
  │    Traefik  ──────── :443 ───────────────────────────────────┐     │
  │       │                                                      │     │
  │       ├──▶ einvoice-at app        :8080   ──┐                │     │
  │       │                                     ├──▶ Postgres    │     │
  │       └──▶ Keycloak               :8080   ──┘   (× 2)        │     │
  │                                                              │     │
  └──────────────────────────────────────────────────────────────┼─────┘
                                                                 │
   visitors ─────────────────────────────────────────────────────┘
   einvoice.sebastiankern.net        ─┐
   auth-einvoice.sebastiankern.net   ─┴─▶ DNS points HERE, at the production VPS
```

**The one thing to take from this diagram:** your DNS records point at the **production VPS**, not at
the Dokploy panel VPS. Traefik — the piece that terminates HTTPS and routes each hostname to the
right container — runs on the production server, because that is where Dokploy installed it when you
ran *Setup Server*. Pointing DNS at the panel is the classic first mistake and it produces a
confusing symptom: the certificate never issues and you get a Dokploy 404 instead of your app.

### What each of the five containers is for

| Container | What it is | Why you need it |
|---|---|---|
| **einvoice-at app** | The Spring Boot application itself | This is the product |
| **Postgres (app)** | The application's database | Stores invoices, validation reports, API keys, the audit log |
| **Keycloak** | An identity provider ("who is this user?") | The app does not store passwords. It hands login to Keycloak and trusts the signed token that comes back — see [ADR-0006](adr/0006-auth-and-api-security.md) |
| **Postgres (Keycloak)** | Keycloak's own database | Keycloak stores users, realms and clients. Its own database, separate from the app's, so a restore of one never touches the other |
| **Traefik** | A reverse proxy | Terminates HTTPS, gets the Let's Encrypt certificates, and sends each hostname to the right container. Dokploy installed and configured it for you |

### Fill these in before you start

Write these down now; every later step refers back to them. Keep the passwords in a password
manager, not in a text file, and **never** in this repository.

| Name | Value | Where it comes from |
|---|---|---|
| Production VPS public IPv4 | `___.___.___.___` | Hetzner Cloud console → your server → the *IPv4* field |
| App hostname | `einvoice.sebastiankern.net` | Decided |
| Keycloak hostname | `auth-einvoice.sebastiankern.net` | Decided |
| App DB password | *(generate, 32+ chars)* | Step 6 |
| Keycloak DB password | *(generate, 32+ chars)* | Step 6 |
| Keycloak admin password | *(generate, 32+ chars)* | Step 7 |
| `einvoice-web` client secret | *(Keycloak generates it)* | Step 7.4 |

Generate a password with:

```bash
openssl rand -base64 36 | tr -d '/+=' | head -c 40; echo
```

> **Why not `auth.einvoice.sebastiankern.net`?** Because it is a sub-**sub**domain, and Cloudflare's
> free Universal SSL certificate covers only one level (`*.sebastiankern.net`). Dokploy's own
> documentation says the same thing about its stack: "with a free Cloudflare account, this method
> works only for the main domain and subdomains, not for sub-subdomains." You are on *DNS only* so it
> would work today, but the day you switch the orange cloud on it would break with a confusing
> error 526. A flat name costs nothing and removes the trap.

---

## 1. Check the production server has room

**What:** Confirm the production VPS can fit five more containers before you spend an hour finding
out it cannot.

**Why:** Keycloak is the heaviest process in this stack by a wide margin — it is a Java application
in its own right. Two JVMs and two PostgreSQL instances need roughly **2 GB of RAM beyond whatever
you already run there**. A server that runs out of memory does not tell you so politely; the kernel
kills whichever process it likes least, usually at 3 a.m.

**Do this.** SSH into the **production** VPS (not the panel):

```bash
ssh root@<PRODUCTION_IP>
free -h
df -h /
docker ps --format '{{.Names}}\t{{.Status}}'
```

**Verify.** You want to see:

- `free -h` → **at least 2.0 Gi available** in the `available` column of the `Mem:` row.
  (Use `available`, not `free`. Linux uses spare RAM as disk cache and hands it back on demand, so
  `free` is almost always small and almost always irrelevant.)
- `df -h /` → **at least 15 GB free**. Docker images, two databases and their write-ahead logs add up.
- `docker ps` → the containers you already run, all `Up`. Note them, so that if something misbehaves
  later you know what was there before you started.

**If it fails.** Not enough RAM is a real answer, not a setback. Two options, in order of preference:

1. **Resize the server.** Hetzner Cloud console → your server → *Rescale*. A CX22 (2 vCPU / 4 GB) is
   enough for this stack *alone*; if you already run other things, go to CX32 (4 vCPU / 8 GB).
   Rescaling to a larger plan is reversible for CPU/RAM as long as you do not grow the disk.
2. **Add swap** as a stopgap, so an unexpected spike does not kill a container:

   ```bash
   fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
   echo '/swapfile none swap sw 0 0' >> /etc/fstab
   ```

   Swap is a safety net, not capacity. A JVM that is swapping is a JVM that is slow in a way no
   profiler will explain.

---

## 2. Lock the firewall down to three ports

**What:** A Hetzner Cloud firewall on the production VPS allowing only SSH, HTTP and HTTPS inbound.

**Why:** Two of the containers you are about to start — the PostgreSQL instances — must never be
reachable from the internet. A Hetzner firewall sits *in front of* the server, so it protects you
even if a container is misconfigured to publish a port. This is the cheapest security control in
this entire document and it takes four minutes. Hetzner firewalls are default-deny: "any other
connections will be dropped."

**Do this.** In the [Hetzner Cloud console](https://console.hetzner.cloud):

1. Left menu → **Firewalls** → **Create Firewall**.
2. Name it `einvoice-prod`.
3. Under **Inbound rules**, delete any pre-filled rules, then add exactly these three
   (leave *Source IPs* at `0.0.0.0/0, ::/0` unless you have a static IP — see the note below):

   | Protocol | Port | Purpose |
   |---|---|---|
   | TCP | `22` | SSH — Dokploy uses this to deploy, so it must stay open |
   | TCP | `80` | HTTP — Let's Encrypt's certificate challenge needs it, and Traefik redirects it to 443 |
   | TCP | `443` | HTTPS — the actual traffic |

4. Leave **Outbound rules** permissive (the containers need to reach GHCR, Let's Encrypt and the NVD).
5. Under **Apply to**, select your **production** server. Click **Create Firewall**.

**Verify.** From your laptop, confirm the database port is *not* reachable and SSH still is:

```bash
IP=<PRODUCTION_IP>
for p in 22 443 5432; do
  timeout 3 bash -c "</dev/tcp/$IP/$p" 2>/dev/null \
    && echo "$p open" || echo "$p closed"
done
```

(That uses bash's own `/dev/tcp`, so you do not need `nc` or `nmap` installed.)

Expected — and the third line is the one that matters:

```
22 open
443 closed      <- nothing is listening yet; Traefik takes this in step 7
5432 closed     <- the database port is unreachable from the internet
```

If `5432` says **open**, the firewall is not applied to this server. Fix that before continuing —
an internet-reachable PostgreSQL is found by scanners within hours.

**If it fails.** If SSH stops working you have locked yourself out of the wrong server — check under
*Apply to* that you selected the production VPS and that port 22's source is `0.0.0.0/0`. You can
always detach a firewall from the Hetzner console, which needs no SSH.

> **Locking SSH to your own IP** is better still, but only if your home connection has a static
> address. If your ISP rotates it, you will lock yourself out at the least convenient moment. Leave
> `22` open to the world and rely on key-only authentication (Hetzner's default with an SSH key) —
> that is a reasonable trade for a demo instance.

---

## 3. Make the container image pullable

**What:** Make the GHCR package `ghcr.io/stoicera/einvoice_at` public.

**Why:** Your CI already builds and pushes the image on every merge to `main` — that part works. But
GitHub publishes container packages as **private by default**, even from a public repository. I
checked yours: an anonymous pull is refused. Dokploy would fail with a message about the image not
being found, which sounds like a typo in the image name and is not.

There are two ways out. Making the package public is the right one here: this is a public portfolio
repository, the image contains only your own compiled code and public dependencies, and it means
Dokploy needs no credentials to store, rotate or leak. Per GitHub's documentation, "in the Container
registry, public packages allow anonymous access and can be pulled without authentication."

**Do this.**

1. Open <https://github.com/Stoicera/einvoice_at/pkgs/container/einvoice_at>.
   (If that 404s, go to <https://github.com/orgs/Stoicera/packages> and click `einvoice_at`.)
2. Right-hand side → **Package settings** (the gear icon).
3. Scroll to the bottom → **Danger Zone** → **Change visibility** → **Public**.
4. Type the package name to confirm.

**Verify.** From your laptop:

```bash
docker pull ghcr.io/stoicera/einvoice_at:main
```

It must succeed **without** `docker login`. If you are already logged in to GHCR, prove it properly:

```bash
docker logout ghcr.io && docker pull ghcr.io/stoicera/einvoice_at:main
```

**If it fails.** `denied` or `unauthorized` means the visibility change did not take. If you would
rather keep the package private, that is a legitimate choice — you then skip this step and instead
give Dokploy a credential in step 8: create a GitHub personal access token with the `read:packages`
scope, and in Dokploy enter registry URL `ghcr.io`, your GitHub username, and the token as the
password. Everything else in this document is unchanged.

> **One-way door:** GitHub states that "once you make a package public, you cannot make it private
> again." For this repository that is fine — the source is already public. Be deliberate about it
> anyway.

---

## 4. Point the two hostnames at the production server

**What:** Two Cloudflare `A` records, both **DNS only** (grey cloud).

**Why:** Traefik cannot request a Let's Encrypt certificate for a name that does not yet resolve to
it. Let's Encrypt proves you own the name by fetching a file over HTTP from whatever the name points
at, so DNS must be correct *first*. Do this before creating anything in Dokploy and the certificate
will simply appear; do it afterwards and you will spend twenty minutes watching a retry loop.

**Grey cloud, not orange,** because with the Cloudflare proxy on, every visitor arrives at your
server from a Cloudflare address. The per-IP rate limit on the public validator would then bucket by
*Cloudflare data centre* rather than by visitor — a rate limit that looks like it works and does not.
Making that correct is possible (it means trusting Cloudflare's IP ranges explicitly) but it is real
work for a demo instance, and it is documented in
[deployment-reference.md §3](deployment-reference.md#3-running-behind-the-cloudflare-proxy) if you ever want it.

**Do this.** In the [Cloudflare dashboard](https://dash.cloudflare.com): select **sebastiankern.net**
→ **DNS** → **Records** → **Add record**. Twice:

| Type | Name | IPv4 address | Proxy status | TTL |
|---|---|---|---|---|
| `A` | `einvoice` | `<PRODUCTION_IP>` | **DNS only** (grey cloud) | Auto |
| `A` | `auth-einvoice` | `<PRODUCTION_IP>` | **DNS only** (grey cloud) | Auto |

In the **Name** field type only the label — `einvoice`, not the full hostname. Cloudflare appends the
zone for you.

**Verify.** Wait about a minute, then from your laptop:

```bash
dig +short einvoice.sebastiankern.net
dig +short auth-einvoice.sebastiankern.net
```

Both must print **your production VPS's IP and nothing else**. If you see two addresses in the
`104.x` or `172.67.x` range, the proxy is still on — go back and click the orange cloud until it
turns grey.

**If it fails.** `dig` printing nothing means the record has not propagated or the name is wrong.
Check for a trailing dot or a doubled zone (`einvoice.sebastiankern.net.sebastiankern.net`) in the
Cloudflare record list — that is what happens when you type the full hostname into the Name field.

---

## 5. Create the Dokploy project

**What:** A project to group the four services, with the production server selected.

**Why:** A Dokploy project is a folder; the thing that matters here is the **server** each service is
created on. Services in the same project on the same server can talk to each other by name over
Docker's internal network, which is how the app will reach its database without any port being
exposed to the internet.

**Do this.** In the Dokploy panel:

1. **Projects** → **Create Project**. Name: `einvoice-at`. Description: `Austrian e-invoicing platform`.
2. Open it and click **Create Service**. Before filling anything else in, find the **Server**
   dropdown at the top of the form and select your **production server** — not `Dokploy Server` /
   `localhost`.

**Verify.** The server name shown at the top of the create-service form is your production server.
Dokploy's documentation is explicit that "if no server is selected, it defaults to the local Dokploy
instance" — which would put your database on the panel VPS, where it does not belong. Check this on
**every** service you create in the next three steps. It is the easiest thing in this document to get
silently wrong.

**If it fails.** No server in the dropdown means the remote server is not set up. Go to
**Settings → Servers**, open your production server, and run **Setup Server** — Dokploy installs
Docker, Swarm and a Traefik instance on it. Their docs note "you only need to run this setup once."

---

## 6. Create the two databases

**What:** Two PostgreSQL 17 services: one for the application, one for Keycloak.

**Why two?** They have unrelated lifecycles. The app's database is the one you back up nightly, drill
restores against, and care about for § 132 BAO retention. Keycloak's holds users and realm
configuration. Keeping them apart means restoring a week-old application backup never logs everyone
out, and a Keycloak upgrade that wants to migrate its schema cannot touch your invoices.

**Do this.** In the `einvoice-at` project, twice: **Create Service** → **Database** → **PostgreSQL**.

**Database 1 — the application's:**

| Field | Value |
|---|---|
| Name | `einvoice-db` |
| Docker Image | `postgres:17` |
| Database Name | `einvoice` |
| Database User | `einvoice` |
| Database Password | *(paste your generated app DB password)* |
| Server | **your production server** |

**Database 2 — Keycloak's:**

| Field | Value |
|---|---|
| Name | `keycloak-db` |
| Docker Image | `postgres:17` |
| Database Name | `keycloak` |
| Database User | `keycloak` |
| Database Password | *(paste your generated Keycloak DB password)* |
| Server | **your production server** |

Click **Create**, then **Deploy** on each.

**Do not set an external port on either.** Dokploy's databases are internal-only unless you assign an
external port, and there is no reason to assign one: the only things that need to reach these
databases run on the same machine. If you later want to inspect the database with a GUI, use an SSH
tunnel rather than opening a port.

**Verify.** Open each database → **General**. Both show status **Running** (`Done`). On the same page
find the **Internal Host** / internal connection value and **write both down** — this is the hostname
the app and Keycloak will use, and it is not simply the name you typed. Dokploy generates a service
name, typically `<name>-<random suffix>`, e.g. `einvoice-db-a1b2c3`.

If the UI does not show it plainly, ask Docker on the production VPS:

```bash
ssh root@<PRODUCTION_IP> "docker service ls --format '{{.Name}}' | grep -Ei 'einvoice-db|keycloak-db'"
```

**If it fails.** A database stuck in `Deploying` usually means the disk is full or the image failed
to pull. Check the **Logs** tab; it says which.

---

## 7. Deploy Keycloak

This is the longest step. It has two halves: getting Keycloak *running* (7.1–7.3), then configuring a
realm inside it (7.4). Do them in order — you cannot configure a server that is not up.

### 7.1 Create the application

**Create Service** → **Application**. Name: `keycloak`. Server: **your production server**.

On the **General** tab:

| Field | Value |
|---|---|
| Source Type | **Docker** |
| Docker Image | `quay.io/keycloak/keycloak:26.7.0` |
| Registry username / password | *(leave empty — quay.io is public)* |

On the **Advanced** tab, find the **Run Command** card. It has two fields, **Command** and
**Arguments (Args)**. Fill in **Command** exactly as below and leave **Arguments (Args)** empty:

```
/opt/keycloak/bin/kc.sh start
```

> **Write the full path. A bare `start` will not work.** Dokploy's **Command** field is the
> container's *entrypoint*, not its arguments: it is written straight into the Docker Swarm service
> as `TaskTemplate.ContainerSpec.Command`, which **replaces** the image's `ENTRYPOINT` exactly the
> way `docker run --entrypoint` does. The field's own placeholder is `/bin/sh` — that is the hint.
>
> The Keycloak image's entrypoint is `/opt/keycloak/bin/kc.sh` and its `CMD` is empty. So typing
> `start` there does **not** run `kc.sh start`. It throws `kc.sh` away and asks Docker to execute a
> program called `start`, which does not exist. The task dies at `exec`, before a single line of log
> — and Dokploy still reports the deployment as successful, because pulling the image and updating
> the service both succeeded. See the failure table in 7.3.
>
> **Why this is easy to get wrong:** our own `docker-compose.yml` says
> `command: ["start-dev", "--import-realm"]` and that is correct. Compose's `command:` is `CMD` —
> arguments *appended* to the entrypoint. Dokploy's `Command` is `ENTRYPOINT` — the thing being
> replaced. Same word, opposite halves of the same line.
>
> Splitting it across both fields — **Command** `/opt/keycloak/bin/kc.sh`, one **Arg** `start` — is
> exactly equivalent and maps more literally onto `ENTRYPOINT` + `CMD`. One field is fewer moving
> parts; either is correct.

> **And not `start --optimized`.** Keycloak's own container documentation says `--optimized`
> "requires a pre-built image. Running this against the plain `quay.io/keycloak/keycloak` image
> without a prior build step will fail because the optimization artifacts don't exist." `--optimized`
> is for a *custom* image you built with `kc.sh build` baked in. Plain `start` does that build at
> startup instead: it costs about 30 extra seconds on first boot and it works.

### 7.2 Environment

**Environment** tab. Paste this, replacing the three `<...>` placeholders:

```bash
KC_DB=postgres
KC_DB_URL=jdbc:postgresql://<KEYCLOAK_DB_INTERNAL_HOST>:5432/keycloak
KC_DB_USERNAME=keycloak
KC_DB_PASSWORD=<KEYCLOAK_DB_PASSWORD>

# The public URL. Keycloak stamps this into every token it issues and into every redirect it
# builds. It MUST match what the browser sees, or logins fail with an issuer mismatch.
KC_HOSTNAME=https://auth-einvoice.sebastiankern.net
KC_HOSTNAME_STRICT=true

# Traefik terminates TLS and speaks plain HTTP to this container. `http-enabled` defaults to
# FALSE, so without this line Keycloak listens on HTTPS only, Traefik cannot reach it, and every
# request is a 502 Bad Gateway.
KC_HTTP_ENABLED=true

# Trust the X-Forwarded-* headers Traefik sets, so Keycloak knows the original request was HTTPS.
KC_PROXY_HEADERS=xforwarded

# Exposes /health/ready. It lives on Keycloak's separate management port 9000, which is NOT
# published to the internet — this is for monitoring from inside the server, not for you to curl.
KC_HEALTH_ENABLED=true

# First boot ONLY. You will delete these two lines in 7.5.
KC_BOOTSTRAP_ADMIN_USERNAME=admin
KC_BOOTSTRAP_ADMIN_PASSWORD=<KEYCLOAK_ADMIN_PASSWORD>
```

Every one of those is required, and each is required for a different reason. `KC_HTTP_ENABLED` and
the `start` command are the two that produce the most confusing failures if you get them wrong.

### 7.3 Domain, then deploy

**Domains** tab → **Add Domain**:

| Field | Value |
|---|---|
| Host | `auth-einvoice.sebastiankern.net` |
| Path | `/` |
| Container Port | `8080` |
| HTTPS | **on** |
| Certificate | **Let's Encrypt** |

Container port `8080` and not `9000`: 9000 is Keycloak's management port, which serves health and
metrics and must **not** be published to the internet. Dokploy's docs describe Container Port as "the
port on the container that the domain should route to."

Now click **Deploy** and watch the **Logs** tab. First boot takes 60–90 seconds — Keycloak builds its
optimized configuration and then runs its database migrations.

> **What Dokploy's green tick actually means.** The deployment log ends at `✅ Pulling image
> completed.` after a few seconds, and the service goes green. That means *the image was pulled and
> the Swarm service was updated* — nothing more. Dokploy does not wait for the container to run, so a
> container that dies on startup still shows a successful deployment and a green tile. **The Logs
> tab is the source of truth, not the tick.** If the Logs tab offers you a handful of containers with
> no output, or `Error response from daemon: No such container`, the container is crash-looping:
> Swarm keeps replacing the dead task and Dokploy is offering you the corpses. Go to the diagnostic
> below.

**Verify.** Wait for the Logs tab to stop scrolling, then from your laptop:

```bash
# 1. Reachable over HTTPS with a valid certificate (curl -f fails on a bad cert).
#    Print the redirect target too — Keycloak's root path does not serve a page.
curl -fsS -o /dev/null -w '%{http_code} %{redirect_url}\n' https://auth-einvoice.sebastiankern.net/

# 2. The discovery document exists and — crucially — reports the right issuer.
curl -fsS https://auth-einvoice.sebastiankern.net/realms/master/.well-known/openid-configuration \
  | jq -r .issuer
```

Expected: `302 https://auth-einvoice.sebastiankern.net/admin/`, then exactly
`https://auth-einvoice.sebastiankern.net/realms/master`.

**`302` is the healthy answer here, not `200`.** Keycloak serves nothing at `/`: once an admin user
exists — which `KC_BOOTSTRAP_ADMIN_*` guarantees on first boot — the root path redirects to
`/admin/`, and `/admin/` redirects again to `/admin/master/console/`. There is no welcome page left
to return `200`. Verified against `quay.io/keycloak/keycloak:26.7.0`. `curl -f` does not treat a
redirect as an error, so the command still succeeds; an earlier version of this document said to
expect `200`, which was simply wrong.

**Check 2 is the important one.** If it prints `http://` instead of `https://`, or a container
hostname, or an internal IP, then `KC_HOSTNAME` or `KC_PROXY_HEADERS` is wrong. Fix it now —
everything downstream trusts that value, and the failure it causes later ("issuer mismatch" during
login) points nowhere near the actual cause.

**If it fails.**

| Symptom | Cause |
|---|---|
| `502`, deployment green in seconds, several containers with **no logs at all** | The container is dying at `exec` and Swarm keeps recreating it. Almost always the **Run Command**: it must be the full path `/opt/keycloak/bin/kc.sh start`, not a bare `start` (7.1) |
| `502 Bad Gateway`, but the container logs a normal Keycloak startup | `KC_HTTP_ENABLED=true` is missing, or Container Port is not `8080` |
| Logs: `Unable to find the Quarkus build output` or a `--optimized` complaint | Run Command ends in `--optimized`. Drop that flag (7.1) |
| Logs: connection refused to the database | `KC_DB_URL` has the wrong internal host — recheck the value from step 6 |
| Certificate never issues | DNS is not yet pointing at the production VPS (step 4), or port 80 is closed (step 2) |

**The diagnostic when there are no logs.** A container that fails before its first instruction has
nothing to log, so Dokploy's Logs tab is empty and tells you nothing. Docker still records why. Ask
it directly:

```bash
ssh root@<PRODUCTION_IP>

# 1. Find the Keycloak application service — the one that is NOT the database.
docker service ls | grep -i keycloak

# 2. The ERROR column of the most recent task is the real reason. --no-trunc matters:
#    without it Docker cuts the message off before the useful part.
docker service ps --no-trunc <keycloak-service-name>
```

A repeating list of `Shutdown`/`Failed` tasks with an error such as
`starting container failed: exec: "start": executable file not found in $PATH` is the Run Command
fault above. To see what Dokploy actually sent, print the service's own entrypoint:

```bash
docker service inspect <keycloak-service-name> \
  --format '{{json .Spec.TaskTemplate.ContainerSpec.Command}}'
```

Expected: `["/opt/keycloak/bin/kc.sh","start"]`. If it prints `["start"]`, fix 7.1 and redeploy.

One corroborating check, if you want certainty before changing anything: open the **keycloak-db**
service's logs. A Keycloak that got as far as its database prints connection activity there. If that
log shows nothing but `database system is ready to accept connections` and periodic checkpoints, then
Keycloak never reached JDBC at all — which rules out every database-shaped explanation and leaves the
process never having started.

### 7.4 Create the realm and its two clients

**What:** A realm is a tenant inside Keycloak — its own users, its own signing keys, its own clients.
A *client* is an application allowed to ask that realm about users. You need one realm and two
clients.

**Why not import `keycloak/dev-realm.json`?** Because every secret in it ends in
`-not-for-production` and is published in a public Git repository. It is a useful reference for what
the finished configuration looks like; it is not something to load into a reachable server.

Log in at `https://auth-einvoice.sebastiankern.net/admin` with `admin` and your bootstrap password.

**a) The realm.** Top-left realm dropdown → **Create realm**. Realm name: `einvoice` (exactly — it
appears in URLs the application is configured with). **Create**.

**b) The `einvoice-web` client** — this is the browser login.

*Clients* → **Create client**:

| Step | Field | Value |
|---|---|---|
| 1 | Client type | `OpenID Connect` |
| 1 | Client ID | `einvoice-web` |
| 2 | Client authentication | **On** (makes it a confidential client with a secret) |
| 2 | Authorization | Off |
| 2 | Authentication flow | **Standard flow** ✅ only. Uncheck *Direct access grants*, *Implicit flow*, *Service accounts roles* |
| 3 | Valid redirect URIs | `https://einvoice.sebastiankern.net/login/oauth2/code/keycloak` |
| 3 | Valid post logout redirect URIs | `https://einvoice.sebastiankern.net/` |
| 3 | Web origins | `https://einvoice.sebastiankern.net` |

Save. Then open the **Credentials** tab and copy the **Client secret** — this is the
`einvoice-web` client secret from your value table, and you need it in step 8.

> Enter the redirect URI **exactly**, with no `*`. A wildcard here means anyone who can get a user to
> click a crafted link can have the authorization code delivered to a site they control. This is the
> one field in Keycloak where being generous is a real vulnerability.

**c) The `einvoice-api` client** — this is what proves an API token was minted for *this* API.

*Clients* → **Create client**:

| Step | Field | Value |
|---|---|---|
| 1 | Client ID | `einvoice-api` |
| 2 | Client authentication | **On** |
| 2 | Authentication flow | Uncheck **everything**. Standard flow off, Direct access grants **off**, Service accounts off |

Save.

*Direct access grants off* means no one can trade a username and password for a token against this
client. Machine-to-machine callers use the application's own API keys, which are issued in the
dashboard, scoped to one tenant and revocable — a better mechanism than a password grant in every
respect.

**d) Make the audience claim real.** By itself, this client does nothing. You need Keycloak to write
`einvoice-api` into the `aud` claim of tokens it issues to the web client, because the application
checks it. Without this, a signature check proves a token is genuine but not that it was minted *for
this API* — any client in the realm could present its own token and be let in ([ADR-0006](adr/0006-auth-and-api-security.md)).

*Client scopes* → **Create client scope**:

- Name: `einvoice-audience`
- Type: **Default**
- Protocol: `openid-connect`
- Include in token scope: On

Save. Open it → **Mappers** tab → **Configure a new mapper** → **Audience**:

- Name: `einvoice-api-audience`
- Included Client Audience: `einvoice-api`
- Add to access token: **On**

Save. Now attach it: *Clients* → `einvoice-web` → **Client scopes** tab → **Add client scope** →
select `einvoice-audience` → **Add** → **Default**.

**e) Create your user.** *Users* → **Add user**. Username, email, **Email verified: On**. Save, then
**Credentials** tab → **Set password**, and turn **Temporary** off.

**Verify.** The realm answers with the right issuer and both clients exist:

```bash
curl -fsS https://auth-einvoice.sebastiankern.net/realms/einvoice/.well-known/openid-configuration \
  | jq -r '.issuer, .authorization_endpoint'
```

Expected — exactly these two lines:

```
https://auth-einvoice.sebastiankern.net/realms/einvoice
https://auth-einvoice.sebastiankern.net/realms/einvoice/protocol/openid-connect/auth
```

### 7.5 Remove the bootstrap credentials

**What:** Delete `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` from Keycloak's
Environment tab, then redeploy.

**Why:** Keycloak's docs are clear that these exist "only when the master realm is created". Once the
admin user exists in the database, the variables do nothing except sit in an environment listing as a
working password in plain text.

**Do this.** First create a second admin user so you are not locked out if you lose the first:
switch to the **master** realm → *Users* → **Add user** → give it a password → *Role mapping* →
**Assign role** → `admin`. Log out, log back in as the new user to prove it works. *Then* delete the
two `KC_BOOTSTRAP_*` lines and redeploy.

**Verify.** After the redeploy, log in at `/admin` with your permanent admin user. Success means the
credentials live in the database, not the environment.

---

## 8. Deploy the application

### 8.1 Create it

**Create Service** → **Application**. Name: `einvoice-app`. Server: **your production server**.

**General** tab:

| Field | Value |
|---|---|
| Source Type | **Docker** |
| Docker Image | `ghcr.io/stoicera/einvoice_at:main` |
| Registry username / password | *(empty — you made the package public in step 3)* |

> **Why the `main` tag and not `sha-<commit>`?** So that automatic deployment works. Your CI pushes
> both tags and then calls Dokploy's webhook, but a webhook only says "redeploy" — it cannot change
> which tag Dokploy pulls. With `main`, every merge is live about six minutes later with no clicking.
> You do not lose the ability to answer "which build is running": `/actuator/info` reports the exact
> commit, and a CI check proves the image can identify itself. If you ever want a pinned tag instead,
> [deployment-reference.md](deployment-reference.md#pinning-an-exact-build) has the swap.

### 8.2 Environment

**Environment** tab. This is the whole configuration — paste it and replace the three `<...>`
placeholders. Every variable here is documented in `.env.example`, and a CI check proves that file
stays complete and current.

```bash
# --- Database ---------------------------------------------------------------
POSTGRES_HOST=<EINVOICE_DB_INTERNAL_HOST>
POSTGRES_PORT=5432
POSTGRES_DB=einvoice
POSTGRES_USER=einvoice
POSTGRES_PASSWORD=<APP_DB_PASSWORD>

# --- Runtime ----------------------------------------------------------------
# Structured JSON logs with ECS field names, and the production defaults.
SPRING_PROFILES_ACTIVE=prod

# REQUIRED behind Traefik, and specifically `native` — not `framework`. Without it every visitor
# appears to come from Traefik's address and the per-IP rate limit on the public validator becomes
# ONE GLOBAL BUCKET: the first abusive client throttles everyone. See the reference document; this
# is the difference between a rate limit and the appearance of one.
SERVER_FORWARD_HEADERS_STRATEGY=native

# Do not publish the full machine-readable API description to anonymous callers.
API_DOCS_ENABLED=false

# --- Identity: validating incoming tokens -----------------------------------
OAUTH2_ISSUER_URI=https://auth-einvoice.sebastiankern.net/realms/einvoice
OAUTH2_JWK_SET_URI=https://auth-einvoice.sebastiankern.net/realms/einvoice/protocol/openid-connect/certs
# Proves a token was minted FOR this API, not merely by this realm. Needs the audience mapper
# from step 7.4d. Setting one without the other locks you out; do both or neither.
OAUTH2_AUDIENCE=einvoice-api

# --- Identity: the browser login --------------------------------------------
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_ID=einvoice-web
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_SECRET=<EINVOICE_WEB_CLIENT_SECRET>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_PROVIDER=keycloak
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_AUTHORIZATION_GRANT_TYPE=authorization_code
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_REDIRECT_URI={baseUrl}/login/oauth2/code/{registrationId}
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE=openid,profile,email
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_AUTHORIZATION_URI=https://auth-einvoice.sebastiankern.net/realms/einvoice/protocol/openid-connect/auth
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_TOKEN_URI=https://auth-einvoice.sebastiankern.net/realms/einvoice/protocol/openid-connect/token
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_JWK_SET_URI=https://auth-einvoice.sebastiankern.net/realms/einvoice/protocol/openid-connect/certs
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_USER_INFO_URI=https://auth-einvoice.sebastiankern.net/realms/einvoice/protocol/openid-connect/userinfo
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_USER_NAME_ATTRIBUTE=preferred_username

# --- Limits -----------------------------------------------------------------
RATE_LIMIT_VALIDATE_CAPACITY=60
RATE_LIMIT_VALIDATE_REFILL_PER_MINUTE=60
RATE_LIMIT_CONVERT_CAPACITY=20
RATE_LIMIT_CONVERT_REFILL_PER_MINUTE=20
RATE_LIMIT_EXPLAIN_CAPACITY=30
RATE_LIMIT_EXPLAIN_REFILL_PER_MINUTE=30

# --- Optional extras --------------------------------------------------------
OTEL_ENABLED=false
FEATURES_AI_EXPLANATIONS=false
```

> **Do NOT add `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI`.** It looks like the
> harmless completion of that block. It is not: Spring Boot treats a *provider* `issuer-uri` as a
> request to perform OIDC discovery **at startup**, and a discovery failure fails bean creation and
> therefore the entire application — public validator included. This exact line stopped the local
> compose stack from booting once.

### 8.3 Domain, health check, deploy

**Domains** tab → **Add Domain**:

| Field | Value |
|---|---|
| Host | `einvoice.sebastiankern.net` |
| Path | `/` |
| Container Port | `8080` |
| HTTPS | **on** |
| Certificate | **Let's Encrypt** |

**Advanced** tab → *Cluster Settings* / *Swarm Settings* → Health Check. Dokploy's production guide
recommends this and it is worth the two minutes: it is what makes a broken deploy roll back instead
of replacing a working container with a crashing one.

```json
{
  "Test": ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/readiness"],
  "Interval": 10000000000,
  "Timeout": 5000000000,
  "StartPeriod": 60000000000,
  "Retries": 5
}
```

(Those are nanoseconds — Docker's Swarm API takes durations that way. 10 s / 5 s / 60 s / 5 tries.)

And in *Update Config*, so a bad build reverts itself:

```json
{ "Parallelism": 1, "Order": "start-first", "FailureAction": "rollback" }
```

Click **Deploy**. First boot takes 40–70 seconds: the JVM starts and Flyway applies every migration
to the empty database.

**Verify.**

```bash
curl -fsS https://einvoice.sebastiankern.net/actuator/health | jq -r .status
```

Expected: `UP`.

**If it fails.** Open the **Logs** tab and match the first error against this table.

| Log says | Cause |
|---|---|
| `Connection to ... refused` / `UnknownHostException` | `POSTGRES_HOST` is not the database's internal host from step 6 |
| `ClientRegistrations.fromIssuerLocation` | You added a provider `issuer-uri`. Remove it |
| Flyway `Validate failed` | The database is not empty and does not match. On a first deploy this means you pointed at the wrong database |
| Container starts then Traefik says 502 | Container Port is not `8080`, or the health check is failing — check `/actuator/health` from inside the container |
| `AI_API_KEY` complaint at startup | `FEATURES_AI_EXPLANATIONS=true` with no key. The app refuses to start rather than pretend the feature works. Set it to `false` |

---

## 9. Prove it actually works

Not "the container is up" — that you already know. These four checks prove the *product* works, and
the fifth proves the security fix that could not be verified without a real proxy.

Run them from your laptop, in the repository directory (check 2 uploads a sample file).

```bash
BASE=https://einvoice.sebastiankern.net

# 1. Alive, and which build is this?
curl -fsS $BASE/actuator/health | jq -r .status          # -> UP

# 2. The public validator works with NO credential, and stores nothing.
curl -fsS -F "file=@samples/invoice-b2g-sample.ebinterface.xml" \
  $BASE/api/v1/validate | jq '{id, valid: .report.valid}'
#    -> { "id": null, "valid": true }

# 3. The landing page renders, and the dashboard redirects to the real Keycloak.
curl -fsS -o /dev/null -w '%{http_code}\n' $BASE/                      # -> 200
curl -fsS -o /dev/null -w '%{http_code} %{redirect_url}\n' $BASE/app   # -> 302 https://auth-einvoice...

# 4. HTTP is redirected to HTTPS.
curl -sSI http://einvoice.sebastiankern.net | grep -i '^location'      # -> https://...
```

Check 2 answering `"id": null` is the important one: it confirms the DSGVO promise the landing page
makes — an anonymous upload is validated and **not stored**.

### The fifth check: the X-Forwarded-For fix

This is the half of the M6 security fix that could not be verified without a real proxy in front of a
real application. Traefik's source code deletes all client-supplied `X-Forwarded-*` headers when the
caller is not a configured trusted IP (`DeleteXForwardedHeaders`, guarded by `!insecure &&
!isTrustedIP`), and Dokploy does not set `trustedIPs`. So a forged header should buy a caller
nothing. Confirm it rather than trust it:

```bash
SAMPLE=samples/invoice-b2g-sample.ebinterface.xml

# 1. Spend the anonymous validator's per-IP allowance. The bucket holds 60 and refills at 60/min,
#    so this loops until it actually OBSERVES a 429 rather than assuming a fixed request count.
for i in $(seq 1 300); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -F "file=@$SAMPLE" $BASE/api/v1/validate)
  if [ "$code" = "429" ]; then echo "rate limited after $i requests"; break; fi
done
#   -> "rate limited after N requests".  If the loop finishes without printing that, the limiter
#      is not engaging at all — check RATE_LIMIT_VALIDATE_* in step 8.

# 2. Immediately claim to be someone else. If forging worked, this would be a fresh bucket.
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-For: 198.51.100.1' \
  -F "file=@$SAMPLE" $BASE/api/v1/validate
#   -> 429.  A 200 here would mean anyone can mint themselves unlimited allowance.
```

Run the second command **right after** the first: the bucket refills at one token per second, so a
minute's pause would hand you a `200` for an innocent reason and make the check meaningless.

**Expected: `429`.** If you get `200`, something upstream is trusting client headers — check that you
did not enable `forwardedHeaders.insecure` in Traefik and that the Cloudflare records are still grey.

Finally, log in through the browser once: open `https://einvoice.sebastiankern.net/app`, sign in as
the user you created in 7.4e, and confirm you land on the dashboard. That exercises the whole
authorization-code flow — the part no `curl` can prove.

---

## 10. Set up backups

**What:** A nightly dump on the production VPS, and one rehearsed restore.

**Why:** The application's database is the only thing here that cannot be rebuilt from Git. Note the
second half — **an untested backup is a hope, not a backup.** The restore is rehearsed on every CI
build (`BackupRestoreDrillIT`), but that proves the *scripts* work, not that *your* dumps are good.

**Run the scripts inside a `postgres:17` container, not on the host.** Two reasons, and both would
otherwise cost you an evening:

- **The database hostname does not resolve on the host.** Dokploy puts its services on a Docker
  overlay network, and overlay DNS names are only resolvable *from inside that network*. A cron job
  running `pg_dump -h einvoice-db-a1b2c3` in a plain host shell fails with "could not translate host
  name".
- **Ubuntu does not ship a version-17 client.** `pg_dump` refuses to dump a server newer than
  itself, so the `postgresql-client` in Ubuntu's own repositories (version 16) errors out against
  your PostgreSQL 17. You would have to add PostgreSQL's apt repository just for this.

A `postgres:17` container attached to the Dokploy network solves both at once, needs nothing
installed on the host, and guarantees the client and server versions match.

**Do this.** On the production VPS:

```bash
ssh root@<PRODUCTION_IP>
mkdir -p /opt/einvoice-at/scripts /var/backups/einvoice
cd /opt/einvoice-at/scripts
curl -fsSLO https://raw.githubusercontent.com/Stoicera/einvoice_at/main/scripts/backup.sh
curl -fsSLO https://raw.githubusercontent.com/Stoicera/einvoice_at/main/scripts/restore.sh
chmod +x backup.sh restore.sh
```

First confirm the network name and that the database answers to it from inside:

```bash
docker network ls --filter driver=overlay --format '{{.Name}}'
#   -> expect `dokploy-network` among them; use whatever it prints below

docker run --rm --network dokploy-network postgres:17 \
  pg_isready -h <EINVOICE_DB_INTERNAL_HOST> -p 5432
#   -> <host>:5432 - accepting connections
```

Getting `accepting connections` here is the whole point of this step — it proves the name resolves
and the credentials path is sound before you automate anything.

**Verify — take one backup by hand now, rather than finding out at 02:15:**

```bash
docker run --rm --network dokploy-network \
  -v /opt/einvoice-at/scripts:/scripts:ro \
  -v /var/backups/einvoice:/backups \
  -e POSTGRES_HOST=<EINVOICE_DB_INTERNAL_HOST> \
  -e POSTGRES_USER=einvoice -e POSTGRES_DB=einvoice \
  -e PGPASSWORD='<APP_DB_PASSWORD>' -e BACKUP_KEEP_DAYS=30 \
  postgres:17 /scripts/backup.sh /backups
```

Expected output — the script reads the archive back before it reports success:

```
Dumping einvoice@<host>:5432 -> /backups/einvoice-<stamp>.dump
Verifying archive
OK: /backups/einvoice-<stamp>.dump (16K, 31 entries)
Pruning dumps older than 30 days in /backups
```

**Now schedule it.** Put the password in a root-only file rather than in the crontab, so the cron
line is not a credential:

```bash
cat > /opt/einvoice-at/backup.env <<'EOF'
POSTGRES_HOST=<EINVOICE_DB_INTERNAL_HOST>
POSTGRES_USER=einvoice
POSTGRES_DB=einvoice
PGPASSWORD=<APP_DB_PASSWORD>
BACKUP_KEEP_DAYS=30
EOF
chmod 600 /opt/einvoice-at/backup.env
```

Then create `/etc/cron.d/einvoice-backup`. It must end with a newline, or cron ignores the file
without saying so:

```cron
15 2 * * * root docker run --rm --network dokploy-network --env-file /opt/einvoice-at/backup.env -v /opt/einvoice-at/scripts:/scripts:ro -v /var/backups/einvoice:/backups postgres:17 /scripts/backup.sh /backups >> /var/log/einvoice-backup.log 2>&1
```

**Then rehearse a restore — into a fresh database, never over the live one.** This is the half that
makes it a backup rather than a hope:

```bash
# A scratch database to restore into.
docker run --rm --network dokploy-network -e PGPASSWORD='<APP_DB_PASSWORD>' postgres:17 \
  createdb -h <EINVOICE_DB_INTERNAL_HOST> -U einvoice einvoice_drill

docker run --rm --network dokploy-network \
  -v /opt/einvoice-at/scripts:/scripts:ro -v /var/backups/einvoice:/backups \
  -e POSTGRES_HOST=<EINVOICE_DB_INTERNAL_HOST> \
  -e POSTGRES_USER=einvoice -e POSTGRES_DB=einvoice_drill \
  -e PGPASSWORD='<APP_DB_PASSWORD>' \
  postgres:17 /scripts/restore.sh /backups/einvoice-<stamp>.dump --force
```

It prints a row count per table. Compare them against the live database — they must match. Then
clean up:

```bash
docker run --rm --network dokploy-network -e PGPASSWORD='<APP_DB_PASSWORD>' postgres:17 \
  dropdb -h <EINVOICE_DB_INTERNAL_HOST> -U einvoice einvoice_drill
```

**If it fails.** `network dokploy-network is not manually attachable` means the overlay is not marked
attachable; in that case run the same commands from a shell inside the running database container
instead (`docker exec -it $(docker ps -qf name=einvoice-db) bash`), writing the dump to a path you
have bind-mounted. `could not translate host name` means the internal host from step 6 is wrong.

> **A dump on the same disk as the database is a copy, not a backup.** Copy them off the machine —
> a Hetzner Storage Box over `rclone`, or any S3 bucket. Hetzner's own server snapshots are worth
> enabling too and are not a substitute: a snapshot restores a *machine*, `pg_dump` restores a
> *database* into a machine you already trust.

---

## 11. Turn on automatic deployment

**What:** Give GitHub Actions the Dokploy webhook URL, so every merge to `main` deploys itself.

**Why:** Your CI already builds the image, pushes it to GHCR, and then looks for a secret called
`DOKPLOY_DEPLOY_WEBHOOK`. If it is absent the job writes a notice to the run summary and exits green
— deliberately, so that a fork does not inherit a red pipeline. Adding the secret is the only thing
standing between you and a fully automatic deployment. No workflow edit is needed.

**Do this.**

1. In Dokploy: open the `einvoice-app` application → **Deployments** tab → copy the **Webhook URL**.
2. On your laptop:

   ```bash
   gh secret set DOKPLOY_DEPLOY_WEBHOOK -R Stoicera/einvoice_at
   # paste the URL when prompted, then press Enter
   ```

   (Or: GitHub → repository → *Settings* → *Secrets and variables* → *Actions* → **New repository
   secret**, named exactly `DOKPLOY_DEPLOY_WEBHOOK`.)

3. In Dokploy, on the same tab, turn **Auto Deploy** on.

**Verify.** Trigger a real deployment and watch it land:

```bash
git commit --allow-empty -m "chore: verify the deployment webhook"
git push
gh run watch
```

The `Deploy (Dokploy webhook)` job must print `Deployment triggered for <sha>` — not the
"Deployment skipped" notice. Then confirm the running container is that commit:

```bash
sleep 120
curl -fsS https://einvoice.sebastiankern.net/actuator/health | jq -r .status   # -> UP
git rev-parse HEAD
```

The commit id from `/actuator/info` should match `git rev-parse HEAD` — that endpoint needs a
credential, so check it from the dashboard once you are logged in, or trust the health check plus the
Dokploy deployment log.

**If it fails.** A failing `Deploy` job means Dokploy returned a 4xx or 5xx — the workflow uses
`curl --fail` on purpose, so a broken webhook is a red job rather than a green one that deployed
nothing. Re-copy the URL; Dokploy regenerates it if the application is recreated.

---

## You are done

The live instance is the last open acceptance criterion of milestone M6. What you now have:

- `https://einvoice.sebastiankern.net` — the public validator, no login needed
- `https://einvoice.sebastiankern.net/app` — the dashboard, behind Keycloak
- Every merge to `main` live in about six minutes
- A nightly, verified backup and a restore you have personally rehearsed

**Next:** the remaining owner tasks are in **[owner-checklist.md](owner-checklist.md)** — tagging
`v0.1.0`, the repository's About box, and branch protection. None of them takes more than five
minutes.

**When something breaks later,** or when you want to know *why* a setting is what it is:
**[deployment-reference.md](deployment-reference.md)**. It covers the `native` vs `framework`
analysis in full, what does and does not scale, observability in production, rolling back, running
behind the Cloudflare proxy, and a symptom-to-cause troubleshooting table.
