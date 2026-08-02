package io.github.mesmerprism.rustykiosk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

internal enum class RustyKioskCliCommand(val wireName: String, val valueRule: CliValueRule) {
  STATUS("status", CliValueRule.NONE),
  SHOW_CONTROLS("show-controls", CliValueRule.NONE),
  SHOW_APPS("show-apps", CliValueRule.NONE),
  RELOAD("reload", CliValueRule.NONE),
  FOCUS_SEARCH("focus-search", CliValueRule.NONE),
  FOCUS_TAG_EDITOR("focus-tag-editor", CliValueRule.NONE),
  SET_SEARCH("set-search", CliValueRule.OPTIONAL),
  SELECT("select", CliValueRule.REQUIRED),
  FILTER_TAG("filter-tag", CliValueRule.OPTIONAL),
  ADD_TAG("add-tag", CliValueRule.REQUIRED),
  REMOVE_TAG("remove-tag", CliValueRule.REQUIRED),
  SET_LAUNCH_REQUIREMENT("set-launch-requirement", CliValueRule.REQUIRED),
  CANCEL_PENDING_LAUNCH("cancel-pending-launch", CliValueRule.NONE),
  LAUNCH_NORMAL("launch-normal", CliValueRule.NONE),
  LAUNCH_KIOSK("launch-kiosk", CliValueRule.NONE),
  CHECK_SETUP_HELPER("check-setup-helper", CliValueRule.NONE),
  REQUEST_WIFI_ADB("request-wifi-adb", CliValueRule.NONE),
  ENABLE_WIFI_AFTER_BOOT("enable-wifi-adb-after-boot", CliValueRule.NONE),
  DISABLE_WIFI_AFTER_BOOT("disable-wifi-adb-after-boot", CliValueRule.NONE),
  DISABLE_WIFI_ADB("disable-wifi-adb", CliValueRule.NONE),
  ENABLE_ACCESSIBILITY("enable-accessibility", CliValueRule.NONE),
  DISABLE_ACCESSIBILITY("disable-accessibility", CliValueRule.NONE),
  PASSTHROUGH_NATURAL("passthrough-natural", CliValueRule.NONE),
  PASSTHROUGH_CONTOUR("passthrough-contour", CliValueRule.NONE),
  EXIT_META_HOME("exit-meta-home", CliValueRule.NONE),
}

internal enum class CliValueRule { NONE, OPTIONAL, REQUIRED }

internal data class RustyKioskCliRequest(
  val requestId: String,
  val command: RustyKioskCliCommand,
  val value: String?,
)

internal data class RustyKioskCliOutcome(
  val accepted: Boolean,
  val completed: Boolean,
  val message: String,
)

internal enum class OperatorRequestState(val wireName: String) {
  PENDING("pending"),
  PENDING_WEARER_ACTION("pending_wearer_action"),
  CONFIRMED("confirmed"),
  REJECTED("rejected"),
  EXPIRED("expired"),
  CANCELLED("cancelled"),
  UNKNOWN("unknown"),
}

internal data class OperatorRequestStatus(
  val providerEpoch: String,
  val requestId: String,
  val state: OperatorRequestState,
  val command: String? = null,
  val enqueuedAtMs: Long? = null,
  val expiresAtMs: Long? = null,
  val completed: Boolean = state !in setOf(
    OperatorRequestState.PENDING,
    OperatorRequestState.PENDING_WEARER_ACTION,
  ),
  val message: String,
)

internal object OperatorRequestLifecyclePolicy {
  fun canCancel(state: OperatorRequestState, exactQueuedRequest: Boolean): Boolean =
    state == OperatorRequestState.PENDING && exactQueuedRequest

  fun isExpired(nowMs: Long, expiresAtMs: Long): Boolean =
    nowMs < 0L || expiresAtMs <= 0L || nowMs >= expiresAtMs

  fun canClaim(nowMs: Long, enqueuedAtMs: Long, expiresAtMs: Long): Boolean =
    nowMs >= 0L && enqueuedAtMs in 0..nowMs && expiresAtMs > nowMs &&
      expiresAtMs > enqueuedAtMs

