package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

private data class QwdttProfileInfo(
  val name: String,
  val enabled: Boolean,
)

/** Mirrors `ProfileSetting` in rust/zdtd/src/programs/qwdtt.rs. */
private data class QwdttSettingUi(
  val tun: String = "zdtdqw0",
  val dns: List<String> = listOf("8.8.8.8"),
  val mtu: Int = 1280,
  val cidr: String = "",
  val peer: String = "",
  val listenPort: Int = 9000,
  val vkHashes: List<String> = emptyList(),
  val password: String = "",
  val workers: Int = 45,
  val obfs: String = "video",
  val vkAuth: String = "anonymous",
  val vkAnonPath: String = "vkcalls",
  val goDns: String = "yandex",
  val captchaMode: String = "auto",
  val deviceId: String = "",
  val timezone: String = "",
)

private val qwdttProfileNameRegex = Regex("^[A-Za-z0-9_-]{1,10}$")
private val qwdttTunRegex = Regex("^[A-Za-z0-9_.-]{1,15}$")
private val qwdttForbiddenTunNames = setOf("wlan0", "rmnet_data0", "eth0", "lo", "dummy0")
private const val QWDTT_AUTOSAVE_DELAY_MS = 1500L

private suspend fun awaitLoadJsonQwdtt(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextQwdtt(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonQwdtt(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private fun qwdttProfilePath(profile: String): String =
  "/api/programs/qwdtt/profiles/${URLEncoder.encode(profile, "UTF-8")}"

private fun qwdttDataObject(obj: JSONObject?): JSONObject? =
  obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj

private fun readQwdttStringArray(obj: JSONObject?, key: String): List<String> {
  val arr = obj?.optJSONArray(key) ?: return emptyList()
  return buildList {
    for (i in 0 until arr.length()) {
      val value = arr.optString(i, "").trim()
      if (value.isNotEmpty()) add(value)
    }
  }
}

private fun parseQwdttSetting(obj: JSONObject?): QwdttSettingUi {
  val data = qwdttDataObject(obj)
  val defaults = QwdttSettingUi()
  return QwdttSettingUi(
    tun = data?.optString("tun", defaults.tun)?.trim().orEmpty().ifBlank { defaults.tun },
    dns = readQwdttStringArray(data, "dns").takeIf { it.isNotEmpty() } ?: defaults.dns,
    mtu = data?.optInt("mtu", defaults.mtu)?.takeIf { it in 576..9000 } ?: defaults.mtu,
    cidr = data?.optString("cidr", "")?.trim().orEmpty(),
    peer = data?.optString("peer", "")?.trim().orEmpty(),
    listenPort = data?.optInt("listen_port", defaults.listenPort)?.takeIf { it in 1..65535 } ?: defaults.listenPort,
    vkHashes = readQwdttStringArray(data, "vk_hashes"),
    password = data?.optString("password", "").orEmpty(),
    workers = data?.optInt("workers", defaults.workers)?.takeIf { it in 1..108 } ?: defaults.workers,
    obfs = data?.optString("obfs", defaults.obfs)?.trim()?.lowercase(Locale.ROOT)
      ?.takeIf { it == "audio" || it == "video" } ?: defaults.obfs,
    vkAuth = data?.optString("vk_auth", defaults.vkAuth)?.trim()?.lowercase(Locale.ROOT)
      ?.takeIf { it == "anonymous" || it == "account" } ?: defaults.vkAuth,
    vkAnonPath = data?.optString("vk_anon_path", defaults.vkAnonPath)?.trim()?.lowercase(Locale.ROOT)
      ?.takeIf { it == "vkcalls" || it == "legacy" } ?: defaults.vkAnonPath,
    goDns = data?.optString("go_dns", defaults.goDns)?.trim().orEmpty().ifBlank { defaults.goDns },
    captchaMode = data?.optString("captcha_mode", defaults.captchaMode)?.trim()?.lowercase(Locale.ROOT)
      ?.takeIf { it == "auto" || it == "rjs" } ?: defaults.captchaMode,
    deviceId = data?.optString("device_id", "")?.trim().orEmpty(),
    timezone = data?.optString("timezone", "")?.trim().orEmpty(),
  )
}

private fun buildQwdttSettingJson(setting: QwdttSettingUi): JSONObject {
  val dns = JSONArray()
  setting.dns.forEach { dns.put(it) }
  val hashes = JSONArray()
  setting.vkHashes.forEach { hashes.put(it) }
  return JSONObject()
    .put("tun", setting.tun.trim())
    .put("dns", dns)
    .put("mtu", setting.mtu)
    .put("cidr", setting.cidr.trim())
    .put("peer", setting.peer.trim())
    .put("listen_port", setting.listenPort)
    .put("vk_hashes", hashes)
    .put("password", setting.password)
    .put("workers", setting.workers)
    .put("obfs", setting.obfs)
    .put("vk_auth", setting.vkAuth)
    .put("vk_anon_path", setting.vkAnonPath)
    .put("go_dns", setting.goDns.trim())
    .put("captcha_mode", setting.captchaMode)
    .put("device_id", setting.deviceId.trim())
    .put("timezone", setting.timezone.trim())
}

private fun splitQwdttList(raw: String): List<String> = raw
  .split(Regex("[\\s,;]+"))
  .map { it.trim() }
  .filter { it.isNotEmpty() }

private fun parseQwdttDnsInput(raw: String): List<String>? {
  val parts = splitQwdttList(raw)
  if (parts.isEmpty() || parts.size > 8) return null
  if (parts.distinct().size != parts.size) return null
  return parts.takeIf { it.all(::isValidQwdttIpv4Literal) }
}

private fun parseQwdttHashesInput(raw: String): List<String>? {
  val parts = splitQwdttList(raw)
  if (parts.isEmpty()) return null
  if (parts.distinct().size != parts.size) return null
  // Hashes are opaque VK join tokens; only reject obviously wrong shapes.
  return parts.takeIf { list -> list.all { it.length in 4..128 && !it.contains('/') } }
}

private fun isValidQwdttIpv4Literal(value: String): Boolean {
  if (value.contains(":") || value.contains("/") || value.contains("://")) return false
  val parts = value.split('.')
  if (parts.size != 4) return false
  return parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
  }
}

private fun isValidQwdttTun(value: String): Boolean {
  val v = value.trim()
  return qwdttTunRegex.matches(v) && v.lowercase(Locale.ROOT) !in qwdttForbiddenTunNames
}

private fun isValidQwdttCidr(value: String): Boolean {
  val v = value.trim()
  if (v.isBlank() || v.contains(":") || v.contains("://")) return false
  val parts = v.split('/')
  if (parts.size != 2) return false
  val prefix = parts[1].trim().toIntOrNull() ?: return false
  return isValidQwdttIpv4Literal(parts[0].trim()) && prefix in 0..32
}

/** The VPS endpoint as host:port. The handset reaches it only through VK TURN. */
private fun isValidQwdttPeer(value: String): Boolean {
  val v = value.trim()
  if (v.isBlank() || v.contains("://") || v.contains(" ")) return false
  val idx = v.lastIndexOf(':')
  if (idx <= 0 || idx == v.length - 1) return false
  val host = v.substring(0, idx)
  val port = v.substring(idx + 1).toIntOrNull() ?: return false
  if (port !in 1..65535) return false
  return host.isNotBlank() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
}

/**
 * The device identity the VPS binds the tunnel password to: 16 hex digits, the
 * shape of an Android SSAID. Required — a blank value used to be sent as
 * "unknown", which the VPS rejects with FATAL_AUTH on every worker, leaving the
 * profile to hang until the supervisor's startup deadline with no obvious cause.
 */
private fun isValidQwdttDeviceId(value: String): Boolean {
  val v = value.trim()
  return v.length == 16 && v.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

private fun qwdttProfileIndex(name: String): Int {
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

@Composable
fun QwdttProgramScreen(
  programs: List<ApiModels.Program>,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "qwdtt" }
  val effectiveTopContentPadding = topContentPadding + 6.dp
  var showCreate by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val profiles = remember(program?.profiles) {
    program?.profiles.orEmpty()
      .map { QwdttProfileInfo(name = it.name, enabled = it.enabled) }
      .sortedWith(compareByDescending<QwdttProfileInfo> { qwdttProfileIndex(it.name) }.thenBy { it.name.lowercase(Locale.ROOT) })
  }

  if (showCreate) {
    StyledCreateProfileDialog(
      existing = program?.profiles.orEmpty().map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("qwdtt", name) { created ->
          if (created != null) {
            showSnack(context.getString(R.string.qwdtt_profile_created, created))
            actions.refreshPrograms()
            onOpenProfile("qwdtt", created)
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
      titleRes = R.string.qwdtt_create_profile_title,
      rulesRes = R.string.qwdtt_profile_name_rules,
      invalidNameRes = R.string.qwdtt_profile_name_invalid,
      validator = { name -> qwdttProfileNameRegex.matches(name) },
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    ProgramDescriptionHeader(
      programId = "qwdtt",
      description = stringResource(R.string.qwdtt_program_hint),
      isProfiles = true,
    )

    CreateProfileCard(onAdd = { showCreate = true })

    ProfilesSectionTitle()

    if (profiles.isEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.qwdtt_no_profiles_title), fontWeight = FontWeight.SemiBold)
          Text(
            stringResource(R.string.qwdtt_no_profiles_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
      }
    }

    profiles.forEach { info ->
      ProfileStatusCard(
        programId = "qwdtt",
        profileName = info.name,
        checked = info.enabled,
        onOpen = { onOpenProfile("qwdtt", info.name) },
        onCheckedChange = { checked ->
          actions.setProfileEnabled("qwdtt", info.name, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        },
        onDelete = {
          actions.deleteProfile("qwdtt", info.name) { ok ->
            showSnack(if (ok) context.getString(R.string.deleted) else context.getString(R.string.delete_failed))
            if (ok) actions.refreshPrograms()
          }
        },
      )
    }

    Spacer(Modifier.height(bottomContentPadding + 12.dp))
  }
}

@Composable
private fun QwdttProfileEnabledCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          modifier = Modifier.width(42.dp).height(42.dp),
          shape = MaterialTheme.shapes.large,
          color = accent.copy(alpha = 0.15f),
          contentColor = accent,
          border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
        ) {
          Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null)
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            stringResource(R.string.enabled_card_profile_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Text(
            stringResource(R.string.enabled_card_apply_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
          )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
      }
      Surface(
        shape = MaterialTheme.shapes.large,
        color = accent.copy(alpha = 0.14f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
      ) {
        Text(
          stringResource(if (checked) R.string.enabled_state_on else R.string.enabled_state_off),
          modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

/** Two-way choice rendered as a filled/outlined button pair, as elsewhere in the app. */
@Composable
private fun QwdttChoiceRow(
  label: String,
  current: String,
  firstValue: String,
  firstLabel: String,
  secondValue: String,
  secondLabel: String,
  onSelect: (String) -> Unit,
) {
  Text(label, style = MaterialTheme.typography.labelLarge)
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (current == firstValue) {
      Button(onClick = { onSelect(firstValue) }, modifier = Modifier.weight(1f)) { Text(firstLabel) }
      OutlinedButton(onClick = { onSelect(secondValue) }, modifier = Modifier.weight(1f)) { Text(secondLabel) }
    } else {
      OutlinedButton(onClick = { onSelect(firstValue) }, modifier = Modifier.weight(1f)) { Text(firstLabel) }
      Button(onClick = { onSelect(secondValue) }, modifier = Modifier.weight(1f)) { Text(secondLabel) }
    }
  }
}

@Composable
fun QwdttProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val effectiveTopContentPadding = topContentPadding + 6.dp
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "qwdtt" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val basePath = remember(profile) { qwdttProfilePath(profile) }

  var loading by remember(profile) { mutableStateOf(true) }
  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var syncedSetting by remember(profile) { mutableStateOf(QwdttSettingUi()) }
  var appCount by remember(profile) { mutableStateOf(0) }
  var usedVpnTuns by remember(profile) { mutableStateOf(emptySet<String>()) }

  // Transport
  var peerText by remember(profile) { mutableStateOf("") }
  var hashesText by remember(profile) { mutableStateOf("") }
  var passwordText by remember(profile) { mutableStateOf("") }
  var listenPortText by remember(profile) { mutableStateOf("9000") }
  var workersText by remember(profile) { mutableStateOf("45") }
  var obfs by remember(profile) { mutableStateOf("video") }
  var vkAuth by remember(profile) { mutableStateOf("anonymous") }
  var vkAnonPath by remember(profile) { mutableStateOf("vkcalls") }
  var captchaMode by remember(profile) { mutableStateOf("auto") }
  var goDnsText by remember(profile) { mutableStateOf("yandex") }
  var deviceIdText by remember(profile) { mutableStateOf("") }
  var timezoneText by remember(profile) { mutableStateOf("") }
  // Interface
  var tunText by remember(profile) { mutableStateOf("zdtdqw0") }
  var dnsText by remember(profile) { mutableStateOf("8.8.8.8") }
  var mtuText by remember(profile) { mutableStateOf("1280") }
  var cidrText by remember(profile) { mutableStateOf("") }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun reload() {
    loading = true
    settingInitialized = false
    scope.launch {
      val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "qwdtt", excludeProfile = profile)
      val loaded = parseQwdttSetting(awaitLoadJsonQwdtt(actions, "$basePath/setting"))
      val setting = if (isVpnTunNameUsed(loaded.tun, usedTuns)) loaded.copy(tun = nextFreeVpnTunName(usedTuns)) else loaded
      val apps = parsePkgList(awaitLoadTextQwdtt(actions, "$basePath/apps/user").orEmpty()).size

      usedVpnTuns = usedTuns
      syncedSetting = loaded
      peerText = setting.peer
      hashesText = setting.vkHashes.joinToString(" ")
      passwordText = setting.password
      listenPortText = setting.listenPort.toString()
      workersText = setting.workers.toString()
      obfs = setting.obfs
      vkAuth = setting.vkAuth
      vkAnonPath = setting.vkAnonPath
      captchaMode = setting.captchaMode
      goDnsText = setting.goDns
      deviceIdText = setting.deviceId
      timezoneText = setting.timezone
      tunText = setting.tun
      dnsText = setting.dns.joinToString(" ")
      mtuText = setting.mtu.toString()
      cidrText = setting.cidr
      settingInitialized = true

      appCount = apps
      loading = false
    }
  }

  LaunchedEffect(profile) { reload() }

  val tunNameConflict = remember(tunText, usedVpnTuns) { isVpnTunNameUsed(tunText, usedVpnTuns) }
  val tunValid = remember(tunText, tunNameConflict) { isValidQwdttTun(tunText) && !tunNameConflict }
  val dnsParsed = remember(dnsText) { parseQwdttDnsInput(dnsText) }
  val hashesParsed = remember(hashesText) { parseQwdttHashesInput(hashesText) }
  val peerValid = remember(peerText) { isValidQwdttPeer(peerText) }
  val listenPortValue = remember(listenPortText) { listenPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } }
  val workersValue = remember(workersText) { workersText.trim().toIntOrNull()?.takeIf { it in 1..108 } }
  val mtuValue = remember(mtuText) { mtuText.trim().toIntOrNull()?.takeIf { it in 576..9000 } }
  val cidrValid = remember(cidrText) { cidrText.isBlank() || isValidQwdttCidr(cidrText) }
  val passwordValid = remember(passwordText) { passwordText.isNotBlank() }
  val deviceIdValid = remember(deviceIdText) { isValidQwdttDeviceId(deviceIdText) }

  val settingComplete = peerValid && hashesParsed != null && passwordValid &&
    listenPortValue != null && workersValue != null && mtuValue != null &&
    dnsParsed != null && tunValid && cidrValid && deviceIdValid

  LaunchedEffect(
    peerText, hashesText, passwordText, listenPortText, workersText, obfs, vkAuth, vkAnonPath,
    captchaMode, goDnsText, deviceIdText, timezoneText, tunText, dnsText, mtuText, cidrText,
    settingInitialized,
  ) {
    if (!settingInitialized || loading) return@LaunchedEffect
    delay(QWDTT_AUTOSAVE_DELAY_MS)
    // The daemon rejects an incomplete profile, so only push a valid one.
    if (!settingComplete) return@LaunchedEffect
    val current = QwdttSettingUi(
      tun = tunText.trim(),
      dns = dnsParsed ?: return@LaunchedEffect,
      mtu = mtuValue ?: return@LaunchedEffect,
      cidr = cidrText.trim(),
      peer = peerText.trim(),
      listenPort = listenPortValue ?: return@LaunchedEffect,
      vkHashes = hashesParsed ?: return@LaunchedEffect,
      password = passwordText,
      workers = workersValue ?: return@LaunchedEffect,
      obfs = obfs,
      vkAuth = vkAuth,
      vkAnonPath = vkAnonPath,
      goDns = goDnsText.trim().ifBlank { "yandex" },
      captchaMode = captchaMode,
      deviceId = deviceIdText.trim(),
      timezone = timezoneText.trim(),
    )
    if (current == syncedSetting) return@LaunchedEffect
    val ok = awaitSaveJsonQwdtt(actions, "$basePath/setting", buildQwdttSettingJson(current))
    if (ok) {
      syncedSetting = current
    } else {
      showSnack(context.getString(R.string.save_failed))
    }
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    QwdttProfileEnabledCard(
      checked = prof?.enabled ?: false,
      onCheckedChange = { checked ->
        if (checked && !settingComplete) {
          showSnack(context.getString(R.string.qwdtt_incomplete_setting))
        } else {
          actions.setProfileEnabled("qwdtt", profile, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        }
      },
    )

    if (loading) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.common_loading))
        }
      }
    }

    // ── Transport (VK TURN → VPS) ───────────────────────────────────────────
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.qwdtt_transport_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          stringResource(R.string.qwdtt_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = profile,
          onValueChange = {},
          modifier = Modifier.fillMaxWidth(),
          readOnly = true,
          label = { Text(stringResource(R.string.profile_name_label)) },
          supportingText = { Text(stringResource(R.string.qwdtt_profile_name_readonly_hint)) },
        )
        OutlinedTextField(
          value = peerText,
          onValueChange = { peerText = it.trim() },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_peer_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = peerText.isNotBlank() && !peerValid,
          supportingText = { Text(stringResource(R.string.qwdtt_peer_hint)) },
        )
        if (peerText.isBlank() || !peerValid) {
          Text(stringResource(R.string.qwdtt_peer_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = hashesText,
          onValueChange = { hashesText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_hashes_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = false,
          minLines = 2,
          isError = hashesText.isNotBlank() && hashesParsed == null,
          supportingText = { Text(stringResource(R.string.qwdtt_hashes_hint)) },
        )
        if (hashesText.isBlank() || hashesParsed == null) {
          Text(stringResource(R.string.qwdtt_hashes_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = passwordText,
          onValueChange = { passwordText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_password_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = !passwordValid,
          supportingText = { Text(stringResource(R.string.qwdtt_password_hint)) },
        )
        OutlinedTextField(
          value = workersText,
          onValueChange = { workersText = it.filter(Char::isDigit).take(3) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_workers_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = workersText.isNotBlank() && workersValue == null,
          supportingText = { Text(stringResource(R.string.qwdtt_workers_hint)) },
        )
        OutlinedTextField(
          value = listenPortText,
          onValueChange = { listenPortText = it.filter(Char::isDigit).take(5) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_listen_port_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = listenPortText.isNotBlank() && listenPortValue == null,
          supportingText = { Text(stringResource(R.string.qwdtt_listen_port_hint)) },
        )

        QwdttChoiceRow(
          label = stringResource(R.string.qwdtt_obfs_label),
          current = obfs,
          firstValue = "video",
          firstLabel = stringResource(R.string.qwdtt_obfs_video),
          secondValue = "audio",
          secondLabel = stringResource(R.string.qwdtt_obfs_audio),
          onSelect = { obfs = it },
        )
        QwdttChoiceRow(
          label = stringResource(R.string.qwdtt_vk_auth_label),
          current = vkAuth,
          firstValue = "anonymous",
          firstLabel = stringResource(R.string.qwdtt_vk_auth_anonymous),
          secondValue = "account",
          secondLabel = stringResource(R.string.qwdtt_vk_auth_account),
          onSelect = { vkAuth = it },
        )
        QwdttChoiceRow(
          label = stringResource(R.string.qwdtt_vk_anon_path_label),
          current = vkAnonPath,
          firstValue = "vkcalls",
          firstLabel = stringResource(R.string.qwdtt_vk_anon_path_vkcalls),
          secondValue = "legacy",
          secondLabel = stringResource(R.string.qwdtt_vk_anon_path_legacy),
          onSelect = { vkAnonPath = it },
        )
        QwdttChoiceRow(
          label = stringResource(R.string.qwdtt_captcha_label),
          current = captchaMode,
          firstValue = "auto",
          firstLabel = stringResource(R.string.qwdtt_captcha_auto),
          secondValue = "rjs",
          secondLabel = stringResource(R.string.qwdtt_captcha_rjs),
          onSelect = { captchaMode = it },
        )

        OutlinedTextField(
          value = goDnsText,
          onValueChange = { goDnsText = it.trim() },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_go_dns_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          supportingText = { Text(stringResource(R.string.qwdtt_go_dns_hint)) },
        )
        OutlinedTextField(
          value = deviceIdText,
          onValueChange = { deviceIdText = it.trim().take(16) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_device_id_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = !deviceIdValid,
          supportingText = { Text(stringResource(R.string.qwdtt_device_id_hint)) },
        )
        if (!deviceIdValid) {
          Text(stringResource(R.string.qwdtt_device_id_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = timezoneText,
          onValueChange = { timezoneText = it.trim().take(10) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_timezone_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          supportingText = { Text(stringResource(R.string.qwdtt_timezone_hint)) },
        )
      }
    }

    // ── Interface ───────────────────────────────────────────────────────────
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.qwdtt_interface_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
          value = tunText,
          onValueChange = { tunText = it.trim().take(15) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_tun_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = tunText.isNotBlank() && !tunValid,
          supportingText = { Text(stringResource(R.string.qwdtt_tun_hint)) },
        )
        if (tunText.isNotBlank() && !isValidQwdttTun(tunText)) {
          Text(stringResource(R.string.qwdtt_tun_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (tunNameConflict) {
          Text(stringResource(R.string.vpn_tun_name_in_use), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = dnsText,
          onValueChange = { dnsText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_dns_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = false,
          minLines = 1,
          isError = dnsText.isNotBlank() && dnsParsed == null,
          supportingText = { Text(stringResource(R.string.qwdtt_dns_hint)) },
        )
        if (dnsText.isBlank() || dnsParsed == null) {
          Text(stringResource(R.string.qwdtt_dns_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = mtuText,
          onValueChange = { mtuText = it.filter(Char::isDigit).take(4) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_mtu_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = mtuText.isNotBlank() && mtuValue == null,
          supportingText = { Text(stringResource(R.string.qwdtt_mtu_hint)) },
        )
        OutlinedTextField(
          value = cidrText,
          onValueChange = { cidrText = it.trim() },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.qwdtt_cidr_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = cidrText.isNotBlank() && !cidrValid,
          supportingText = { Text(stringResource(R.string.qwdtt_cidr_hint)) },
        )
      }
    }

    AppListPickerCard(
      title = stringResource(R.string.qwdtt_apps_title),
      desc = stringResource(R.string.qwdtt_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      saveFailedMessage = stringResource(R.string.qwdtt_app_conflict_error),
      onSavedSelection = { appCount = it.size },
    )

    if ((prof?.enabled == true) && appCount == 0) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
      ) {
        Text(
          stringResource(R.string.qwdtt_enabled_empty_apps_warning),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
    Spacer(Modifier.height(bottomContentPadding + 12.dp))
  }
}
