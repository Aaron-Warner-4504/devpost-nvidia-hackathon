# Sentinel on a shared host (behind an existing nginx, under `/sentinel/`)

Goal: add Sentinel to a VM that already runs another production app behind nginx for `interns.enthral.ai`,
without changing that app. Sentinel is served at **https://interns.enthral.ai/sentinel/**; nginx strips the
prefix and proxies to `127.0.0.1:8100`. The database is an external Supabase Postgres. Android base URL:
`https://interns.enthral.ai/sentinel` (the client appends `v1/...`).

Every step before step 5 is invisible to the neighbour app. Step 5 is the only edit to anything existing
(one `include` line), and step 7 undoes it in 30 seconds.

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
| CPU / RAM | Capped | api 1 GB / 1 CPU; inspector 1.5 GB / 1.5 CPU; image build briefly uses CPU/RAM (build off-peak) |
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
| `WARN no swap` | Informational: container limits stop Sentinel from taking more than 2.5 GB, but an OOM kills instead of swapping |
| `WARN load above 70%` | Build the images off-peak |
| `WARN disk < 10 GB` | Images need ~2 GB; free space or move Docker's data root |
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
It validates the env file (without printing it), runs
`docker compose -f deploy/shared-host/docker-compose.yml --env-file /opt/sentinel/.env.prod -p sentinel up -d --build`,
waits for both healthchecks, and calls `curl http://127.0.0.1:8100/health`. The first build takes a few minutes
and some CPU: run it off-peak (`nice` is not needed; the build is single project, but Docker builds use all cores).
It does **not** edit nginx.

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

From the laptop: `bash deploy/shared-host/push-to-vm.sh` (same variables), then on the VM
`cd /opt/sentinel/app && bash deploy/shared-host/install.sh`. Only the `sentinel` project is rebuilt and
recreated (a few seconds of 502 for `/sentinel/` while the api restarts and migrates; the neighbour is unaffected).
Build off-peak. Roll back with `rm -rf /opt/sentinel/app && mv /opt/sentinel/app.prev /opt/sentinel/app` then install.sh again
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
docker stats --no-stream          # per-container CPU / memory; Sentinel is capped at 1 GB + 1.5 GB
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
