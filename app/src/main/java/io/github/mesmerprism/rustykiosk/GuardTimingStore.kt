package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

internal enum class GuardTimingPhase {
  SIGNAL_OBSERVED, RECOVERY_DISPATCHED, ORIGINAL_CALLBACK, NATIVE_CLOSED, TARGET_OBSERVED, TARGET_FOCUSED, ESCAPE, DISARMED
}

/** Bounded diagnostic timestamps, never a rendering acknowledgement. All values use uptimeMillis. */
internal class GuardTimingStore(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences("guard_timing_v1", Context.MODE_PRIVATE)

  fun record(mode: GuardMode, generation: Long, phase: GuardTimingPhase,
    uptimeMs: Long = SystemClock.uptimeMillis(), commandId: String? = null,
    source: String = "accessibility", eventUptimeMs: Long? = null) {
    if (uptimeMs < 0L || eventUptimeMs?.let { it < 0L || it > uptimeMs } == true) return
    synchronized(LOCK) {
      val rows = readRows()
      val previous = rows.optJSONObject(rows.length() - 1)
      // Repeated target bookkeeping must not evict the comparison's native endpoints.
      if (previous != null && phase in setOf(GuardTimingPhase.TARGET_OBSERVED, GuardTimingPhase.TARGET_FOCUSED) &&
        previous.optString("phase") == phase.name && previous.optString("mode") == mode.id &&
        previous.optLong("generation") == generation && previous.optString("source") == source.take(40)) return
      rows.put(JSONObject().apply {
        put("mode", mode.id); put("generation", generation); put("phase", phase.name)
        put("uptime_ms", uptimeMs); put("command_id", commandId ?: JSONObject.NULL)
        put("source", source.take(40)); put("event_uptime_ms", eventUptimeMs ?: JSONObject.NULL)
      })
      val bounded = JSONArray()
      for (i in maxOf(0, rows.length() - MAX_ROWS) until rows.length()) bounded.put(rows.getJSONObject(i))
      preferences.edit().putString("rows", bounded.toString()).apply()
    }
  }

  fun snapshotJson(): JSONObject = synchronized(LOCK) {
    val rows = readRows()
    JSONObject().put("clock", "android-uptime-millis").put("render_effect_ack", false)
      .put("max_rows", MAX_ROWS).put("rows", rows)
      .put("native_comparison", GuardTimingAnalysis.summarize(rows))
  }

  fun summary(): String = synchronized(LOCK) {
    GuardTimingAnalysis.summary(readRows()) + ". Native state timing; visible flash is unmeasured."
  }

  private fun readRows(): JSONArray = runCatching { JSONArray(preferences.getString("rows", "[]")) }.getOrDefault(JSONArray())

  companion object {
    private val LOCK = Any()
    private const val MAX_ROWS = 64
  }
}
