//! qWDTT — WireGuard over VK TURN.
//!
//! A first-class program module: one qWDTT profile owns the whole stack, so the
//! launch sequence is not split across generic profiles and there is no startup
//! race between them.
//!
//! Layout per profile (under `working_folder/qwdtt/profile/<name>`):
//!
//! ```text
//! setting.json            engine + transport settings (this module's schema)
//! qwdtt.conf              generated from setting.json for the supervisor
//! app/uid/user_program    package list selected in the app
//! app/out/user_program    resolved package=uid pairs (vpn_netd fills this)
//! state/                  supervisor state: wg-turn.conf, vk_profile.json, ...
//! log/qwdtt.log           supervisor log
//! ```
//!
//! Runtime shape:
//!
//! ```text
//! qwdtt-cli (root) ── qwdtt-transport ──▶ VK TURN relays ──▶ VPS
//!                  └─ amneziawg-go ──▶ TUN ──▶ vpn_netd UID binding
//! ```
//!
//! The supervisor binary owns transport supervision, VK hash validation and the
//! WireGuard bring-up; this module owns profile state, validation, conflict
//! checks, process lifecycle and the handover to `vpn_netd`.

use anyhow::{bail, Context, Result};
use super::common::*;
use log::{info, warn};
use serde::{Deserialize, Deserializer, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

use crate::{
    shell::{self, Capture},
    vpn_netd::VpnNetdProfile,
};

const QWDTT_CLI_BIN: &str = "/data/adb/modules/ZDT-D/bin/qwdtt-cli";
const QWDTT_TRANSPORT_BIN: &str = "/data/adb/modules/ZDT-D/bin/qwdtt-transport";
const AWG_GO_BIN: &str = "/data/adb/modules/ZDT-D/bin/amneziawg-go";
const AWG_BIN: &str = "/data/adb/modules/ZDT-D/bin/awg";
const QWDTT_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/qwdtt";
const QWDTT_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/qwdtt/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/qwdtt/active.json";
/// amneziawg-go and awg agree on the UAPI socket only under this directory.
const AMNEZIAWG_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/amneziawg";

const NETID_BASE: u32 = 25000;
const NETID_MAX: u32 = 25199;
/// The supervisor validates every VK hash before the tunnel appears, which costs
/// several seconds per hash, so the TUN wait is generous.
const TUN_WAIT: Duration = Duration::from_secs(120);
const IP_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ProfileState {
    #[serde(default)]
    pub enabled: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ActiveProfiles {
    #[serde(default)]
    pub profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    /// TUN interface created by the supervisor.
    pub tun: String,
    /// Tunnel DNS handed to netd for the bound UIDs.
    #[serde(default = "default_dns", deserialize_with = "deserialize_dns")]
    pub dns: Vec<String>,
    #[serde(default = "default_mtu")]
    pub mtu: u32,
    /// Tunnel CIDR. Empty means learn it from the interface once it is up.
    #[serde(default)]
    pub cidr: String,

    /// VPS DTLS endpoint as host:port. Never contacted directly by the handset —
    /// the transport reaches it only through VK TURN relays.
    #[serde(default)]
    pub peer: String,
    /// Local UDP port the WireGuard client dials (loopback only).
    #[serde(default = "default_listen_port")]
    pub listen_port: u16,
    /// VK call join hashes. Dead ones are dropped by the supervisor at startup.
    #[serde(default)]
    pub vk_hashes: Vec<String>,
    /// Plaintext tunnel password; the transport derives its WRAP key from it.
    #[serde(default)]
    pub password: String,
    #[serde(default = "default_workers")]
    pub workers: u32,
    /// RTP masking mode: audio | video.
    #[serde(default = "default_obfs")]
    pub obfs: String,
    /// anonymous | account.
    #[serde(default = "default_vk_auth")]
    pub vk_auth: String,
    /// vkcalls | legacy.
    #[serde(default = "default_vk_anon_path")]
    pub vk_anon_path: String,
    /// Resolver used to reach VK (not tunnel DNS).
    #[serde(default = "default_go_dns")]
    pub go_dns: String,
    /// auto | rjs. WebView is not available headless.
    #[serde(default = "default_captcha_mode")]
    pub captcha_mode: String,
    /// Stable device identity used for VK auth.
    #[serde(default)]
    pub device_id: String,
    /// Fixed UTC offset for supervisor log timestamps, e.g. "UTC+3".
    #[serde(default)]
    pub timezone: String,
}

fn default_dns() -> Vec<String> { vec!["8.8.8.8".to_string()] }
fn default_mtu() -> u32 { 1280 }
fn default_listen_port() -> u16 { 9000 }
fn default_workers() -> u32 { 45 }
fn default_obfs() -> String { "video".to_string() }
fn default_vk_auth() -> String { "anonymous".to_string() }
fn default_vk_anon_path() -> String { "vkcalls".to_string() }
fn default_go_dns() -> String { "yandex".to_string() }
fn default_captcha_mode() -> String { "auto".to_string() }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "zdtdqw0".to_string(),
            dns: default_dns(),
            mtu: default_mtu(),
            cidr: String::new(),
            peer: String::new(),
            listen_port: default_listen_port(),
            vk_hashes: Vec::new(),
            password: String::new(),
            workers: default_workers(),
            obfs: default_obfs(),
            vk_auth: default_vk_auth(),
            vk_anon_path: default_vk_anon_path(),
            go_dns: default_go_dns(),
            captcha_mode: default_captcha_mode(),
            device_id: String::new(),
            timezone: String::new(),
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    profile_dir: PathBuf,
    conf_path: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    log_path: PathBuf,
    netid: u32,
}

#[derive(Debug, Clone)]
struct TunInfo {
    cidr: String,
    gateway: Option<String>,
}

fn deserialize_dns<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = serde_json::Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        serde_json::Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    out.extend(split_dns_text(s));
                }
            }
        }
        serde_json::Value::String(s) => out.extend(split_dns_text(&s)),
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("dns must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}

fn split_dns_text(s: &str) -> Vec<String> {
    s.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .map(str::trim)
        .filter(|x| !x.is_empty() && is_ipv4(x))
        .map(ToOwned::to_owned)
        .collect()
}

pub fn root_path() -> PathBuf { PathBuf::from(QWDTT_ROOT) }
pub fn active_path() -> PathBuf { PathBuf::from(ACTIVE_JSON) }
pub fn profiles_root() -> PathBuf { PathBuf::from(QWDTT_PROFILE_ROOT) }
pub fn profile_root(profile: &str) -> PathBuf { profiles_root().join(profile) }

pub fn is_valid_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 10
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("qwdtt profile name must be 1..10 chars and contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let root = profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("state"))?;
    ensure_file_empty(&root.join("app/uid/user_program"))?;
    ensure_file_empty(&root.join("app/out/user_program"))?;
    let setting_path = root.join("setting.json");
    if !setting_path.exists() {
        write_json_pretty(&setting_path, &ProfileSetting::default())?;
    }
    Ok(())
}

pub fn ensure_root_layout() -> Result<()> {
    fs::create_dir_all(QWDTT_PROFILE_ROOT)?;
    let active_path = active_path();
    if !active_path.exists() {
        write_json_pretty(&active_path, &ActiveProfiles::default())?;
    }
    Ok(())
}

pub fn read_active() -> Result<ActiveProfiles> {
    ensure_root_layout()?;
    read_json(&active_path())
}

pub fn write_active(active: &ActiveProfiles) -> Result<()> {
    ensure_root_layout()?;
    write_json_pretty(&active_path(), active)
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> {
    ensure_valid_profile_name(profile)?;
    read_json(&profile_root(profile).join("setting.json"))
}

pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    validate_setting(setting)?;
    ensure_profile_layout(profile)?;
    write_json_pretty(&profile_root(profile).join("setting.json"), setting)
}

pub fn normalize_setting_value(value: serde_json::Value) -> Result<ProfileSetting> {
    let mut setting: ProfileSetting =
        serde_json::from_value(value).context("bad qwdtt setting.json")?;
    setting.tun = setting.tun.trim().to_string();
    setting.peer = setting.peer.trim().to_string();
    setting.obfs = setting.obfs.trim().to_ascii_lowercase();
    setting.vk_auth = setting.vk_auth.trim().to_ascii_lowercase();
    setting.vk_anon_path = setting.vk_anon_path.trim().to_ascii_lowercase();
    setting.captcha_mode = setting.captcha_mode.trim().to_ascii_lowercase();
    setting.cidr = setting.cidr.trim().to_string();
    setting.vk_hashes = setting
        .vk_hashes
        .into_iter()
        .map(|h| h.trim().to_string())
        .filter(|h| !h.is_empty())
        .collect();
    setting.dns.sort();
    setting.dns.dedup();
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    if setting.dns.is_empty() || setting.dns.len() > 8 || !setting.dns.iter().all(|d| is_ipv4(d)) {
        bail!("dns must contain 1..8 IPv4 addresses");
    }
    if setting.mtu < 576 || setting.mtu > 9000 {
        bail!("mtu must be in range 576..9000");
    }
    if !setting.cidr.is_empty() && !is_cidr(&setting.cidr) {
        bail!("cidr must be an IPv4 CIDR when set");
    }
    if setting.peer.trim().is_empty() {
        bail!("peer is required (VPS host:port)");
    }
    validate_host_port(&setting.peer).context("peer")?;
    if setting.listen_port == 0 {
        bail!("listen_port must be 1..65535");
    }
    if setting.vk_hashes.is_empty() {
        bail!("at least one VK hash is required");
    }
    if setting.password.trim().is_empty() {
        bail!("password is required (the transport derives its WRAP key from it)");
    }
    if setting.workers == 0 || setting.workers > 108 {
        bail!("workers must be in range 1..108");
    }
    match setting.obfs.as_str() {
        "audio" | "video" => {}
        other => bail!("obfs must be audio or video, got {other}"),
    }
    match setting.vk_auth.as_str() {
        "anonymous" | "account" => {}
        other => bail!("vk_auth must be anonymous or account, got {other}"),
    }
    match setting.vk_anon_path.as_str() {
        "vkcalls" | "legacy" => {}
        other => bail!("vk_anon_path must be vkcalls or legacy, got {other}"),
    }
    match setting.captcha_mode.as_str() {
        "auto" | "rjs" => {}
        // WebView cannot run headless; the supervisor rejects it too.
        other => bail!("captcha_mode must be auto or rjs, got {other}"),
    }
    Ok(())
}

fn validate_host_port(s: &str) -> Result<()> {
    let Some((host, port)) = s.rsplit_once(':') else {
        bail!("must be host:port");
    };
    if host.trim().is_empty() {
        bail!("host is empty");
    }
    let port: u32 = port.trim().parse().context("port must be a number")?;
    if port == 0 || port > 65535 {
        bail!("port must be 1..65535");
    }
    Ok(())
}

pub fn has_enabled_profiles() -> bool {
    read_active()
        .map(|a| a.profiles.values().any(|st| st.enabled))
        .unwrap_or(false)
}

pub fn has_profiles_requiring_netd() -> bool {
    let Ok(active) = read_active() else { return false; };
    active.profiles.iter().any(|(name, st)| {
        if !st.enabled { return false; }
        !enabled_app_list_empty(&profile_root(name).join("app/uid/user_program"))
    })
}

pub fn is_running() -> bool { !main_pids_exact().is_empty() }

/// PIDs of supervisors started from this module's profiles.
pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = format!(
        r#"sh -c "pgrep -f '^{} -config {}/' 2>/dev/null || true""#,
        QWDTT_CLI_BIN, QWDTT_PROFILE_ROOT
    );
    if let Ok(out) = shell::capture_quiet(&cmd) {
        pids.extend(parse_pid_lines(&out));
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

pub fn enabled_tun_claims() -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        if let Ok(setting) = read_setting(&name) {
            out.push((format!("qwdtt/{name}"), setting.tun));
        }
    }
    out
}

