#!/usr/bin/env bash
# READ-ONLY preflight for installing Sentinel on a shared host. Changes nothing: no docker/nginx/firewall
# modification, no files written. Prints PASS / WARN / FAIL lines and a final go/no-go.
#
#   bash deploy/shared-host/preflight.sh            # as your normal user
#   sudo bash deploy/shared-host/preflight.sh       # optional: lets `nginx -T` read root-only configs
#
# Env: SENTINEL_HOST_PORT (default 8100), SENTINEL_DOMAIN (default interns.enthral.ai),
#      SENTINEL_API_MEM (768m), SENTINEL_INSPECTOR_MEM (1g) = the memory caps compose will apply
# Disk verdict: FAIL below 3 GB free, WARN below 10 GB (images are loaded from the laptop, never built here).
set -u

PORT="${SENTINEL_HOST_PORT:-8100}"
DOMAIN="${SENTINEL_DOMAIN:-interns.enthral.ai}"
FAIL_DISK_GB=3
WARN_DISK_GB=10
MIN_RAM_MB=4096

FAILS=0
WARNS=0
pass() { printf 'PASS  %s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*"; WARNS=$((WARNS + 1)); }
fail() { printf 'FAIL  %s\n' "$*"; FAILS=$((FAILS + 1)); }
info() { printf 'INFO  %s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

echo "== Sentinel shared-host preflight (read-only) =="

# --- OS ---
if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  os_name="$(. /etc/os-release && printf '%s' "${PRETTY_NAME:-unknown}")"
else
  os_name="unknown"
fi
info "OS: ${os_name}, kernel $(uname -r), arch $(uname -m)"
case "$(uname -m)" in
  x86_64 | aarch64 | arm64) pass "architecture supported by the base images" ;;
  *) warn "unusual architecture $(uname -m): image build may fail" ;;
esac

# --- docker ---
if have docker; then
  info "docker client: $(docker --version 2>/dev/null)"
  if docker info >/dev/null 2>&1; then
    pass "docker daemon reachable by this user"
  else
    fail "docker daemon not reachable by this user (add the user to the 'docker' group or use sudo)"
  fi
  if cv="$(docker compose version --short 2>/dev/null)" && [ -n "$cv" ]; then
    major="${cv#v}"; major="${major%%.*}"
    if [ "${major:-0}" -ge 2 ] 2>/dev/null; then
      pass "docker compose v2 plugin: ${cv}"
    else
      fail "docker compose is ${cv}; v2 required"
    fi
  else
    fail "docker compose v2 plugin not found (install docker-compose-plugin)"
  fi
else
  fail "docker not installed"
fi

# --- ports ---
listeners() { ss -H -ltnp "sport = :$1" 2>/dev/null; }
if have ss; then
  for p in 80 443; do
    out="$(listeners "$p")"
    if [ -n "$out" ]; then
      who="$(printf '%s\n' "$out" | grep -o 'users:(([^)]*))' | sort -u | tr '\n' ' ')"
      pass "port ${p} is in use by: ${who:-<process hidden: re-run with sudo to see>} (expected: the existing nginx; Sentinel will NOT use it)"
    else
      warn "nothing listens on port ${p}: is the existing app/nginx really on this host?"
    fi
  done
  if [ -n "$(listeners "$PORT")" ]; then
    fail "port ${PORT} is already in use (set SENTINEL_HOST_PORT to a free port, and use it in the nginx snippet)"
  else
    pass "port ${PORT} is free"
  fi
else
  warn "'ss' not available: cannot check listening ports"
fi

