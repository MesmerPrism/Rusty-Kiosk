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
  val dispatchElapsedRealtimeMs: Long? = null,
  val wifiConnected: Boolean? = null,
) {
  fun encode(): String = JSONObject()
    .put("boot_count", bootCount)
    .put("elapsed_realtime_ms", elapsedRealtimeMs)
    .put("outcome", outcome)
    .put("adb_enabled", adbEnabled ?: JSONObject.NULL)
    .put("wifi_setting_enabled", wifiSettingEnabled ?: JSONObject.NULL)
    .put("message", message.take(160))
    .put("dispatch_elapsed_realtime_ms", dispatchElapsedRealtimeMs ?: JSONObject.NULL)
    .put("wifi_connected", wifiConnected ?: JSONObject.NULL)
    .toString()

  companion object {
    fun decode(value: String?): BootRequestReceipt? = if (value == null) null else runCatching {
      val json = JSONObject(value)
      BootRequestReceipt(json.getInt("boot_count"), json.getLong("elapsed_realtime_ms"),
        json.getString("outcome"), json.opt("adb_enabled") as? Boolean,
        json.opt("wifi_setting_enabled") as? Boolean, json.getString("message"),
        (json.opt("dispatch_elapsed_realtime_ms") as? Number)?.toLong(), json.opt("wifi_connected") as? Boolean)
    }.getOrNull()
  }
}

/** Receiver/job and host tests execute the same opt-in, expiry and effect path. */
internal class BootRequestHandler(
  private val requestEnabled: () -> Boolean,
  private val hasAuthority: () -> Boolean,
  private val request: () -> SetupResult,
  private val readSettings: () -> Pair<Boolean?, Boolean?>,
  private val record: (BootRequestReceipt) -> Unit,
  private val schedule: (BootRequestReceipt) -> Boolean,
) {
  fun onBoot(bootCount: Int, elapsedRealtimeMs: Long) {
    val settings = readback()
    val receipt = BootRequestReceipt(bootCount, elapsedRealtimeMs,
      when { !requestEnabled() -> "opted_out"; !hasAuthority() -> "no_authority"; else -> "waiting_for_wifi" },
      settings.first, settings.second, "Boot received; no Wi-Fi ADB request dispatched yet.")
    record(receipt)
    if (receipt.outcome == "waiting_for_wifi" && !runCatching { schedule(receipt) }.getOrDefault(false)) {
      record(receipt.copy(outcome = "failed", message = "Android rejected scheduling the one-shot Wi-Fi boot request."))
    }
  }

  fun onDeferred(receipt: BootRequestReceipt, currentBootCount: Int, nowMs: Long, wifiReady: Boolean) {
    // No replay after completion, cancellation or a different boot.
    if (receipt.outcome != "waiting_for_wifi" || currentBootCount != receipt.bootCount || nowMs < receipt.elapsedRealtimeMs) return
    var outcome = "failed"
    var message = "Boot request failed."
    var dispatched: Long? = null
    try {
      when {
        !requestEnabled() -> { outcome = "cancelled"; message = "Pending boot request was revoked." }
        nowMs - receipt.elapsedRealtimeMs >= MAX_WAIT_MS -> { outcome = "expired"; message = "Wi-Fi boot request expired after ten minutes." }
        !hasAuthority() -> { outcome = "no_authority"; message = "Setup helper needs its USB-C provisioning grant." }
        !wifiReady -> { outcome = "network_unavailable"; message = "Assigned Wi-Fi network was no longer connected; no request made." }
        else -> {
          dispatched = nowMs
          val result = request()
          outcome = if (result.success) "requested" else "failed"
          message = result.message.take(160)
        }
      }
    } catch (error: Exception) {
      message = "Boot request failed (${error.javaClass.simpleName.take(40)})."
    }
    val settings = readback()
    record(receipt.copy(outcome = outcome, message = message, adbEnabled = settings.first,
      wifiSettingEnabled = settings.second, dispatchElapsedRealtimeMs = dispatched, wifiConnected = wifiReady))
  }

  private fun readback() = runCatching(readSettings).getOrDefault(null to null)

  companion object { const val MAX_WAIT_MS = 10 * 60 * 1000L }
}