pub fn enabled_cidr_claims() -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(&name) else { continue; };
        if setting.cidr.trim().is_empty() { continue; }
        if let Ok(cidr) = normalize_cidr_network(setting.cidr.trim()) {
            out.push((format!("qwdtt/{name}"), cidr));
        }
    }
    out
}

pub fn validate_enabled_tun_uniqueness_with_override(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
    override_enabled: Option<bool>,
) -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let mut seen: BTreeMap<String, String> = BTreeMap::new();

    for (name, st) in active.profiles {
        let enabled = if override_profile == Some(name.as_str()) {
            override_enabled.unwrap_or(st.enabled)
        } else {
            st.enabled
        };
        if !enabled { continue; }

        let setting = if override_profile == Some(name.as_str()) {
            override_setting.cloned().unwrap_or_else(|| read_setting(&name).unwrap_or_default())
        } else {
            read_setting(&name).unwrap_or_default()
        };
        validate_setting(&setting).with_context(|| format!("qwdtt profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("qwdtt tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
        }
    }
    Ok(())
}

/// Local loopback ports this program occupies, for the shared conflict check.
pub fn collect_defined_ports_for_conflict_check() -> BTreeSet<u16> {
    let mut used = BTreeSet::new();
    let Ok(active) = read_active() else { return used; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        if let Ok(setting) = read_setting(&name) {
            if setting.listen_port > 0 {
                used.insert(setting.listen_port);
            }
        }
    }
    used
}

