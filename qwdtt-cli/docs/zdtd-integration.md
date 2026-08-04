# Running qWDTT as a ZDT-D program

qWDTT is a **first-class program module** (`rust/zdtd/src/programs/qwdtt.rs`), not a
pair of generic profiles. One qWDTT profile owns the whole stack, so there is no
launch sequence split across `myprogram` and `myvpn` and no race between them.

> Earlier revisions of this project wired qWDTT through `myprogram` + `myvpn`.
> That approach is gone: those profiles exist for arbitrary user programs and for
> binding apps to an interface someone else created, which is not what qWDTT is.

## Architecture

```
qwdtt program module (daemon)
  └─ qwdtt-cli (root, one supervisor per profile)
       ├─ qwdtt-transport ──▶ VK TURN relays ──▶ wdtt-server on VPS
       │                      (root uid — stays direct, never touches the VPS IP)
       ├─ listens on 127.0.0.1:<listen_port>
       └─ amneziawg-go ──▶ creates the TUN
  └─ hands the interface to vpn_netd, which binds the selected app UIDs
```

The module owns profile state, validation, conflict checks, process lifecycle and
the handover to `vpn_netd`. The supervisor binary owns VK hash validation,
transport supervision and the WireGuard bring-up.

## Files

Everything lives under one profile:

```text
working_folder/qwdtt/active.json                        {"profiles":{"<name>":{"enabled":true}}}
working_folder/qwdtt/profile/<name>/setting.json        engine + transport settings
working_folder/qwdtt/profile/<name>/qwdtt.conf          generated from setting.json (chmod 600)
working_folder/qwdtt/profile/<name>/app/uid/user_program   selected packages
working_folder/qwdtt/profile/<name>/app/out/user_program   resolved package=uid
working_folder/qwdtt/profile/<name>/state/              wg-turn.conf, vk_profile.json, ...
working_folder/qwdtt/profile/<name>/log/qwdtt.log       supervisor log
```

`qwdtt.conf` is **generated** — edit `setting.json`, not the conf.

## setting.json

| Key | Default | Notes |
|---|---|---|
| `tun` | `zdtdqw0` | interface the supervisor creates |
| `dns` | `["8.8.8.8"]` | tunnel DNS handed to netd |
| `mtu` | `1280` | verified good under load; 576..9000 |
| `cidr` | *(empty)* | empty = learn from the interface; set to pin it |
| `peer` | — | VPS `host:port` (DTLS). **Required** |
| `listen_port` | `9000` | local UDP port for WireGuard (loopback) |
| `vk_hashes` | — | VK call join hashes. **Required**, dead ones dropped at startup |
| `password` | — | plaintext tunnel password. **Required** |
| `workers` | `45` | floored to a multiple of 9, capped at 108 |
| `obfs` | `video` | `audio` or `video` |
| `vk_auth` / `vk_anon_path` | `anonymous` / `vkcalls` | |
| `go_dns` | `yandex` | resolver used to *reach* VK, not tunnel DNS |
| `captcha_mode` | `auto` | `auto` or `rjs`; WebView cannot run headless |
| `device_id`, `timezone` | *(empty)* | stable device identity; log clock offset |

## Binaries

Shipped in the module and built by CI (`scripts/build-qwdtt-bins.sh`):

```text
/data/adb/modules/ZDT-D/bin/qwdtt-cli          supervisor
/data/adb/modules/ZDT-D/bin/qwdtt-transport    patched upstream transport
/data/adb/modules/ZDT-D/bin/amneziawg-go       userspace WireGuard (already shipped)
/data/adb/modules/ZDT-D/bin/awg                WireGuard control tool (already shipped)
```

`validate_start_plan` refuses to start if any of them is missing.

## Lifecycle

- **start** — `start_profiles_for_netd()` renders `qwdtt.conf`, spawns `qwdtt-cli`
  in its own session, polls until the TUN carries an IPv4 address (the supervisor
  validates VK hashes first, so this can take a while — the wait is 120 s), then
  returns a `VpnNetdProfile` for the UID binding.
- **stop** — `stop_all()` sends SIGTERM to each supervisor first so it can release
  its TURN allocations and drop the interface cleanly, then escalates and removes
  any interface left behind.
- **validation** — profile name, tun name, DNS, MTU, CIDR, peer `host:port`,
  listen port, hashes, password, workers, obfs, auth/captcha modes, app list.
- **conflicts** — tun-name and CIDR uniqueness across all VPN engines, the local
  `listen_port` contributed to the shared port check, and app-list conflicts via
  the `exclusive_network` domain.
- **status** — `is_running()` / `main_pids_exact()` match supervisors started from
  this module's profiles only.

## API

Profiles are managed over the daemon API, mirroring the other VPN engines:

```text
GET    /api/programs/qwdtt/profiles                      list profiles
POST   /api/programs/qwdtt/profiles                      create ({"name": "..."} or auto)
DELETE /api/programs/qwdtt/profiles/<p>                  delete (dir moved to .deleted)
PUT    /api/programs/qwdtt/profiles/<p>/enabled          {"enabled": true|false}
GET    /api/programs/qwdtt/profiles/<p>/setting          read setting.json
PUT    /api/programs/qwdtt/profiles/<p>/setting          write + validate setting.json
GET    /api/programs/qwdtt/profiles/<p>/apps/user        read the package list
PUT    /api/programs/qwdtt/profiles/<p>/apps/user        write the package list
GET    /api/programs/qwdtt/profiles/<p>/status           enabled / running / startable / tun
```

Enabling a profile validates it first, so a half-configured profile cannot be
switched on. Writing settings or toggling `enabled` re-checks tun uniqueness
within qwdtt and against every other VPN engine. The app list participates in the
shared conflict model as an `exclusive_network` program: a package routed through
qWDTT cannot also be routed by another VPN/tunnel program. Deleting a profile
moves its directory aside rather than unlinking it, because it holds the tunnel
password and VK hashes.

## Stealth

The TUN is created by root, not `VpnService`, so it carries no Android VPN UI. The
Zygisk layer hides it from the selected UIDs: `applied.json` records
`owner_program = "qwdtt"`, which the native layer treats as a VPN owner.

## Invariants

- **Never route root/system UIDs.** Root in the bound range makes the transport
  tunnel itself and the stack deadlocks.
- **No direct handset→VPS path.** The transport only ever talks to VK TURN relays.
- The 34 `excluded_apps` (banking/gov/Yandex) must stay direct — do not add them
  to the app list.
- UID resolution shifts when apps are installed, removed, cloned, or moved between
  Android users; re-validate the binding after such changes.

## Troubleshooting

- **No interface:** read `log/qwdtt.log` — all VK hashes dead, or a failed
  `awg setconf`/`ip` shows there; the supervisor restarts with backoff.
- **Interface up, app has no egress:** check `vpn_netd/applied.json` for the UID
  binding, and confirm `app/out/user_program` lists `package=uid`. Test from the
  app's real UID — a root shell is deliberately not bound, so its traffic goes
  direct and a whitelist ISP drops it (the request just hangs).
- **Immediate restarts:** the transport exits non-zero (missing password,
  unreachable VK). Fix `setting.json`; `qwdtt.conf` is regenerated on each start.