# --- memory / cpu ---
if [ -r /proc/meminfo ]; then
  avail_mb=$(awk '/^MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo)
  total_mb=$(awk '/^MemTotal:/ {printf "%d", $2/1024}' /proc/meminfo)
  swap_total=$(awk '/^SwapTotal:/ {printf "%d", $2/1024}' /proc/meminfo)
  swap_free=$(awk '/^SwapFree:/ {printf "%d", $2/1024}' /proc/meminfo)
  info "RAM: ${avail_mb} MB available of ${total_mb} MB; swap: ${swap_free} MB free of ${swap_total} MB"
  if [ "$avail_mb" -ge "$MIN_RAM_MB" ]; then
    pass "available RAM >= ${MIN_RAM_MB} MB (Sentinel caps: api ${SENTINEL_API_MEM:-768m} + inspector ${SENTINEL_INSPECTOR_MEM:-1g})"
  else
    warn "available RAM ${avail_mb} MB < ${MIN_RAM_MB} MB: the neighbour could be squeezed; the image build also needs ~1 GB"
  fi
  if [ "$swap_total" -eq 0 ]; then
    warn "no swap configured: an OOM would kill a process instead of swapping (container limits protect the neighbour, but check)"
  fi
fi
cpus="$(nproc 2>/dev/null || echo '?')"
load="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo '?')"
info "CPUs: ${cpus}; load average (1/5/15 min): ${load}"
if [ "$cpus" != "?" ] && [ -r /proc/loadavg ]; then
  load1="$(cut -d' ' -f1 /proc/loadavg)"
  if awk -v l="$load1" -v c="$cpus" 'BEGIN{exit !(l > c*0.7)}'; then
    warn "1-minute load ${load1} is above 70% of ${cpus} CPUs: build the images off-peak"
  else
    pass "CPU load is comfortable"
  fi
  if [ "$cpus" -lt 3 ]; then
    warn "only ${cpus} CPU(s): Sentinel's default limits total 2 CPUs; they are caps, not reservations, but expect contention under load"
  fi
fi

# --- disk ---
check_disk() {
  d="$1"
  if [ ! -d "$d" ]; then
    info "${d} does not exist yet (checking the filesystem it will live on)"
    d="$(dirname "$d")"
    [ -d "$d" ] || d=/
  fi
  free_mb=$(df -Pk "$d" 2>/dev/null | awk 'NR==2 {printf "%d", $4/1024}')
  if [ -z "$free_mb" ]; then
    warn "cannot determine free space for $1"
    return
  fi
  free_gb=$((free_mb / 1024))
  if [ "$free_gb" -lt "$FAIL_DISK_GB" ]; then
    fail "$1: only ${free_mb} MB free: below ${FAIL_DISK_GB} GB, do not install (free space first; load images with build-images.sh, never build here)"
  elif [ "$free_gb" -lt "$WARN_DISK_GB" ]; then
    warn "$1: ${free_gb} GB free: OK for the pre-built images (api ~0.4 GB, + inspector ~1 GB) but below ${WARN_DISK_GB} GB; do not build on this host"
  else
    pass "$1: ${free_gb} GB free (>= ${WARN_DISK_GB} GB)"
  fi
}
info "disk thresholds: FAIL < ${FAIL_DISK_GB} GB free, WARN < ${WARN_DISK_GB} GB free"
docker_root=""
if have docker; then docker_root="$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || true)"; fi
check_disk /opt
check_disk "${docker_root:-/var/lib/docker}"

# --- memory caps in effect ---
info "memory caps compose will apply: api ${SENTINEL_API_MEM:-768m}, inspector ${SENTINEL_INSPECTOR_MEM:-1g} (set SENTINEL_API_MEM / SENTINEL_INSPECTOR_MEM to change)"
if have docker && docker info >/dev/null 2>&1; then
  for c in sentinel-api sentinel-inspector; do
    if docker inspect "$c" >/dev/null 2>&1; then
      lim="$(docker inspect -f '{{.HostConfig.Memory}}' "$c" 2>/dev/null || echo 0)"
      if [ "${lim:-0}" -gt 0 ] 2>/dev/null; then info "running ${c}: memory cap $((lim / 1048576)) MB"; else info "running ${c}: NO memory cap"; fi
    fi
  done
fi

# --- nginx ---
if have nginx; then
  info "nginx: $(nginx -v 2>&1)"
  dump="$(nginx -T 2>/dev/null)"
  if [ -z "$dump" ]; then
    warn "could not read the nginx config (permission?). Re-run with sudo for the server-block check"
  else
    # Find server blocks whose server_name contains the domain; print ONLY file path + listen lines + whether
    # a certificate is configured. Never prints the config body, cert paths or any other directive.
    report="$(printf '%s\n' "$dump" | awk -v dom="$DOMAIN" '
      /^# configuration file / { f=$0; sub(/^# configuration file /,"",f); sub(/:$/,"",f); next }
      {
        line=$0; sub(/#.*/,"",line)
        if (!inblk && line ~ /^[ \t]*server[ \t]*\{/) { inblk=1; depth=0; buf=""; file=f; nl=0 }
        if (inblk) {
          n=gsub(/\{/,"{",line); m=gsub(/\}/,"}",line); depth+=n-m
          buf=buf line "\n"
          if (depth<=0) {
            inblk=0
            if (buf ~ ("server_name[^;]*[ \t]" dom "[ \t;]")) {
              print "FILE " file
              k=split(buf, L, "\n")
              for (i=1;i<=k;i++) {
                if (L[i] ~ /^[ \t]*listen[ \t]/) { x=L[i]; gsub(/^[ \t]+/,"",x); print "LISTEN " x }
                if (L[i] ~ /^[ \t]*ssl_certificate[ \t]/) hascert=1
              }
              print "CERT " (hascert ? "configured" : "none-in-this-block")
              hascert=0
            }
          }
        }
      }')"
    if [ -n "$report" ]; then
      pass "nginx has a server block for ${DOMAIN}:"
      printf '%s\n' "$report" | sed 's/^FILE /        file:   /; s/^LISTEN /        listen: /; s/^CERT /        ssl_certificate: /'
      info "add the single include line from nginx-sentinel.conf INSIDE that server block (README step 5)"
    else
      warn "no nginx server block with server_name ${DOMAIN} found in 'nginx -T' (is TLS handled elsewhere, e.g. a container?)"
    fi
    if printf '%s\n' "$dump" | sed 's/#.*//' | grep -Eq 'location[^{]*[ \t=~^*]/sentinel'; then
      fail "nginx already defines a /sentinel location: resolve the clash before including the Sentinel snippet"
    else
      pass "no existing /sentinel location in nginx"
    fi
  fi
else
  warn "nginx binary not found on the host: if nginx runs in Docker see 'nginx inside Docker' in README / nginx-sentinel.conf"
fi

# --- existing docker footprint ---
if have docker && docker info >/dev/null 2>&1; then
  n_run="$(docker ps -q 2>/dev/null | wc -l | tr -d ' ')"
  n_all="$(docker ps -aq 2>/dev/null | wc -l | tr -d ' ')"
  n_net="$(docker network ls -q 2>/dev/null | wc -l | tr -d ' ')"
  info "docker: ${n_run} running / ${n_all} total containers, ${n_net} networks (these are only counted, never touched)"
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -Eq '^sentinel-'; then
    info "containers named sentinel-* already exist: this looks like a re-install/update (fine)"
  fi
  if docker network ls --format '{{.Name}}' 2>/dev/null | grep -q '^sentinel_'; then
    info "sentinel_* networks already exist: re-install/update (fine)"
  fi
  # address-pool exhaustion warning: each compose network takes a /16 from 172.17-31 by default
  if [ "$n_net" -ge 25 ]; then
    warn "${n_net} docker networks: the default address pool may be nearly exhausted (Sentinel needs 3 more)"
  fi
fi

echo
if [ "$FAILS" -gt 0 ]; then
  echo "RESULT: NO-GO  (${FAILS} FAIL, ${WARNS} WARN): fix the FAIL lines first. Nothing was changed."
  exit 1
fi
if [ "$WARNS" -gt 0 ]; then
  echo "RESULT: GO with caution  (0 FAIL, ${WARNS} WARN): read each WARN (README step 1). Nothing was changed."
else
  echo "RESULT: GO  (0 FAIL, 0 WARN). Nothing was changed."
fi
exit 0