pub fn validate_start_plan() -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();
    if enabled_names.is_empty() { return Ok(()); }

    let mut errors = Vec::<String>::new();
    for bin in [QWDTT_CLI_BIN, QWDTT_TRANSPORT_BIN, AWG_GO_BIN, AWG_BIN] {
        if !Path::new(bin).is_file() {
            errors.push(format!("binary missing: {bin}"));
        }
    }

    let mut seen_tuns = BTreeMap::<String, String>::new();
    let mut seen_ports = BTreeMap::<u16, String>::new();
    for name in enabled_names {
        let profile_res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let setting = read_setting(&name).with_context(|| format!("read setting for profile {name}"))?;
            validate_setting(&setting).with_context(|| format!("validate setting for profile {name}"))?;
            if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
            }
            if let Some(other) = seen_ports.insert(setting.listen_port, name.clone()) {
                bail!("listen_port {} is used by enabled profiles {} and {}", setting.listen_port, other, name);
            }
            let app_in = profile_root(&name).join("app/uid/user_program");
            if enabled_app_list_empty(&app_in) {
                bail!("app list is empty: {}", app_in.display());
            }
            Ok(())
        })();
        if let Err(e) = profile_res {
            errors.push(format!("{name}: {e:#}"));
        }
    }

    if errors.is_empty() { Ok(()) } else { bail!("qwdtt start plan has issue(s): {}", errors.join("; ")) }
}

