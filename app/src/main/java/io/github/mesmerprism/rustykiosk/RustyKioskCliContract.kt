package io.github.mesmerprism.rustykiosk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

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

internal enum class CliValueRule {
  NONE,
  OPTIONAL,
  REQUIRED,
}

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

  private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,64}")
  private const val MAX_VALUE_LENGTH = 160
}

internal class RustyKioskCliStore(context: Context) {
  private val appContext = context.applicationContext
  private val preferences =
    appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun enqueue(request: RustyKioskCliRequest): Boolean {
    if (preferences.contains(KEY_PENDING_REQUEST_ID)) return false
    if (preferences.getString(KEY_LAST_CONSUMED_REQUEST_ID, null) == request.requestId) return false
    return preferences.edit()
      .putString(KEY_PENDING_REQUEST_ID, request.requestId)
      .putString(KEY_PENDING_COMMAND, request.command.wireName)
      .putString(KEY_PENDING_VALUE, request.value)
      .commit()
  }

  fun consume(requestId: String): RustyKioskCliRequest? {
    if (preferences.getString(KEY_PENDING_REQUEST_ID, null) != requestId) return null
    val parsed =
      RustyKioskCliProtocol.parse(
        requestId = requestId,
        command = preferences.getString(KEY_PENDING_COMMAND, null),
        value = preferences.getString(KEY_PENDING_VALUE, null),
      ).getOrNull()
    preferences.edit()
      .remove(KEY_PENDING_REQUEST_ID)
      .remove(KEY_PENDING_COMMAND)
      .remove(KEY_PENDING_VALUE)
      .putString(KEY_LAST_CONSUMED_REQUEST_ID, requestId)
      .commit()
    return parsed
  }

  fun record(
    request: RustyKioskCliRequest,
    outcome: RustyKioskCliOutcome,
    state: KioskUiState,
    guardArmed: Boolean,
  ) {
    val selected = state.selectedEntry
    val controls = state.userControls
    fun encodeEntries(entries: List<CatalogEntry>): JSONArray =
      JSONArray().also { array ->
        entries.take(MAX_RESULT_ENTRIES).forEach { entry ->
          array.put(
            JSONObject()
              .put("key", entry.key)
              .put("name", entry.label)
              .put("package", entry.packageName ?: JSONObject.NULL)
              .put("installed", entry.installed)
              .put("launchable", entry.launchable)
              .put("tags", JSONArray(entry.tags.sorted()))
          )
        }
      }
    val entries = encodeEntries(state.entries)
    val visibleEntries = encodeEntries(state.visibleEntries)
    val json =
      JSONObject()
        .put("schema", RustyKioskCliProtocol.SCHEMA)
        .put("request_id", request.requestId)
        .put("command", request.command.wireName)
        .put("accepted", outcome.accepted)
        .put("completed", outcome.completed)
        .put("message", outcome.message.take(MAX_MESSAGE_LENGTH))
        .put("recorded_at_ms", System.currentTimeMillis())
        .put(
          "state",
          JSONObject()
            .put("installed_count", state.entries.count(CatalogEntry::installed))
            .put("not_installed_count", state.entries.count { !it.installed })
            .put("visible_count", state.visibleEntries.size)
            .put("entries_truncated", state.entries.size > MAX_RESULT_ENTRIES)
            .put("entries", entries)
            .put("visible_entries_truncated", state.visibleEntries.size > MAX_RESULT_ENTRIES)
            .put("visible_entries", visibleEntries)
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
            .put("operation_in_progress", controls.operationInProgress ?: JSONObject.NULL)
        )
    val resultFile = File(appContext.filesDir, RustyKioskCliProtocol.RESULT_RELATIVE_PATH)
    resultFile.parentFile?.mkdirs()
    val temp = File(resultFile.parentFile, "${resultFile.name}.tmp")
    temp.writeText(json.toString(), StandardCharsets.UTF_8)
    check(temp.renameTo(resultFile) || runCatching {
      resultFile.writeText(json.toString(), StandardCharsets.UTF_8)
      temp.delete()
      true
    }.getOrDefault(false)) { "Could not record CLI result." }
  }

  fun readResult(requestId: String): String? {
    val resultFile = File(appContext.filesDir, RustyKioskCliProtocol.RESULT_RELATIVE_PATH)
    if (!resultFile.isFile || resultFile.length() > MAX_RESULT_BYTES) return null
    return runCatching {
        val json = JSONObject(resultFile.readText(StandardCharsets.UTF_8))
        if (
          json.optString("schema") != RustyKioskCliProtocol.SCHEMA ||
            json.optString("request_id") != requestId
        ) {
          null
        } else {
          json.toString()
        }
      }
      .getOrNull()
  }

  private companion object {
    const val PREFERENCES = "rusty_kiosk_cli"
    const val KEY_PENDING_REQUEST_ID = "pending_request_id"
    const val KEY_PENDING_COMMAND = "pending_command"
    const val KEY_PENDING_VALUE = "pending_value"
    const val KEY_LAST_CONSUMED_REQUEST_ID = "last_consumed_request_id"
    const val MAX_RESULT_ENTRIES = 500
    const val MAX_MESSAGE_LENGTH = 240
    const val MAX_RESULT_BYTES = 512 * 1024L
  }
}