  fun canRecord(
    activeRequestId: String?,
    activeCommand: String?,
    activeProviderEpoch: String?,
    activeEnqueuedAtMs: Long,
    activeExpiresAtMs: Long,
    nowMs: Long,
    request: RustyKioskCliRequest,
    currentProviderEpoch: String,
    terminalExists: Boolean,
  ): Boolean =
    !terminalExists && activeRequestId == request.requestId &&
      activeCommand == request.command.wireName &&
      activeProviderEpoch == currentProviderEpoch &&
      canClaim(nowMs, activeEnqueuedAtMs, activeExpiresAtMs)
}

internal object OperatorRequestProcessLock {
  val monitor = Any()
}

internal object RustyKioskCliProtocol {
  const val SCHEMA = "rusty.kiosk.cli_result.v1"
  const val EXTRA_REQUEST_ID = "rusty_kiosk_cli_request_id"
  const val EXTRA_COMMAND = "rusty_kiosk_cli_command"
  const val EXTRA_VALUE_BASE64 = "rusty_kiosk_cli_value_base64"
  const val EXTRA_PENDING_REQUEST_ID = "rusty_kiosk_pending_cli_request_id"
  const val RESULT_RELATIVE_PATH = "cli/last-result.json"

  fun parse(requestId: String?, command: String?, value: String?): Result<RustyKioskCliRequest> =
    runCatching {
      val cleanRequestId = requestId.orEmpty()
      require(REQUEST_ID_PATTERN.matches(cleanRequestId)) {
        "request id must contain 8-64 letters, digits, underscores, or hyphens"
      }
      val cleanCommand = command.orEmpty().trim().lowercase(Locale.ROOT)
      val kind = RustyKioskCliCommand.entries.firstOrNull { it.wireName == cleanCommand }
        ?: error("unknown command")
      val cleanValue = value?.trim()?.takeIf(String::isNotEmpty)
      require((cleanValue?.length ?: 0) <= MAX_VALUE_LENGTH) { "value is too long" }
      when (kind.valueRule) {
        CliValueRule.NONE -> require(cleanValue == null) { "command does not accept a value" }
        CliValueRule.OPTIONAL -> Unit
        CliValueRule.REQUIRED -> require(cleanValue != null) { "command requires a value" }
      }
      RustyKioskCliRequest(cleanRequestId, kind, cleanValue)
    }

  fun validRequestId(requestId: String?): String? =
    requestId?.takeIf(REQUEST_ID_PATTERN::matches)

  private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,64}")
  private const val MAX_VALUE_LENGTH = 160
}

