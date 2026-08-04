# qwdtt roadmap — remaining work

The transport stack is complete and verified on-device (S23 Ultra, KernelSU Next,
whitelisted SIM): a bound app egresses at the VPS through VK TURN with no manual
interface binding. qWDTT now ships as a first-class program module
(`rust/zdtd/src/programs/qwdtt.rs`); the generic `myprogram` + `myvpn` wiring is
gone.

## Done (for context)

- **M0** open questions resolved (`docs/M0-findings.md`).
- **M1** headless transport supervisor — verified on-device.
- **M2** amneziawg-go bring-up of `zdtdqw0` — verified on-device.
- **M3** automatic per-app routing — verified on-device.
- **M5 (core)** first-class `qwdtt` program module: profile state, validation,
  conflict checks, start/stop lifecycle and the `vpn_netd` handover, registered in
  the runtime (`docs/zdtd-integration.md`).
- Upstream deltas (atomic `wg-turn.conf` write, `STATS|` marker) as pinned
  patches (`upstream/qwdtt/`); CI builds both binaries.
- Hardening already landed: skip hash re-validation on local wg failure; local-time
  logging with a configurable UTC offset and no duplicate timestamps; suppressed
  the config-box PrivateKey log leak; optional `mtu` override.

---

## SOCKS5 mode (upstream v1.3.7+) — experimental alternative

Upstream added a client mode that runs WireGuard in a **userspace netstack inside
the transport process** and serves SOCKS5, so no TUN, `amneziawg-go` or `awg` is
involved. qwdtt-cli exposes it as `mode = socks` (+ `socks_addr`); the supervisor
then skips the whole WireGuard stage and treats "SOCKS port accepting" as
readiness. Apps are routed with ZDT-D's **myproxy** (`UID → iptables redirect →
t2s → SOCKS5`) instead of `myvpn`.

Attractions: no TUN, no amneziawg-go/awg dependency, no `myvpn` CIDR race, no
netd network lifecycle.

**Blocking question before adopting it:** the upstream SOCKS server is **TCP
CONNECT only** — no UDP ASSOCIATE, IPv6 rejected. Hostnames passed through SOCKS
resolve inside the tunnel, but app UDP (QUIC, direct DNS) is not carried and goes
direct, which the whitelist ISP drops. Whether that is acceptable depends on how
`t2s` handles DNS/UDP for redirected apps — test on the SIM before switching.

Until that is answered, `mode = vpn` stays the default and the supported path;
`socks` is for A/B testing only.

## M4 — hardening

Ordered by value. Each is independent.

### 1. Interface stealth / Zygisk hiding  *(core design goal)*
- **Rename `zdtdqw0` → `zdtdvpn0`** — *DEFERRED (user postponed)*. The interface is
  hidden today via the **exact-name** match from `vpn_netd/applied.json` (the qwdtt
  module records `owner_program = "qwdtt"`, now accepted by the native layer), but
  the name does **not** match Zygisk's *fallback* matcher
  (`tun*/tap*/wg*/awg*/utun*/ppp*/ipsec*/xfrm*/l2tp*/gre*/amneziawg*/*vpn*/if<n>`,
  see `zygisk/src/main.cpp:looks_like_tunnel_interface_name`). A name containing
  `vpn` gets the fallback too, closing the window where `applied.json` is stale.
  Touch points: the `tun` default in `qwdtt.rs`, example config, docs.
- **Turn hiding on for the target app** — separate from UID routing. Requires:
  Zygisk module installed (setup marker present pre-install); `proxyInfo/enabled.json`
  and `setting/start.json` both `{"enabled": true}`; and the app's UID present in
  `proxyInfo/out_program` (`package=uid`). Populate/enable via the ZDT-D app; note
  in docs that the routing list and the Zygisk target list are distinct.
- **Verify** from inside the target UID that `zdtdqw0` is absent from `getifaddrs`,
  `if_nametoindex`, `/proc/net/*`, `/sys/class/net`, and `NETLINK_ROUTE`.

### 2. Watchdog enablement
- `STATS|` is proven emitting on-device. Set a conservative `watchdog_min_active`
  (e.g. one worker group = 9), field-test the restart trigger, then document a
  recommended value. Currently `0` (disabled).

### 3. Clean TURN teardown  *(largely resolved)*
- The module now owns stop timing: `stop_profile` sends SIGTERM first and only
  escalates after a grace period, instead of the 300 ms group-kill `myprogram`
  used. Tune the grace if TURN allocations are still seen leaking.

### 4. Captcha token-feed field test
- The `CAPTCHA_RESULT|<token>` path (config `captcha_token_file`) is built but
  untested live — captcha never fired in captured sessions. Exercise it once
  captcha actually triggers; document the operator workflow (write token → file).

### 5. Energy-saver / Doze exemption
- Ensure Android/ZDT-D energy-saver policy doesn't freeze or kill the qwdtt-cli
  process (the transport supports `PAUSE`/`RESUME` on stdin — wire or exempt).

### 6. proxyInfo port protection
- Protect the local WG endpoint `127.0.0.1:9000` from other app UIDs probing local
  listeners (proxyInfo can block/observe). Stealth defense-in-depth.

### 7. vk_profile.json persistence
- Confirm on-device that the browser identity stays stable across restarts (the
  `seed_profile`/`seed_captcha_fp` path exists but is untested live). A
  fingerprint that changes every launch is itself a signal.

### 8. Throughput / MTU  *(effectively done)*
- Confirmed fine at 1280 by speed tests; `mtu` is now tunable. No action unless a
  future SIM/relay path fragments.

### 9. Secret rotation *(pre-production)*
- Rotate the WG keypair and tunnel password before production (they left the
  device in `reference/`). Ensure on-device `qwdtt.conf` perms are `600`.

---

## M5 — remaining app-facing integration

The daemon module is in (steps 1, 2, 4, 5 of the `docs/PROGRAMS.md` guide). What is
left is the app-facing surface, so profiles can be managed from the UI rather than
by editing `setting.json` on disk:

1. **API endpoints** in `api.rs` — profile CRUD, enable/disable, setting
   read/write, app-list handling, status. Mirror the `amneziawg` endpoints; the
   module already exposes the functions they need (`read_setting`, `write_setting`,
   `normalize_setting_value`, `write_active`,
   `validate_enabled_tun_uniqueness_with_override`, `collect_defined_ports_for_conflict_check`).
2. **`capabilities.rs`** — advertise the program.
3. **Android API models** — data classes for the app↔daemon API.
4. **Compose UI** — a qWDTT profile screen.
5. **`strings.xml`** — EN and RU.
6. **Docs** — a `PROGRAMS.md` entry.

The module keeps invoking the `qwdtt-cli` binary rather than absorbing supervision
into Rust: it reuses proven code, and the module already owns lifecycle and stop
timing at the process-group level.

---

## Finalization

- Upstream: the qwdtt module PR must stay self-contained. The `myvpn` CIDR-wait
  improvement is preserved on branch `claude/myvpn-cidr-wait` for an independent
  PR with its own justification (as the upstream maintainer requested).
- Keep `reference/` and any filled-in `qwdtt.conf` out of git (already gitignored).