pub fn start_if_enabled() -> Result<()> {
    let profiles = start_profiles_for_netd()?;
    crate::vpn_netd::start_profiles(profiles)?;
    Ok(())
}

/// Start every enabled profile and return the descriptors `vpn_netd` binds.
pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;

    let active = read_active().unwrap_or_default();
    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        info!("qwdtt: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("qWDTT: запуск");

    for bin in [QWDTT_CLI_BIN, QWDTT_TRANSPORT_BIN, AWG_GO_BIN, AWG_BIN] {
        if !Path::new(bin).is_file() {
            warn!("qwdtt: binary not found: {bin} -> skip");
            crate::logging::user_warn("qWDTT: ошибка запуска, запуск продолжен");
            return Ok(Vec::new());
        }
    }

    let all_profiles: Vec<String> = active.profiles.keys().cloned().collect();
    let mut plans = Vec::new();
    let mut had_error = false;
    for name in enabled_names {
        match build_profile_plan(&name, &all_profiles) {
            Ok(plan) => plans.push(plan),
            Err(e) => {
                had_error = true;
                warn!("qwdtt: profile '{name}' skip: {e:#}");
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn("qWDTT: ошибка запуска, запуск продолжен");
        }
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    let mut used_cidrs = Vec::<(String, String)>::new();
    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            info!(
                "qwdtt: starting profile={} tun={} listen=127.0.0.1:{} workers={} hashes={}",
                plan.name,
                plan.setting.tun,
                plan.setting.listen_port,
                plan.setting.workers,
                plan.setting.vk_hashes.len()
            );
            write_supervisor_config(plan)?;
            spawn_supervisor(plan)?;
            // The supervisor validates VK hashes before the tunnel exists, so the
            // interface can take a while; poll rather than inspect once.
            let tun = wait_tun_ready(&plan.setting.tun, TUN_WAIT).with_context(|| {
                format!("qwdtt profile={} wait tun={}", plan.name, plan.setting.tun)
            })?;
            let cidr = if plan.setting.cidr.trim().is_empty() {
                tun.cidr.clone()
            } else {
                normalize_cidr_network(plan.setting.cidr.trim())?
            };
            for (other_name, other_cidr) in &used_cidrs {
                if cidrs_overlap(&cidr, other_cidr).unwrap_or(false) {
                    bail!(
                        "qwdtt profile={} cidr {} overlaps with profile={} cidr {}",
                        plan.name, cidr, other_name, other_cidr
                    );
                }
            }
            info!(
                "qwdtt: tun ready profile={} tun={} cidr={} gateway={}",
                plan.name,
                plan.setting.tun,
                cidr,
                tun.gateway.as_deref().unwrap_or("none")
            );
            Ok(VpnNetdProfile {
                owner_program: "qwdtt".to_string(),
                profile: plan.name.clone(),
                netid: plan.netid,
                tun: plan.setting.tun.clone(),
                cidr,
                gateway: tun.gateway,
                dns: plan.setting.dns.clone(),
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
                // The WireGuard endpoint is loopback, so no traffic has to escape
                // the tunnel to reach it.
                endpoint_escape_ips: Vec::new(),
            })
        })();

        match res {
            Ok(profile) => {
                used_cidrs.push((profile.profile.clone(), profile.cidr.clone()));
                profiles.push(profile);
            }
            Err(e) => {
                had_error = true;
                warn!("qwdtt: profile '{}' failed, startup continues: {e:#}", plan.name);
                stop_profile(&plan.name, &plan.setting.tun);
            }
        }
    }

    if had_error {
        crate::logging::user_warn("qWDTT: часть профилей не запущена, запуск продолжен");
    }
    info!("qwdtt: prepared vpn_netd profiles count={}", profiles.len());
    Ok(profiles)
}

