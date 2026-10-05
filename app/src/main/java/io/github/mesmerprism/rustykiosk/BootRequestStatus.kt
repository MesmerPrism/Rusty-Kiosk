package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import org.json.JSONTokener

/** Helper-owned boot evidence; setting readback never proves an ADB listener. */
internal data class BootRequestStatus(
  val bootCount: Int,
  val elapsedRealtimeMs: Long,
  val outcome: String,
  val adbEnabled: Boolean?,
  val wifiSettingEnabled: Boolean?,
  val message: String,
  val dispatchElapsedRealtimeMs: Long? = null,
  val wifiConnected: Boolean? = null,
) {
  val summary: String
    get() {
      val boot = if (bootCount < 0) "unknown" else bootCount.toString()
      val setting = when (wifiSettingEnabled) { true -> "On"; false -> "Off"; null -> "Unknown" }
      return "Last boot $boot: ${outcome.replace('_', ' ')}; Wi-Fi setting $setting. ADB transport unverified."
    }

  fun toJson(): JSONObject = JSONObject()
    .put("boot_count", bootCount)
    .put("elapsed_realtime_ms", elapsedRealtimeMs)
    .put("outcome", outcome)
    .put("adb_enabled", adbEnabled ?: JSONObject.NULL)
    .put("wifi_setting_enabled", wifiSettingEnabled ?: JSONObject.NULL)
    .put("message", message)
    .put("dispatch_elapsed_realtime_ms", dispatchElapsedRealtimeMs ?: JSONObject.NULL)
    .put("wifi_connected", wifiConnected ?: JSONObject.NULL)

  companion object {
    fun parse(value: String?): BootRequestStatus? {
      if (value == null) return null // Older helper versions have no boot receipt.
      require(value.length <= 2048) { "Boot receipt exceeds its bound." }
      val tokenizer = JSONTokener(value)
      val json = tokenizer.nextValue()
      require(json is JSONObject && tokenizer.nextClean() == '\u0000') { "Incomplete boot receipt framing." }
      val baseFields = setOf("boot_count", "elapsed_realtime_ms", "outcome", "adb_enabled", "wifi_setting_enabled", "message")
      val fields = json.keys().asSequence().toSet()
      require(fields == baseFields || fields == baseFields + setOf("dispatch_elapsed_realtime_ms", "wifi_connected")) {
        "Unexpected boot receipt fields."
      }
      val count = json.get("boot_count")
      val elapsed = json.get("elapsed_realtime_ms")
      require(count is Int && count >= -1) { "Invalid boot count." }
      require(elapsed is Number && elapsed.toLong() >= 0 && elapsed.toString().matches(Regex("[0-9]+"))) {
        "Invalid boot elapsed time."
      }
      val outcome = json.get("outcome")
      require(outcome in setOf("opted_out", "no_authority", "waiting_for_wifi", "dispatch_started", "requested", "failed", "expired", "cancelled", "network_unavailable")) { "Invalid boot outcome." }
      fun setting(key: String): Boolean? {
        val result = json.get(key)
        require(result == JSONObject.NULL || result is Boolean) { "Invalid boot setting readback." }
        return result as? Boolean
      }
      val message = json.get("message")
      require(message is String && message.length <= 160) { "Invalid boot message." }
      val dispatch = if (json.has("dispatch_elapsed_realtime_ms")) json.get("dispatch_elapsed_realtime_ms") else JSONObject.NULL
      require(dispatch == JSONObject.NULL || dispatch is Number && dispatch.toLong() >= elapsed.toLong() &&
        dispatch.toString().matches(Regex("[0-9]+"))) { "Invalid boot dispatch time." }
      val connected = if (json.has("wifi_connected")) setting("wifi_connected") else null
      return BootRequestStatus(count, elapsed.toLong(), outcome as String, setting("adb_enabled"),
        setting("wifi_setting_enabled"), message, (dispatch as? Number)?.toLong(), connected)
    }
  }
}
