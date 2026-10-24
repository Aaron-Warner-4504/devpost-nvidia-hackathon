# Deploying Sentinel on a single VM (Nebius or any Linux host)

Stack (`deploy/docker-compose.prod.yml`): **caddy** (HTTPS, the only published ports 80/443) -> **api**
(`backend`, `prod` target: non-root, read-only filesystem, migrates then serves) -> **db** (pgvector
Postgres on an internal network, named volume `pgdata`); **inspector** (sandboxed link fetcher) reachable by
the api only. Nothing here was run on Nebius: the compose file was validated with `docker compose config`
and the api `prod` image was built and smoke-tested locally; the inspector image and Caddy certificate
issuance are untested until the first real deploy.

## 1. Provision
1. Create a Nebius Compute VM (Ubuntu 22.04/24.04, 2+ vCPU, 4+ GB RAM, 30+ GB disk; the inspector's Chromium
   wants ~1 GB). Give it a public IP and open inbound TCP 80, 443 and UDP 443 (and 22 from your IP only).
2. Point a DNS A record (e.g. `sentinel.example.com`) at the VM's public IP. Caddy needs it to get a
   Let's Encrypt certificate.
3. Install Docker Engine + the compose plugin: <https://docs.docker.com/engine/install/ubuntu/>, then
   `sudo usermod -aG docker $USER` and log in again.

## 2. Configure
```bash
git clone <repo-url> sentinel && cd sentinel
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
# fill in: SITE_ADDRESS, POSTGRES_PASSWORD, FAMILY_CODE_PEPPER, INSPECTOR_SECRET (openssl rand -hex 24),
# TOKEN_FACTORY_API_KEY and model IDs, optional TAVILY_API_KEY / threat-feed keys
```
`deploy/.env.prod` is gitignored; secrets never go in the compose file. The compose file forces
`ENVIRONMENT=prod`, `TRUST_PROXY_HEADERS=true`, `TRUSTED_PROXY_COUNT=1` (the client IP is the entry Caddy
appends to `X-Forwarded-For`) and refuses to start without `POSTGRES_PASSWORD`, `FAMILY_CODE_PEPPER`,
`INSPECTOR_SECRET` and `SITE_ADDRESS`. Never publish the api or db ports yourself, or rate limiting by client
IP can be bypassed with a forged header.

## 3. Start
```bash
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --build
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml ps      # api healthy
curl https://sentinel.example.com/health                                              # {"status":"ok"}
```
(`--env-file` feeds the `${...}` interpolation; compose does not read `deploy/.env.prod` for that on its
own.) The api entrypoint runs `alembic upgrade head` before uvicorn on every start.

## 4. Demo and evaluation against the deployment
```bash
cd backend
uv sync
uv run python -m sim.inject --user demo --base-url https://sentinel.example.com          # 3-day timeline
uv run python -m sim.inject --user demo --base-url https://sentinel.example.com --reset  # start over
uv run python scripts/latency_check.py --base-url https://sentinel.example.com           # p50/p95 per mode
uv run python -m eval.run_eval --llm live                                                # needs a local .env
```
`sim.inject` keeps the token in `backend/.demo_token` (gitignored). `eval.run_eval` replays the scenarios
**in-process** against its own `<db>_eval` database (it does not call the public URL); run it on a machine
with `DATABASE_URL` pointing at a scratch Postgres and, for `--llm live`, the Token Factory key in the
environment. Latency thresholds: p95 < 6 s per `/v1/events` with the agent, < 300 ms rules-only; the agent
number is only meaningful with `AGENT_MODE=hybrid` and a working LLM key.
To show the web dashboard, set `DEMO_ENABLED=true` in `deploy/.env.prod`, recreate the api container, open
`https://<host>/dashboard` and paste the token from `.demo_token`; switch it back off afterwards.

## 5. Host egress firewall for the inspector (do this before going public)
The inspector fetches attacker-supplied URLs. Docker networks do not stop it reaching the cloud metadata
service, the VM's private network or other containers' published ports, so drop that traffic on the host in
the `DOCKER-USER` chain (it runs before Docker's own rules). Find the inspector's egress subnet:
```bash
docker network inspect sentinel_inspector_egress -f '{{(index .IPAM.Config 0).Subnet}}'   # e.g. 172.20.0.0/16
```
```bash
EGRESS=172.20.0.0/16   # the value printed above
for dst in 169.254.0.0/16 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 100.64.0.0/10 127.0.0.0/8 224.0.0.0/4; do
  sudo iptables -I DOCKER-USER -s "$EGRESS" -d "$dst" -j DROP
done
# IPv6: block link-local / ULA, or disable IPv6 in Docker (the default)
```
Because `-I` inserts at the top, the DROPs win over Docker's ACCEPT rules. Note this also blocks the
inspector's DNS if your resolver is on a private address: allow it first with a
`-I DOCKER-USER -s $EGRESS -d <resolver-ip> -p udp --dport 53 -j ACCEPT` placed *after* the DROP loop
(so it ends up above them). Persist with `sudo apt install iptables-persistent && sudo netfilter-persistent save`,
and re-check the subnet after `docker compose down -v` (it can change). Also set the Nebius security group
to deny inbound everything except 22/80/443, and if the platform supports it enforce IMDS hardening at the
VM level too. Verify from inside the container:
`docker compose ... exec inspector python -c "import urllib.request as u; u.urlopen('http://169.254.169.254', timeout=3)"`
must time out.

## 6. Backups
```bash
# nightly cron, as the deploy user
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml exec -T db \
  sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > /var/backups/sentinel-$(date +%F).dump
find /var/backups -name 'sentinel-*.dump' -mtime +14 -delete
```
Copy dumps off the VM (object storage) and test a restore:
`pg_restore -U sentinel -d sentinel_restore_test --clean --if-exists < sentinel-YYYY-MM-DD.dump`.
Dumps contain redacted message text and user data: encrypt them in transit and at rest. Also snapshot the
`caddy_data` volume if you want to keep the issued certificate.

## 7. Rotating secrets
Edit `deploy/.env.prod`, then `docker compose --env-file ... up -d` (recreates changed services).
- `INSPECTOR_SECRET`: recreates api and inspector together; no data impact.
- `TOKEN_FACTORY_API_KEY`, `TAVILY_API_KEY`, threat-feed keys: swap and recreate the api / inspector.
- `FAMILY_CODE_PEPPER`: invalidates every pending pairing code (existing family links are unaffected).
- `POSTGRES_PASSWORD`: also run `ALTER USER sentinel PASSWORD '<new>'` in the db first
  (`docker compose ... exec db psql -U sentinel -c ...`), because the volume keeps the old password.
- Device tokens are per user and stored hashed; there is no server-side key to rotate. A user can
  `DELETE /v1/me` and re-register.

## Updating
`git pull && docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --build`.
Migrations run on api start. Tag releases (`git tag hackathon-v1`) after the latency and eval checks pass.