fn build_profile_plan(profile: &str, all_profiles: &[String]) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&app_in)?;
    ensure_file_empty(&app_out)?;
    if enabled_app_list_empty(&app_in) {
        bail!("app list is empty: {}", app_in.display());
    }

    let netid = stable_netid(NETID_BASE, NETID_MAX, all_profiles, profile)?;

    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        conf_path: profile_dir.join("qwdtt.conf"),
        app_in,
        app_out,
        log_path: profile_dir.join("log/qwdtt.log"),
        profile_dir,
        netid,
    })
}

/// Render `setting.json` into the supervisor's config format.
///
/// Written with owner-only permissions: it carries the tunnel password and the
/// VK hashes.
fn write_supervisor_config(plan: &ProfilePlan) -> Result<()> {
    let s = &plan.setting;
    let state_dir = plan.profile_dir.join("state");
    fs::create_dir_all(&state_dir)?;

    let mut out = String::new();
    out.push_str("# Generated by ZDT-D from setting.json. Edits are overwritten.\n");
    out.push_str(&format!("transport_binary = {QWDTT_TRANSPORT_BIN}\n"));
    out.push_str(&format!("state_dir = {}\n", state_dir.display()));
    out.push_str(&format!("peer = {}\n", s.peer));
    out.push_str(&format!("listen = 127.0.0.1:{}\n", s.listen_port));
    out.push_str(&format!("vk_hashes = {}\n", s.vk_hashes.join(",")));
    out.push_str(&format!("password = {}\n", s.password));
    out.push_str(&format!("workers = {}\n", s.workers));
    out.push_str(&format!("obfs = {}\n", s.obfs));
    out.push_str(&format!("vk_auth = {}\n", s.vk_auth));
    out.push_str(&format!("vk_anon_path = {}\n", s.vk_anon_path));
    out.push_str(&format!("go_dns = {}\n", s.go_dns));
    out.push_str(&format!("captcha_mode = {}\n", s.captcha_mode));
    if !s.device_id.trim().is_empty() {
        out.push_str(&format!("device_id = {}\n", s.device_id.trim()));
    }
    if !s.timezone.trim().is_empty() {
        out.push_str(&format!("timezone = {}\n", s.timezone.trim()));
    }
    // This module owns routing, so the supervisor only creates the interface.
    out.push_str("mode = vpn\n");
    out.push_str("wg_bringup = true\n");
    out.push_str(&format!("tun = {}\n", s.tun));
    out.push_str(&format!("mtu = {}\n", s.mtu));
    out.push_str(&format!("awg_go_binary = {AWG_GO_BIN}\n"));
    out.push_str(&format!("awg_binary = {AWG_BIN}\n"));
    out.push_str(&format!("awg_run_dir = {AMNEZIAWG_ROOT}\n"));

    write_text_atomic(&plan.conf_path, &out)?;
    let _ = Command::new("chmod").arg("600").arg(&plan.conf_path).status();
    Ok(())
}

