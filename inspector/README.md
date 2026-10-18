# Sentinel link inspector

Internal service that looks at attacker-supplied links on behalf of the Sentinel API. The API
never fetches a message's URL itself; it asks this service, which returns a `LinkReport`.

```
POST /inspect   {"url": "..."}   header X-Inspector-Secret: <INSPECTOR_SECRET>
  -> 200 LinkReport JSON (always 200 for a well-formed authorised request; problems => fetched_ok=false)
  -> 401 bad/missing secret, 413 body > 4 KB, 422 malformed body / unknown fields, 503 busy
GET  /health    -> {"ok": true}   (unauthenticated, reveals nothing)
```

`LinkReport` (identical to `backend/app/pipeline/link_client.LinkReport`):
`final_url`, `redirect_chain` (every URL requested, in order, starting with the normalised
original and ending with the final / blocked hop), `status`, `title` (<= 200 chars),
`has_password_field`, `has_otp_or_upi_or_card_field` (otp / one-time-code / upi / vpa / card
number / cvv / mpin / atm pin / aadhaar, English and Hindi; "pin code" excluded), `triggers_download`,
`apk_download`, `brand_mismatch` (brand named in the title, or in the text of a page with a
credential form, while the final host is not that brand's domain), `threat_feed_hits`
(`safe_browsing:<THREAT_TYPE>`, `urlhaus:<threat>`), `text_excerpt` (<= 1,500 chars, control and
format characters removed; UNTRUSTED), `fetched_ok`.

## Run

```
cd inspector
uv sync
uv run playwright install chromium --only-shell        # optional: without it, static analysis only
INSPECTOR_SECRET=$(openssl rand -hex 24) uv run uvicorn --factory app:create_app --host 127.0.0.1 --port 8080
uv run pytest -q                                        # Chromium tests skip if it cannot launch
```

Environment: `INSPECTOR_SECRET` (required, >= 16 chars, the app refuses to start otherwise),
`SAFE_BROWSING_API_KEY` (optional; no key => Safe Browsing skipped), `URLHAUS_AUTH_KEY`
(optional; without it URLhaus is tried anonymously and failures are ignored). The API side needs
`INSPECTOR_URL=http://inspector:8080` and the same `INSPECTOR_SECRET`.

Docker: `backend/docker-compose.yml` builds `../inspector` as service `inspector`. Set
`INSPECTOR_SECRET` in `backend/.env` (used by both api and inspector).

## Network layout (compose)

| network            | internal | members                | purpose                                   |
|--------------------|----------|------------------------|-------------------------------------------|
| `default`          | no       | api, db                | as before; db published on 127.0.0.1:5440 |
| `inspector_net`    | yes      | api, inspector         | api -> inspector calls only               |
| `inspector_egress` | no       | inspector              | inspector's outbound internet             |

The inspector port is `expose`d, never published to the host. The inspector is not on `default`,
so it has no route to `db`. The db stays on `default` (not an `internal` network) because the
host-published dev port that the test suite uses does not work on internal networks.

Container hardening: non-root uid 10001, `read_only: true` root filesystem, `/tmp` tmpfs (256 MB,
nosuid, nodev), `cap_drop: [ALL]`, `no-new-privileges`, 1 GB memory, 1 CPU, 256 pids, no secrets in
the image.

## What the application enforces (SSRF and abuse)

* Only `http`/`https`; no userinfo; ports 80, 443, 8080, 8443 only (common web ports; 8080/8443 are
  frequent on phishing kits; anything else is refused); URL <= 2048 chars; no control characters or
  backslashes; hostname <= 253 chars, valid LDH labels, alphabetic/IDNA TLD (this rejects every
  IP-literal spelling: dotted, decimal `2130706433`, hex `0x7f000001`, octal `0177.0.0.1`, short
  `127.1`, `[::1]`, `[::ffff:127.0.0.1]`); literal IPs are rejected outright; special/internal names
  (`localhost`, `*.local`, `*.internal` incl. `metadata.google.internal`, `*.test`, `*.onion`,
  `*.arpa`, `*.svc.cluster.local`, ...) are rejected before DNS.
* The service resolves the hostname itself (all A/AAAA answers) and rejects the URL if ANY answer
  is not a global unicast address (`ipaddress.is_global` plus explicit private, loopback,
  link-local incl. 169.254.169.254 and fd00:ec2::254, CGNAT 100.64/10, multicast, reserved,
  unspecified, documentation, benchmarking; IPv4-mapped, 6to4, Teredo, NAT64 and IPv4-compatible
  IPv6 are rejected, and their embedded IPv4 is checked too).
* Connection pinning: the HTTP client (httpcore) uses a network backend that can only open a TCP
  connection to the IP validated for that (host, port); TLS SNI/certificate checks still use the
  hostname. There is no second DNS lookup, so DNS rebinding between check and connect is
  impossible. Unix sockets are refused.
* Redirects (3xx and meta refresh <= 10 s) are followed manually, max 5, and every hop is parsed,
  resolved, validated and pinned again (a rebinding answer on a later hop is caught).
* No cookies, no credentials, no proxy env (httpcore does not read proxy variables; the feed client
  uses `trust_env=False`), `Accept-Encoding: identity`.
* 10 s wall-clock budget for the whole inspection (DNS, fetch, render, feeds) via `asyncio`
  timeouts; 4 s per-read idle timeout; body capped at 2 MB of DECOMPRESSED bytes (bounded zlib
  decompressor; 4 MB raw cap; unknown encodings are not decoded), so gzip bombs and slow-drip
  bodies are bounded.
* Downloads (Content-Disposition attachment, download MIME types, executable/archive extensions,
  APK magic) are detected from headers + a <= 4 KB sniff, then the connection is dropped. Nothing
  is ever written to disk.
* API: constant-time secret check before the body is read, 4 KB request cap, strict schema
  (unknown fields rejected), max 4 concurrent inspections (others wait <= 2 s then 503), no
  OpenAPI/docs endpoints.
* No env var or request field relaxes any of this. Tests reach their 127.0.0.1 server only via the
  code-only factory argument `create_app(_test_allow={("127.0.0.1", port)})` plus an injected
  resolver; IP literals and internal names stay blocked even then.

## Rendering (Chromium)

The service fetches the HTML itself (above) and only then renders it in headless Chromium
(Playwright), which must never touch the network:

* the HTML is served by the route handler as the document of a fake `https://render.sentinel.invalid/`
  origin with CSP `default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'`;
* `context.route("**/*")` aborts every other request; further navigations (JS, meta refresh,
  `window.open`) are answered locally with 204 so the page stays put; WebSockets are swallowed;
* the context is offline, service workers blocked, downloads refused, fresh per call and closed in
  `finally`; small mobile viewport; hard 3 s render timeout (wedged renderers are restarted);
* launch flags: `--js-flags=--jitless` (no JIT, no WASM), dead proxy `127.0.0.1:9` for all traffic
  incl. loopback, `--host-resolver-rules=MAP * ~NOTFOUND`, WebRTC limited to proxied UDP;
* secrets are removed from Chromium's environment, and the Python process marks itself
  non-dumpable so a same-uid child cannot read its `/proc/<pid>/environ` or memory;
* the rendered DOM is read with `page.content()` (Playwright's isolated world), and the result is
  UNIONED with the static analysis: page scripts can add fields but cannot hide ones present in
  the served HTML. If Chromium is missing or fails, static analysis (lexbor parser) is used alone.

Tests prove that a page referencing stylesheets, scripts, images, iframes, `fetch`, WebSocket,
`sendBeacon` and dynamically inserted images pointing at the test server produces no request
other than the inspector's own pinned fetch.

## Threat feeds

Google Safe Browsing v4 `threatMatches:find` and URLhaus, for the original and the final URL,
3 s timeout, 64 KB response cap, labels sanitised to `[A-Za-z0-9_]`. Hits only ever add risk; a
clean or failed lookup means nothing. Privacy: the inspected URLs (which can contain tracking or
victim tokens) are disclosed to Google and abuse.ch.

## NOT defended / known limits

* **Chromium's own sandbox is usually unavailable in the container.** Docker's default seccomp
  profile blocks the user namespaces Chromium needs once all capabilities are dropped, so the
  renderer falls back to `--no-sandbox` (logged as a warning). A V8/Blink exploit would then run as
  uid 10001 inside the inspector container. Mitigations: jitless JS, no network from Chromium,
  read-only rootfs, no capabilities, resource limits, no secrets in the browser env, non-dumpable
  parent, and network separation from db. Recommended: run with a Chromium seccomp profile that
  allows user namespaces (or gVisor `runsc`) so the sandbox launches, or set `render=False`.
* **A compromised inspector container still has outbound internet and can reach the api** on
  `inspector_net`, and, at the IP level, other hosts reachable from its bridge (including the
  Docker host gateway and cloud metadata). The application checks only protect the fetcher, not
  arbitrary code. Defence in depth that SHOULD be added on the host: an egress firewall for the
  inspector's bridge that drops RFC1918, 100.64.0.0/10, 169.254.0.0/16 (incl. 169.254.169.254),
  127.0.0.0/8, fc00::/7, fe80::/10 and the host's own addresses, e.g. in `DOCKER-USER`:
  `iptables -I DOCKER-USER -i <inspector_egress bridge> -d 169.254.0.0/16 -j DROP` (repeat per
  range), and IMDSv2 hop-limit 1 on cloud VMs.
* The IP blocklist is only as good as Python's `ipaddress` tables plus the explicit checks above;
  6to4/Teredo/NAT64 are refused entirely.
* TLS certificates are verified; phishing sites with broken TLS are reported `fetched_ok=false`
  rather than inspected.
* Cloaking: kits that serve benign content to datacenter IPs, non-mobile UAs or first visits will
  look clean. That is why a clean report never lowers a tier in the API.
* JS that builds a form after more than ~250 ms, or only after user interaction, is not seen.
* The text excerpt and title are attacker-controlled. They are length-capped and cleaned, and the
  API's agent tool marks them `[untrusted web text]`, but they must never be treated as
  instructions.
* The brand list for `brand_mismatch` is small and hand-maintained; no screenshot/vision check.
* DNS resolution runs in a worker thread; a timed-out lookup is abandoned, not killed.
