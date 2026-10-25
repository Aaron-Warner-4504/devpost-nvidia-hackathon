# Sentinel on a shared host (behind an existing nginx, under `/sentinel/`)

Goal: add Sentinel to a VM that already runs another production app behind nginx for `interns.enthral.ai`,
without changing that app. Sentinel is served at **https://interns.enthral.ai/sentinel/**; nginx strips the
prefix and proxies to `127.0.0.1:8100`. The database is an external Supabase Postgres. Android base URL:
`https://interns.enthral.ai/sentinel` (the client appends `v1/...`).

Every step before step 5 is invisible to the neighbour app. Step 5 is the only edit to anything existing
(one `include` line), and step 7 undoes it in 30 seconds.

## Low-disk / low-RAM host (recommended path)

Recommended even now that the VM has 36 GB free: RAM is only ~3.6 GB available, and a build there needs ~1 GB RAM plus
several GB of cache. So **images are built on your laptop and streamed into the VM's Docker** (`docker save | gzip -1 | ssh ... 'gunzip | docker load'`,
no temp files on either side). Compose uses `image: sentinel/api:${SENTINEL_TAG:-local}` with `pull_policy: never`; `install.sh` never builds
(`--build` is only allowed with >= 12 GB free).

**Laptop (repo root):**
```bash
export SENTINEL_VM_HOST=HOST SENTINEL_VM_USER=USER SENTINEL_SSH_KEY=~/.ssh/KEY
bash deploy/shared-host/build-images.sh --api-only   # build amd64 api image, check the VM's free disk, type "yes", stream it
bash deploy/shared-host/push-to-vm.sh                # code + compose files (git archive HEAD), type "yes"
# env file once, see step 2:  scp ... .env.prod  &&  ssh ... 'chmod 600 /opt/sentinel/.env.prod'
```
**VM:**
```bash
cd /opt/sentinel/app
bash deploy/shared-host/preflight.sh                 # read-only; disk verdict FAIL < 3 GB free, WARN < 10 GB
bash deploy/shared-host/install.sh --api-only        # starts only sentinel-api (768 MB / 1 CPU), then add nginx (step 5)
bash deploy/shared-host/verify.sh
```
**Add the inspector later** (link analysis; without it the api works and link checks return an "unfetched" report):
```bash
# laptop:  bash deploy/shared-host/build-images.sh        (sends both images; or only the inspector is new, both is simplest)
# VM:      bash deploy/shared-host/install.sh              # no --api-only: starts the `inspector` compose profile too (1 GB / 1 CPU)
```
Inspector egress firewall (step 4b) is recommended before relying on it.

| Image | Before slimming (unpacked / compressed) | After (unpacked / compressed) |
|---|---|---|
| `sentinel/api` | 346 MB / 102 MB | 291 MB / 79 MB |
| `sentinel/inspector` | 949 MB / 354 MB | 697 MB / 265 MB |
| both | ~1.3 GB / 456 MB | ~1.0 GB / 344 MB |

(Measured locally: unpacked = `du -x /` in the container, compressed = `docker save | gzip -1 | wc -c`.)

Free-disk rule of thumb (docker root, usually `/var/lib/docker`): `build-images.sh` refuses unless free >= unpacked images x 1.5 + `SENTINEL_MIN_FREE_GB`
(default 2) - about 2.5 GB for `--api-only`, 3.5 GB for both - and `install.sh` refuses unless >= `SENTINEL_MIN_FREE_GB` (default 3) is still free after the
images are loaded. In practice keep ~4 GB free for api-only and ~5 GB for both. `SENTINEL_FORCE_LOW_DISK=1` overrides the laptop-side check only; think before using it,
the neighbour shares the disk. Memory caps (env, defaults sized for this VM): `SENTINEL_API_MEM=768m`, `SENTINEL_API_CPUS=1.0`,
`SENTINEL_INSPECTOR_MEM=1g`, `SENTINEL_INSPECTOR_CPUS=1.0`; put them in `/opt/sentinel/.env.prod` to change them (check with `docker stats --no-stream`).