fn spawn_supervisor(plan: &ProfilePlan) -> Result<()> {
    if supervisor_running(&plan.name) {
        info!("qwdtt: profile={} supervisor already running, skip spawn", plan.name);
        return Ok(());
    }
    // Clear anything a previous lifetime left behind.
    cleanup_interface(&plan.setting.tun);

    ensure_parent_dir(&plan.log_path)?;
    fs::create_dir_all(AMNEZIAWG_ROOT).ok();

    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.log_path)
        .with_context(|| format!("open log {}", plan.log_path.display()))?;
    let logf_err = logf.try_clone().context("clone qwdtt log")?;

    let mut cmd = Command::new(QWDTT_CLI_BIN);
    cmd.arg("-config")
        .arg(&plan.conf_path)
        .current_dir(&plan.profile_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        // Own session: stopping this profile kills the supervisor's whole process
        // group, which reaps the transport and amneziawg-go with it.
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {QWDTT_CLI_BIN}"))?;
    info!(
        "qwdtt: spawned profile={} pid={} log={}",
        plan.name,
        child.id(),
        plan.log_path.display()
    );

    thread::sleep(Duration::from_millis(250));
    if !PathBuf::from("/proc").join(child.id().to_string()).is_dir() {
        warn!(
            "qwdtt: profile={} pid={} exited immediately; check log {}",
            plan.name,
            child.id(),
            plan.log_path.display()
        );
    }
    Ok(())
}

fn supervisor_running(profile: &str) -> bool {
    let conf = profile_root(profile).join("qwdtt.conf");
    let pattern = format!("{} -config {}", QWDTT_CLI_BIN, conf.display());
    let cmd = format!(
        "ps -ef 2>/dev/null | grep -F {} | grep -v grep >/dev/null 2>&1",
        shell_quote_for_sh(&pattern)
    );
    shell::ok_sh(&cmd).is_ok()
}

/// Stop one profile: kill its supervisor process group and drop the interface.
pub fn stop_profile(profile: &str, tun: &str) {
    let conf = profile_root(profile).join("qwdtt.conf");
    let pattern = format!("{} -config {}", QWDTT_CLI_BIN, conf.display());
    // SIGTERM first so the supervisor can release TURN allocations and tear the
    // interface down cleanly, then make sure nothing survives.
    let _ = shell::ok_sh(&format!("pkill -TERM -f {} 2>/dev/null || true", shell_quote_for_sh(&pattern)));
    thread::sleep(Duration::from_millis(400));
    let _ = shell::ok_sh(&format!("pkill -KILL -f {} 2>/dev/null || true", shell_quote_for_sh(&pattern)));
    cleanup_interface(tun);
}

pub fn stop_all() {
    let mut tuns = BTreeSet::<String>::new();
    if let Ok(rd) = fs::read_dir(profiles_root()) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()) else { continue; };
            let tun = read_setting(profile).map(|s| s.tun).unwrap_or_default();
            if !tun.is_empty() {
                tuns.insert(tun.clone());
            }
            stop_profile(profile, &tun);
        }
    }
    for tun in tuns {
        cleanup_interface(&tun);
    }
}

pub fn cleanup_all_interfaces() {
    stop_all();
}

