package io.github.mesmerprism.rustykiosk.setuphelper

import org.json.JSONObject

/** One observation of the real boot broadcast, never a claim of reachable ADB. */
internal data class BootRequestReceipt(
  val bootCount: Int,
  val elapsedRealtimeMs: Long,
  val outcome: String,
  val adbEnabled: Boolean?,
  val wifiSettingEnabled: Boolean?,
  val message: String,
) {
  fun encode(): String = JSONObject()
    .put("boot_count", bootCount)
    .put("elapsed_realtime_ms", elapsedRealtimeMs)
    .put("outcome", outcome)
    .put("adb_enabled", adbEnabled ?: JSONObject.NULL)
    .put("wifi_setting_enabled", wifiSettingEnabled ?: JSONObject.NULL)
    .put("message", message.take(160))
    .toString()
}

/** The receiver and host tests execute the same bounded decision/effect path. */
internal class BootRequestHandler(
  private val requestEnabled: () -> Boolean,
  private val hasAuthority: () -> Boolean,
  private val request: () -> SetupResult,
  private val readSettings: () -> Pair<Boolean?, Boolean?>,
  private val record: (BootRequestReceipt) -> Unit,
) {
  fun onBoot(bootCount: Int, elapsedRealtimeMs: Long) {
    var outcome = "failed"
    var message = "Boot request failed."
    try {
      when {
        !requestEnabled() -> {
          outcome = "opted_out"
          message = "Restart request is off; no setting was changed."
        }
        !hasAuthority() -> {
          outcome = "no_authority"
          message = "Setup helper needs its USB-C provisioning grant; no request was made."
        }
        else -> {
          val result = request()
          outcome = if (result.success) "requested" else "failed"
          message = result.message.take(160)
        }
      }
    } catch (error: Exception) {
      message = "Boot request failed (${error.javaClass.simpleName.take(40)})."
    }
    val settings = runCatching(readSettings).getOrDefault(null to null)
    record(BootRequestReceipt(bootCount, elapsedRealtimeMs, outcome, settings.first, settings.second, message))
  }
}