If disk is still short: never run `docker system prune` / `docker image prune` (they would delete the neighbour's images). Instead: ship `--api-only` first;
delete your own leftovers (`/opt/sentinel/app.prev`, old `sentinel/*:TAG` images via `uninstall.sh --images`, apt cache `sudo apt-get clean`, old journal logs);
cap logs (already 10 MB x 3 per container); ask the neighbour's owner to clean their images/volumes; or move Docker's data root to a bigger disk (admin task).

Rollback: remove the nginx include (step 7) and `bash deploy/shared-host/uninstall.sh` (containers and networks only; images stay so you can re-install without re-uploading).
Add `--images` to also remove the `sentinel/api` and `sentinel/inspector` images (second typed confirmation; no other image is ever touched).
To go back to the previous image version, load the older tag (`build-images.sh --tag v1`, then `SENTINEL_TAG=v1 bash deploy/shared-host/install.sh`).

Optional Firebase credentials (FCM push): copy the JSON to `/opt/sentinel/firebase.json` (`chmod 644`, or `chown 10001`), uncomment the
`volumes:` example under the api service in `docker-compose.yml` (`/opt/sentinel/firebase.json:/run/secrets/firebase.json:ro`) and add
`FIREBASE_CREDENTIALS_FILE=/run/secrets/firebase.json` to `.env.prod`. It is mounted read-only and never baked into an image; enabled by uncommenting only.

**Not verified (no VM access):** everything here was exercised locally only (build, the stream pipeline against a stand-in for `ssh`, compose with
both profiles, nginx simulation). The real VM's `docker load` behaviour and disk reading, `df` on the real docker root, memory behaviour under the neighbour's load,
and ssh options with your actual ssh config are untested.

## 0. What this will and will not touch

| Thing | Touched? | Detail |
|---|---|---|
| Ports 80 / 443 | **No** | Sentinel publishes nothing there |
| Host port 8100 | New, loopback only | `127.0.0.1:8100` (not reachable from the internet) |
| Existing containers / compose projects | **No** | Sentinel is its own project `sentinel` |
| Existing Docker networks | **No** | Creates `sentinel_edge`, `sentinel_inspector_net`, `sentinel_inspector_egress` |
| Firewall / iptables / ufw | **No** | One OPTIONAL, scoped rule set is described in step 4b, run only if you choose |
| TLS certificates | **No** | The existing nginx keeps terminating TLS |
| nginx config | One line added | `include .../nginx-sentinel.conf;` inside the `interns.enthral.ai` server block (step 5, backed up first) |
| Files on disk | `/opt/sentinel/` | `app/` (code), `.env.prod` (secrets, mode 600), Docker images/layers under `/var/lib/docker` |
| CPU / RAM | Capped | api 768 MB / 1 CPU; inspector 1 GB / 1 CPU (env-tunable); images are built on your laptop, nothing is built on the VM by default |
| Logs | Capped | json-file, 10 MB x 3 per container |
| Database | External | Supabase; Sentinel runs its own migrations (`alembic upgrade head`) on start |

## 1. Run the preflight (read-only)

Once the code is on the VM (step 3) run `bash deploy/shared-host/preflight.sh`. You may also run it before,
by copying just that one file. It changes nothing. Add `sudo` only if you want it to read root-only nginx files.

| Line | Meaning / action |
|---|---|
| `FAIL docker daemon not reachable` | Add your user to the `docker` group (`sudo usermod -aG docker $USER`, re-login) or use sudo |
| `FAIL port 8100 already in use` | Pick a free port: `export SENTINEL_HOST_PORT=8101` for install.sh **and** change `8100` in `nginx-sentinel.conf` |
| `FAIL nginx already defines a /sentinel location` | Something else owns that path; stop and decide before continuing |
| `WARN nothing listens on 443` | nginx may run in Docker or on another host: see "nginx inside Docker" below |
| `WARN RAM < 4096 MB` | The neighbour may be squeezed during builds; free memory or wait |
| `WARN no swap` | Informational: container limits stop Sentinel from taking more than ~1.8 GB, but an OOM kills instead of swapping |
| `WARN load above 70%` | Build the images off-peak |
| `FAIL disk < 3 GB` / `WARN disk < 10 GB` | Images need ~0.3 GB (api) + ~0.7 GB (inspector) unpacked; free space first, never prune the neighbour's images |
| `WARN no nginx server block for interns.enthral.ai` | The server block lives elsewhere (another container/host). Add the include there |
| `WARN nginx binary not found` | nginx runs in Docker: see the alternative below |

Final line is `GO`, `GO with caution` (read the WARNs) or `NO-GO`.

## 2. Prepare `/opt/sentinel` and the env file

On the VM:
```bash
sudo mkdir -p /opt/sentinel && sudo chown "$USER" /opt/sentinel && chmod 755 /opt/sentinel
```
On your laptop, create `.env.prod` from `deploy/.env.prod.example` (generate secrets with `openssl rand -hex 24`).
Required: `DATABASE_URL` (Supabase **session pooler**, `sslmode=require`), `FAMILY_CODE_PEPPER` (>= 16 chars),
`INSPECTOR_SECRET` (>= 16 chars). Add the Token Factory / Tavily keys as in the example. `SITE_ADDRESS` is not
needed. The compose file sets `ENVIRONMENT`, `TRUST_PROXY_HEADERS`, `TRUSTED_PROXY_COUNT`, `ROOT_PATH`,
`INSPECTOR_URL` and `DEMO_ENABLED` itself (they override the file). Do not use a literal `$` in values
(write `$$`): compose interpolates the file.

Copy it (separate on purpose, never part of the code push) and lock it down:
```bash
scp -i ~/.ssh/KEY ./.env.prod USER@HOST:/opt/sentinel/.env.prod
ssh -i ~/.ssh/KEY USER@HOST 'chmod 600 /opt/sentinel/.env.prod'
```

## 3. Push the code (from your laptop, repo root)

```bash
SENTINEL_VM_HOST=HOST SENTINEL_VM_USER=USER SENTINEL_SSH_KEY=~/.ssh/KEY bash deploy/shared-host/push-to-vm.sh
```
It asks for `yes`, then ships `git archive HEAD` (committed files only) into `/opt/sentinel/app`
(old copy kept as `/opt/sentinel/app.prev`). It never touches `.env.prod`. Then on the VM, now run step 1.

## 4. Install (on the VM)

```bash
cd /opt/sentinel/app && bash deploy/shared-host/install.sh
```
Flags: `--api-only` (skip the inspector), `--no-build` (default; missing images -> prints the `build-images.sh` command and exits 1),
`--build` (build on the VM; refused below 12 GB free). It validates the env file (without printing it), applies the disk guard, runs
`docker compose ... -p sentinel [--profile inspector] up -d --no-build`, waits for the healthchecks and calls
`curl http://127.0.0.1:8100/health`. It does **not** edit nginx and deletes nothing. See the low-disk section above for the image workflow.

### 4b. Optional: inspector egress firewall
The inspector fetches attacker-supplied URLs. On a shared host it could reach the neighbour's published ports via
the host gateway or private ranges. `deploy/README.md` section 5 gives DOCKER-USER rules that drop traffic from the
**inspector egress subnet only** (`docker network inspect sentinel_inspector_egress -f '{{(index .IPAM.Config 0).Subnet}}'`).
Those rules only match Sentinel's subnet, so they do not affect the neighbour; they are not applied by any script
here. Recommended before relying on link checks; ask whoever owns the host if you are unsure.

## 5. Add the nginx include (the only change to existing config)

On this VM the `interns.enthral.ai` server blocks live in `/etc/nginx/sites-enabled/fastapi`: one `listen 80;` block and one
`listen 443 ssl;` block (`ssl_certificate` is configured in the 443 block only). Put the include **only inside the `listen 443 ssl;` block**.
The app is HTTPS-only: leave the port-80 block alone. If it does not redirect to https, the API is simply not reachable over plain http, which is what we want.

Find the right place:
```bash
sudo grep -n 'listen 443\|server {\|location /' /etc/nginx/sites-enabled/fastapi
```

1. Back up the file **outside** `sites-enabled`/`conf.d` (nginx would load a copy there twice):
   `sudo mkdir -p /etc/nginx/backup && sudo cp -a /etc/nginx/sites-enabled/fastapi /etc/nginx/backup/fastapi.$(date +%F-%H%M)`
2. Inside the `server { ... }` block that has `listen 443 ssl;` add this single line:
   `include /opt/sentinel/app/deploy/shared-host/nginx-sentinel.conf;`
3. `sudo nginx -t`
4. **Only if** it prints `syntax is ok` and `test is successful`: `sudo systemctl reload nginx`
   (graceful: workers finish current requests; no connection is dropped). If `-t` fails, remove the line and re-run `-t`; nothing was reloaded, nothing changed.

The snippet only adds `/sentinel` locations. It uses `location ^~ /sentinel/` (plus an exact `location = /sentinel` redirect): the `^~`
modifier makes the prefix match beat any REGEX location in the neighbour's block (e.g. `location ~* \.css$` or `~ ^/(api|docs)`), which would
otherwise capture `/sentinel/dashboard/dashboard.css` or similar. Read the snippet's comments for why each directive is there.

### nginx inside Docker (alternative)
If the neighbour's nginx is a container, `127.0.0.1` is that container. Preferred fix: none needed if you can run nginx on the host. Otherwise
set **both** `SENTINEL_BIND_ADDR=<docker0 gateway, usually 172.17.0.1>` and `SENTINEL_ALLOW_NON_LOOPBACK=1` in `/opt/sentinel/.env.prod` (`install.sh` refuses a non-loopback bind without both, and always refuses `0.0.0.0`; `TRUSTED_PROXY_CIDRS` already trusts the private/bridge ranges), re-run `install.sh`, and use
`proxy_pass http://172.17.0.1:8100/;` (or `host.docker.internal:8100` with `extra_hosts: host-gateway`, which edits the neighbour's compose). Allow
that docker subnet to the port in the host firewall if it is blocked. Details are at the bottom of `nginx-sentinel.conf`. Default stays loopback.

## 6. Verify

```bash
bash /opt/sentinel/app/deploy/shared-host/verify.sh          # or from the laptop; pass a URL to override
```
Checks `/health`, `/openapi.json`, that `/dashboard` is 404, registers a test device and deletes it again via `DELETE /v1/me`
(no test data remains), and confirms the deleted token returns 401. Also confirm the neighbour: open its usual URL and compare.

## 7. Rollback in 30 seconds

```bash
# 1. remove the include line (or: sudo cp -a the .bak file back)
sudo nano /etc/nginx/sites-enabled/interns
sudo nginx -t && sudo systemctl reload nginx     # Sentinel is now unreachable publicly; neighbour exactly as before
# 2. optionally stop Sentinel completely (typed confirmation; only sentinel-* containers/networks/images)
bash /opt/sentinel/app/deploy/shared-host/uninstall.sh
```
The Supabase database is not touched by uninstall; Sentinel's data stays there until you delete it in Supabase.
Files under `/opt/sentinel` stay (`sudo rm -rf /opt/sentinel` if you want them gone).

## 8. Updating Sentinel later

From the laptop: `bash deploy/shared-host/build-images.sh [--api-only]` then `bash deploy/shared-host/push-to-vm.sh` (same variables), then on the VM
`cd /opt/sentinel/app && bash deploy/shared-host/install.sh [--api-only]`. Only the `sentinel` project is recreated
(a few seconds of 502 for `/sentinel/` while the api restarts and migrates; the neighbour is unaffected). Roll back with `rm -rf /opt/sentinel/app && mv /opt/sentinel/app.prev /opt/sentinel/app` then install.sh again
(migrations are forward-only: only roll back across a release that added none).

## 9. Logs and monitoring

```bash
docker logs --tail 100 -f sentinel-api
docker logs --tail 100 sentinel-inspector
docker inspect -f '{{.State.Health.Status}}' sentinel-api sentinel-inspector
docker compose -p sentinel ps
sudo tail -f /var/log/nginx/error.log          # 502 / 413 reasons
```
Container logs rotate at 10 MB x 3 each. The api logs only exception types, not message text.

## 10. Keeping an eye on the neighbour

```bash
docker stats --no-stream          # per-container CPU / memory; Sentinel is capped at 768 MB + 1 GB by default
free -h && uptime                 # host memory and load
df -h /var/lib/docker /opt        # disk
docker system df                  # Docker disk use (do not run `docker system prune` on a shared host)
```
If the neighbour suffers: `docker compose -p sentinel stop` (instant, reversible with `start`) before debugging.

## 11. Supabase region

The database is in `ap-southeast-2` (Sydney). Every request pays that round trip from the VM; if the VM is elsewhere expect
noticeably slower `/v1/events`. Co-locate the VM or the project later (new Supabase project in the VM's region, `pg_dump` / restore,
change `DATABASE_URL`). Use the **session pooler** string (IPv4) and `sslmode=require`.

## 12. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| 502 from nginx | api not up: `docker compose -p sentinel ps`, `docker logs sentinel-api` (bad `DATABASE_URL`, Supabase IP allow-list, migration error); or wrong port in the snippet vs `SENTINEL_HOST_PORT`; or nginx in Docker (see above) |
| 404 `{"detail":"Not Found"}` on `/sentinel/...` | `proxy_pass` lacks the trailing `/` (prefix not stripped) |
| 301 loop / wrong URL | something else defines `/sentinel`; check `sudo nginx -T \| grep -n sentinel` |
| 413 on image upload | `client_max_body_size 6m` not applied: the include is outside the server block or a location with a lower limit wins; app limit is 5 MB |
| 401 | Missing/invalid bearer token (deleted device, or app registered against a different base URL) |
| 429 on register for everyone | all clients counted as one IP: nginx is behind another proxy/CDN so `$remote_addr` is that proxy; either use real-IP restoration in the neighbour's nginx (`real_ip_header`) or raise `REGISTER_PER_IP_PER_HOUR` |
| Client IP looks like nginx's/bridge's address | Expected for the proxy hop; the app trusts `X-Forwarded-For` only from peers inside `TRUSTED_PROXY_CIDRS` (default loopback + private ranges). A proxy on a public address must be added there |
| Rate limit never triggers / forged IPs counted | `TRUSTED_PROXY_COUNT` must be 1 and nginx must **replace** X-Forwarded-For (the snippet does); never use `$proxy_add_x_forwarded_for` |
| CORS errors | Not needed: the Android app is not a browser. Leave `CORS_ALLOW_ORIGINS` empty |
| `/sentinel/docs` assets 404 | `ROOT_PATH` is not `/sentinel` in the running container (`docker exec sentinel-api env \| grep ROOT_PATH`) |
| Port in use on install | Set `SENTINEL_HOST_PORT` and edit the port in the snippet |

## What was NOT verified (no access to the VM)

Verified locally: compose file validity, the api/inspector stack running as project `sentinel-localtest`, the nginx
snippet syntax and prefix stripping/XFF replacement in a throwaway nginx container, the scripts via shellcheck, and the backend tests.
**Not verified:** how the snippet interacts with the real `interns.enthral.ai` server block (existing `location /`, `add_header` inheritance, a
CDN/proxy in front), real TLS, Docker networking/firewall (ufw, DOCKER-USER, address pools) on the real host, Supabase reachability and latency from the VM,
the 4 GB RAM assumption under the neighbour's real load, and `nginx -T` parsing of the real config layout in `preflight.sh`.