fn cleanup_interface(tun: &str) {
    if tun.is_empty() { return; }
    let _ = shell::run_timeout("ip", &["link", "del", tun], Capture::None, IP_TIMEOUT);
    let pattern = format!("{} -f {}", AWG_GO_BIN, tun);
    let _ = shell::ok_sh(&format!("pkill -f {} 2>/dev/null || true", shell_quote_for_sh(&pattern)));
}

fn wait_tun_ready(tun: &str, timeout: Duration) -> Result<TunInfo> {
    let start = Instant::now();
    loop {
        let last_err = match inspect_tun(tun) {
            Ok(info) => return Ok(info),
            Err(e) => e,
        };
        if start.elapsed() >= timeout {
            bail!("tun {tun} is not ready after {timeout:?}: {last_err:#}");
        }
        thread::sleep(Duration::from_millis(500));
    }
}

fn inspect_tun(tun: &str) -> Result<TunInfo> {
    let (code, out) = shell::run_timeout(
        "ip",
        &["-o", "-4", "addr", "show", "dev", tun],
        Capture::Stdout,
        IP_TIMEOUT,
    )
    .with_context(|| format!("ip -o -4 addr show dev {tun}"))?;
    if code != 0 {
        bail!("ip addr show failed for tun {tun}");
    }

    let mut cidr = None::<String>;
    let mut gateway = None::<String>;
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for i in 0..tokens.len() {
            if tokens[i] == "inet" {
                if let Some(ip_cidr) = tokens.get(i + 1) {
                    cidr = normalize_cidr_network(ip_cidr).ok();
                }
            }
            if tokens[i] == "peer" {
                if let Some(peer) = tokens.get(i + 1) {
                    let peer_ip = peer.split('/').next().unwrap_or(peer).to_string();
                    if is_ipv4(&peer_ip) {
                        gateway = Some(peer_ip);
                    }
                }
            }
        }
    }

    let cidr = cidr.ok_or_else(|| anyhow::anyhow!("tun {tun} has no IPv4 CIDR"))?;
    if gateway.is_none() {
        gateway = first_host_for_cidr(&cidr);
    }
    Ok(TunInfo { cidr, gateway })
}

// ─── small local helpers (kept private so this module stays self-contained) ───

fn is_forbidden_tun_name(s: &str) -> bool {
    s == "lo"
        || s == "dummy0"
        || s.starts_with("wlan")
        || s.starts_with("rmnet")
        || s.starts_with("ccmni")
        || s.starts_with("eth")
        || s.starts_with("ap")
        || s.starts_with("rndis")
}

fn is_ipv4(s: &str) -> bool {
    let parts = s.split('.').collect::<Vec<_>>();
    if parts.len() != 4 { return false; }
    parts.iter().all(|p| !p.is_empty() && p.parse::<u8>().is_ok())
}

fn is_cidr(s: &str) -> bool {
    let Some((ip, prefix)) = s.split_once('/') else { return false; };
    let Ok(prefix) = prefix.parse::<u8>() else { return false; };
    is_ipv4(ip) && prefix <= 32
}

fn normalize_cidr_network(cidr: &str) -> Result<String> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 { bail!("bad cidr prefix {cidr}"); }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok(format!("{}/{}", u32_to_ipv4(addr & mask), prefix))
}

fn cidrs_overlap(a: &str, b: &str) -> Result<bool> {
    let (an, am) = cidr_network_mask(a)?;
    let (bn, bm) = cidr_network_mask(b)?;
    let a_end = an | !am;
    let b_end = bn | !bm;
    Ok(an <= b_end && bn <= a_end)
}

fn ensure_file_empty(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    if !path.exists() { fs::write(path, "")?; }
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let txt = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    serde_json::from_str(&txt).with_context(|| format!("parse {}", path.display()))
}

fn write_json_pretty<T: Serialize>(path: &Path, v: &T) -> Result<()> {
    let txt = serde_json::to_string_pretty(v)?;
    write_text_atomic(path, &txt)
}

fn write_text_atomic(path: &Path, content: &str) -> Result<()> {
    ensure_parent_dir(path)?;
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content.as_bytes()).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}