internal class RustyKioskCliStore(
  context: Context,
  private val wallNow: () -> Long = System::currentTimeMillis,
) {
  private val appContext = context.applicationContext
  private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
  private val resultDirectory = File(appContext.filesDir, RESULT_DIRECTORY)

  fun providerEpoch(): String = synchronized(OperatorRequestProcessLock.monitor) {
    providerEpochLocked()
  }

  private fun providerEpochLocked(): String = preferences.getString(KEY_PROVIDER_EPOCH, null)
    ?: UUID.randomUUID().toString().also { generated ->
      preferences.edit().putString(KEY_PROVIDER_EPOCH, generated).commit()
    }

  fun enqueue(request: RustyKioskCliRequest): Boolean =
    synchronized(OperatorRequestProcessLock.monitor) { enqueueLocked(request) }

  private fun enqueueLocked(request: RustyKioskCliRequest): Boolean {
    reconcileExpired()
    if (preferences.contains(KEY_PENDING_REQUEST_ID) || preferences.contains(KEY_ACTIVE_REQUEST_ID)) {
      return false
    }
    if (resultFile(request.requestId).isFile ||
      preferences.getString(KEY_LAST_CONSUMED_REQUEST_ID, null) == request.requestId
    ) return false
    val now = wallNow()
    if (now < 0 || now > Long.MAX_VALUE - REQUEST_LIFETIME_MS) return false
    return preferences.edit()
      .putString(KEY_PENDING_REQUEST_ID, request.requestId)
      .putString(KEY_PENDING_COMMAND, request.command.wireName)
      .putString(KEY_PENDING_VALUE, request.value)
      .putString(KEY_PENDING_PROVIDER_EPOCH, providerEpochLocked())
      .putLong(KEY_PENDING_ENQUEUED_AT_MS, now)
      .putLong(KEY_PENDING_EXPIRES_AT_MS, now + REQUEST_LIFETIME_MS)
      .commit()
  }

  fun consume(requestId: String): RustyKioskCliRequest? =
    synchronized(OperatorRequestProcessLock.monitor) { consumeLocked(requestId) }

  private fun consumeLocked(requestId: String): RustyKioskCliRequest? {
    reconcileExpired()
    if (preferences.getString(KEY_PENDING_REQUEST_ID, null) != requestId) return null
    val now = wallNow()
    val enqueuedAt = preferences.getLong(KEY_PENDING_ENQUEUED_AT_MS, -1L)
    val expiresAt = preferences.getLong(KEY_PENDING_EXPIRES_AT_MS, -1L)
    val pendingEpoch = preferences.getString(KEY_PENDING_PROVIDER_EPOCH, null)
    if (pendingEpoch != providerEpochLocked() ||
      !OperatorRequestLifecyclePolicy.canClaim(now, enqueuedAt, expiresAt)
    ) {
      val command = preferences.getString(KEY_PENDING_COMMAND, null)
      clearPending()
      writeTerminalResult(
        requestId,
        command,
        OperatorRequestState.EXPIRED,
        "The queued operator request could not be claimed within its original lifetime.",
        enqueuedAt.takeIf { it >= 0L },
        expiresAt.takeIf { it > 0L },
      )
      return null
    }
    val parsed = RustyKioskCliProtocol.parse(
      requestId,
      preferences.getString(KEY_PENDING_COMMAND, null),
      preferences.getString(KEY_PENDING_VALUE, null),
    ).getOrNull()
    val editor = preferences.edit()
      .remove(KEY_PENDING_REQUEST_ID)
      .remove(KEY_PENDING_COMMAND)
      .remove(KEY_PENDING_VALUE)
      .remove(KEY_PENDING_PROVIDER_EPOCH)
      .remove(KEY_PENDING_ENQUEUED_AT_MS)
      .remove(KEY_PENDING_EXPIRES_AT_MS)
      .putString(KEY_LAST_CONSUMED_REQUEST_ID, requestId)
    if (parsed != null) {
      editor
        .putString(KEY_ACTIVE_REQUEST_ID, requestId)
        .putString(KEY_ACTIVE_COMMAND, parsed.command.wireName)
        .putString(KEY_ACTIVE_VALUE, parsed.value)
        .putString(KEY_ACTIVE_PROVIDER_EPOCH, pendingEpoch)
        .putLong(KEY_ACTIVE_ENQUEUED_AT_MS, enqueuedAt)
        .putLong(KEY_ACTIVE_EXPIRES_AT_MS, expiresAt)
    }
    editor.commit()
    return parsed
  }

  fun activeRequest(): RustyKioskCliRequest? =
    synchronized(OperatorRequestProcessLock.monitor) { activeRequestLocked() }

  private fun activeRequestLocked(): RustyKioskCliRequest? {
    reconcileExpired()
    if (preferences.getString(KEY_ACTIVE_PROVIDER_EPOCH, null) != providerEpochLocked()) return null
    return RustyKioskCliProtocol.parse(
      preferences.getString(KEY_ACTIVE_REQUEST_ID, null),
      preferences.getString(KEY_ACTIVE_COMMAND, null),
      preferences.getString(KEY_ACTIVE_VALUE, null),
    ).getOrNull()
  }

  fun cancel(requestId: String): OperatorRequestStatus =
    synchronized(OperatorRequestProcessLock.monitor) { cancelLocked(requestId) }

  private fun cancelLocked(requestId: String): OperatorRequestStatus {
    requireNotNull(RustyKioskCliProtocol.validRequestId(requestId)) { "A valid request id is required." }
    reconcileExpired()
    val current = statusLocked(requestId)
    if (!OperatorRequestLifecyclePolicy.canCancel(
        current.state,
        preferences.getString(KEY_PENDING_REQUEST_ID, null) == requestId,
      )) {
      return current.copy(
        message = "Only the exact queued request can be cancelled; applied or terminal requests are unchanged."
      )
    }
    val command = preferences.getString(KEY_PENDING_COMMAND, null)
    val enqueuedAt = preferences.getLong(KEY_PENDING_ENQUEUED_AT_MS, 0L)
    val expiresAt = preferences.getLong(KEY_PENDING_EXPIRES_AT_MS, 0L)
    clearPending()
    writeTerminalResult(
      requestId,
      command,
      OperatorRequestState.CANCELLED,
      "The queued operator request was cancelled before application.",
      enqueuedAt,
      expiresAt,
    )
    return statusLocked(requestId)
  }

  fun status(requestId: String): OperatorRequestStatus =
    synchronized(OperatorRequestProcessLock.monitor) { statusLocked(requestId) }

  private fun statusLocked(requestId: String): OperatorRequestStatus {
    requireNotNull(RustyKioskCliProtocol.validRequestId(requestId)) { "A valid request id is required." }
    reconcileExpired()
    val epoch = providerEpochLocked()
    if (preferences.getString(KEY_PENDING_REQUEST_ID, null) == requestId) {
      return OperatorRequestStatus(
        epoch,
        requestId,
        OperatorRequestState.PENDING,
        preferences.getString(KEY_PENDING_COMMAND, null),
        preferences.getLong(KEY_PENDING_ENQUEUED_AT_MS, 0L),
        preferences.getLong(KEY_PENDING_EXPIRES_AT_MS, 0L),
        completed = false,
        message = "The typed request is queued and has not been applied.",
      )
    }
    if (preferences.getString(KEY_ACTIVE_REQUEST_ID, null) == requestId) {
      return OperatorRequestStatus(
        epoch,
        requestId,
        OperatorRequestState.PENDING,
        preferences.getString(KEY_ACTIVE_COMMAND, null),
        preferences.getLong(KEY_ACTIVE_ENQUEUED_AT_MS, 0L).takeIf { it >= 0L },
        expiresAtMs = preferences.getLong(KEY_ACTIVE_EXPIRES_AT_MS, 0L),
        completed = false,
        message = "The typed request was claimed by the visible activity; matching readback is pending.",
      )
    }
    val result = readResultObject(requestId)
    if (result != null) {
      val state = OperatorRequestState.entries.firstOrNull {
        it.wireName == result.optString("operation_state")
      } ?: if (result.optBoolean("accepted") && result.optBoolean("completed")) {
        OperatorRequestState.CONFIRMED
      } else if (!result.optBoolean("completed")) {
        OperatorRequestState.PENDING_WEARER_ACTION
      } else {
        OperatorRequestState.REJECTED
      }
      return OperatorRequestStatus(
        result.optString("provider_epoch", epoch),
        requestId,
        state,
        result.optString("command").takeIf(String::isNotBlank),
        result.optLong("enqueued_at_ms").takeIf { it > 0L },
        result.optLong("expires_at_ms").takeIf { it > 0L },
        result.optBoolean("completed", state !in setOf(
          OperatorRequestState.PENDING,
          OperatorRequestState.PENDING_WEARER_ACTION,
        )),
        result.optString("message").take(MAX_MESSAGE_LENGTH),
      )
    }
    return OperatorRequestStatus(
      epoch,
      requestId,
      OperatorRequestState.UNKNOWN,
      message = "No queued, applied, or terminal request matches this id.",
    )
  }

  fun record(
    request: RustyKioskCliRequest,
    outcome: RustyKioskCliOutcome,
    state: KioskUiState,
    guardArmed: Boolean,
  ) = synchronized(OperatorRequestProcessLock.monitor) {
    reconcileExpired()
    val activeEnqueuedAtMs = preferences.getLong(KEY_ACTIVE_ENQUEUED_AT_MS, -1L)
    val activeExpiresAtMs = preferences.getLong(KEY_ACTIVE_EXPIRES_AT_MS, 0L)
    val nowMs = wallNow()
    if (!OperatorRequestLifecyclePolicy.canRecord(
        preferences.getString(KEY_ACTIVE_REQUEST_ID, null),
        preferences.getString(KEY_ACTIVE_COMMAND, null),
        preferences.getString(KEY_ACTIVE_PROVIDER_EPOCH, null),
        activeEnqueuedAtMs,
        activeExpiresAtMs,
        nowMs,
        request,
        providerEpochLocked(),
        resultFile(request.requestId).isFile,
      )) return@synchronized
    val selected = state.selectedEntry
    val controls = state.userControls
    fun encodeEntries(entries: List<CatalogEntry>): JSONArray = JSONArray().also { array ->
      entries.take(MAX_RESULT_ENTRIES).forEach { entry ->
        array.put(JSONObject()
          .put("key", entry.key)
          .put("name", entry.label)
          .put("package", entry.packageName ?: JSONObject.NULL)
          .put("installed", entry.installed)
          .put("launchable", entry.launchable)
          .put("tags", JSONArray(entry.tags.sorted()))
          .put("launch_requirement", entry.launchRequirement.wireName))
      }
    }
    val operationState = when {
      outcome.accepted && outcome.completed -> OperatorRequestState.CONFIRMED
      outcome.accepted -> OperatorRequestState.PENDING_WEARER_ACTION
      else -> OperatorRequestState.REJECTED
    }
    val json = JSONObject()
      .put("schema", RustyKioskCliProtocol.SCHEMA)
      .put("provider_epoch", providerEpochLocked())
      .put("request_id", request.requestId)
      .put("command", request.command.wireName)
      .put("operation_state", operationState.wireName)
      .put("accepted", outcome.accepted)
      .put("completed", outcome.completed)
      .put("message", outcome.message.take(MAX_MESSAGE_LENGTH))
      .put("enqueued_at_ms", activeEnqueuedAtMs)
      .put("expires_at_ms", activeExpiresAtMs)
      .put("recorded_at_ms", nowMs)
      .put("state", JSONObject()
        .put("installed_count", state.entries.count(CatalogEntry::installed))
        .put("not_installed_count", state.entries.count { !it.installed })
        .put("visible_count", state.visibleEntries.size)
        .put("entries_truncated", state.entries.size > MAX_RESULT_ENTRIES)
        .put("entries", encodeEntries(state.entries))
        .put("visible_entries_truncated", state.visibleEntries.size > MAX_RESULT_ENTRIES)
        .put("visible_entries", encodeEntries(state.visibleEntries))
        .put("search", state.searchQuery)
        .put("search_focus_request", state.searchFocusRequest)
        .put("tag_focus_request", state.tagFocusRequest)
        .put("tag_filter", state.selectedTag ?: JSONObject.NULL)
        .put("controls_open", state.userControlsOpen)
        .put("status_line", state.statusLine.take(MAX_MESSAGE_LENGTH))
        .put("tag_file_path", state.tagFilePath)
        .put("selected_key", selected?.key ?: JSONObject.NULL)
        .put("selected_name", selected?.label ?: JSONObject.NULL)
        .put("selected_package", selected?.packageName ?: JSONObject.NULL)
        .put("selected_installed", selected?.installed ?: false)
        .put("selected_launchable", selected?.launchable ?: false)
        .put("selected_launch_requirement", selected?.launchRequirement?.wireName ?: JSONObject.NULL)
        .put("pending_requirement_launch", state.pendingRequirementLaunchId != null)
        .put("pending_requirement_launch_id", state.pendingRequirementLaunchId ?: JSONObject.NULL)
        .put("wifi_adb_enabled", controls.wirelessDebuggingEnabled)
        .put("setup_helper_installed", controls.setupHelperInstalled)
        .put("setup_helper_ready", controls.setupHelperReady)
        .put("request_wifi_adb_after_boot", controls.requestWifiAfterBoot)
        .put("accessibility_enabled", controls.accessibilityEnabled)
        .put("passthrough_style", controls.passthroughStyle.wireName)
        .put("system_passthrough_enabled", controls.systemPassthroughEnabled)
        .put("passthrough_lut_applied", controls.passthroughLutApplied)
        .put("operator_bridge_enabled", controls.operatorBridgeEnabled)
        .put("operator_bridge_running", controls.operatorBridgeRunning)
        .put("operator_bridge_endpoint", controls.operatorBridgeEndpoint ?: JSONObject.NULL)
        .put("installer_allowed", controls.installerAllowed)
        .put("guard_armed", guardArmed)
        .put("operation_in_progress", controls.operationInProgress ?: JSONObject.NULL))
    writeResult(request.requestId, json)
    clearActive(request.requestId)
  }

  fun readResult(requestId: String): String? =
    synchronized(OperatorRequestProcessLock.monitor) { readResultLocked(requestId) }

  private fun readResultLocked(requestId: String): String? {
    requireNotNull(RustyKioskCliProtocol.validRequestId(requestId)) { "A valid request id is required." }
    reconcileExpired()
    return readResultObject(requestId)?.toString()
  }

  private fun reconcileExpired() {
    val now = wallNow()
    val pendingId = preferences.getString(KEY_PENDING_REQUEST_ID, null)
    val pendingEnqueued = preferences.getLong(KEY_PENDING_ENQUEUED_AT_MS, -1L)
    val pendingExpiry = preferences.getLong(KEY_PENDING_EXPIRES_AT_MS, 0L)
    if (pendingId != null &&
      !OperatorRequestLifecyclePolicy.canClaim(now, pendingEnqueued, pendingExpiry)
    ) {
      val command = preferences.getString(KEY_PENDING_COMMAND, null)
      clearPending()
      writeTerminalResult(
        pendingId,
        command,
        OperatorRequestState.EXPIRED,
        "The queued operator request expired before application.",
        pendingEnqueued.takeIf { it >= 0L },
        pendingExpiry,
      )
    }
    val activeId = preferences.getString(KEY_ACTIVE_REQUEST_ID, null)
    val activeEnqueued = preferences.getLong(KEY_ACTIVE_ENQUEUED_AT_MS, -1L)
    val activeExpiry = preferences.getLong(KEY_ACTIVE_EXPIRES_AT_MS, 0L)
    if (activeId != null && !OperatorRequestLifecyclePolicy.canClaim(now, activeEnqueued, activeExpiry)) {
      val command = preferences.getString(KEY_ACTIVE_COMMAND, null)
      clearActive(activeId)
      writeTerminalResult(
        activeId,
        command,
        OperatorRequestState.EXPIRED,
        "The applied operator request expired before matching readback was recorded.",
        activeEnqueued.takeIf { it >= 0L },
        activeExpiry,
      )
    }
  }

  private fun writeTerminalResult(
    requestId: String,
    command: String?,
    state: OperatorRequestState,
    message: String,
    enqueuedAtMs: Long?,
    expiresAtMs: Long?,
  ) {
    if (resultFile(requestId).isFile) return
    val json = JSONObject()
      .put("schema", RustyKioskCliProtocol.SCHEMA)
      .put("provider_epoch", providerEpochLocked())
      .put("request_id", requestId)
      .put("command", command ?: JSONObject.NULL)
      .put("operation_state", state.wireName)
      .put("accepted", false)
      .put("completed", true)
      .put("message", message.take(MAX_MESSAGE_LENGTH))
      .put("recorded_at_ms", wallNow())
      .put("enqueued_at_ms", enqueuedAtMs ?: JSONObject.NULL)
      .put("expires_at_ms", expiresAtMs ?: JSONObject.NULL)
    writeResult(requestId, json)
  }

  private fun writeResult(requestId: String, json: JSONObject) {
    resultDirectory.mkdirs()
    writeAtomically(resultFile(requestId), json.toString())
    writeAtomically(File(appContext.filesDir, RustyKioskCliProtocol.RESULT_RELATIVE_PATH), json.toString())
    resultDirectory.listFiles().orEmpty().filter(File::isFile)
      .sortedByDescending(File::lastModified).drop(MAX_RESULT_FILES).forEach(File::delete)
  }

  private fun writeAtomically(resultFile: File, text: String) {
    resultFile.parentFile?.mkdirs()
    val temp = File(resultFile.parentFile, "${resultFile.name}.tmp")
    temp.writeText(text, StandardCharsets.UTF_8)
    check(temp.renameTo(resultFile) || runCatching {
      resultFile.writeText(text, StandardCharsets.UTF_8)
      temp.delete()
      true
    }.getOrDefault(false)) { "Could not record CLI result." }
  }

  private fun readResultObject(requestId: String): JSONObject? {
    val file = resultFile(requestId)
    if (!file.isFile || file.length() > MAX_RESULT_BYTES) return null
    return runCatching {
      JSONObject(file.readText(StandardCharsets.UTF_8)).takeIf {
        it.optString("schema") == RustyKioskCliProtocol.SCHEMA &&
          it.optString("request_id") == requestId &&
          it.optString("provider_epoch") == providerEpochLocked()
      }
    }.getOrNull()
  }

  private fun resultFile(requestId: String): File = File(resultDirectory, "$requestId.json")

  private fun clearPending() {
    preferences.edit()
      .remove(KEY_PENDING_REQUEST_ID)
      .remove(KEY_PENDING_COMMAND)
      .remove(KEY_PENDING_VALUE)
      .remove(KEY_PENDING_PROVIDER_EPOCH)
      .remove(KEY_PENDING_ENQUEUED_AT_MS)
      .remove(KEY_PENDING_EXPIRES_AT_MS)
      .commit()
  }

  private fun clearActive(requestId: String) {
    if (preferences.getString(KEY_ACTIVE_REQUEST_ID, null) != requestId) return
    preferences.edit()
      .remove(KEY_ACTIVE_REQUEST_ID)
      .remove(KEY_ACTIVE_COMMAND)
      .remove(KEY_ACTIVE_VALUE)
      .remove(KEY_ACTIVE_PROVIDER_EPOCH)
      .remove(KEY_ACTIVE_ENQUEUED_AT_MS)
      .remove(KEY_ACTIVE_EXPIRES_AT_MS)
      .commit()
  }

  companion object {
    const val REQUEST_LIFETIME_MS = 2 * 60 * 1000L
    private const val PREFERENCES = "rusty_kiosk_cli"
    private const val KEY_PROVIDER_EPOCH = "provider_epoch"
    private const val KEY_PENDING_REQUEST_ID = "pending_request_id"
    private const val KEY_PENDING_COMMAND = "pending_command"
    private const val KEY_PENDING_VALUE = "pending_value"
    private const val KEY_PENDING_PROVIDER_EPOCH = "pending_provider_epoch"
    private const val KEY_PENDING_ENQUEUED_AT_MS = "pending_enqueued_at_ms"
    private const val KEY_PENDING_EXPIRES_AT_MS = "pending_expires_at_ms"
    private const val KEY_ACTIVE_REQUEST_ID = "active_request_id"
    private const val KEY_ACTIVE_COMMAND = "active_command"
    private const val KEY_ACTIVE_VALUE = "active_value"
    private const val KEY_ACTIVE_PROVIDER_EPOCH = "active_provider_epoch"
    private const val KEY_ACTIVE_ENQUEUED_AT_MS = "active_enqueued_at_ms"
    private const val KEY_ACTIVE_EXPIRES_AT_MS = "active_expires_at_ms"
    private const val KEY_LAST_CONSUMED_REQUEST_ID = "last_consumed_request_id"
    private const val RESULT_DIRECTORY = "cli/results"
    private const val MAX_RESULT_ENTRIES = 500
    private const val MAX_MESSAGE_LENGTH = 240
    private const val MAX_RESULT_BYTES = 512 * 1024L
    private const val MAX_RESULT_FILES = 128
  }
}
