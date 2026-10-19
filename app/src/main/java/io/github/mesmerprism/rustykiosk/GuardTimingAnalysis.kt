package io.github.mesmerprism.rustykiosk

import org.json.JSONArray
import org.json.JSONObject

/** Pairs observed native endpoints only. These intervals are not pixel or causal acknowledgements. */
internal object GuardTimingAnalysis {
  private val modes = listOf(GuardMode.ACCESSIBILITY, GuardMode.SHELL, GuardMode.VISIBILITY)
  private data class Key(val mode: String, val generation: Long)
  private data class Opening(val timeMs: Long)

  fun summarize(rows: JSONArray): JSONObject {
    val pending = mutableMapOf<Key, Opening>()
    val intervals = JSONArray()
    var missingEndpoints = 0
    var escapeExcluded = 0
    for (index in 0 until rows.length()) {
      val row = rows.optJSONObject(index) ?: continue
      val mode = row.optString("mode")
      if (mode !in modes.map { it.id }) continue
      if (!row.has("generation") || !row.has("uptime_ms")) continue
      val generation = row.optLong("generation")
      val receipt = row.optLong("uptime_ms", -1L)
      if (generation == 0L || receipt < 0L) continue
      val key = Key(mode, generation)
      when (row.optString("phase")) {
        GuardTimingPhase.SIGNAL_OBSERVED.name -> {
          if (row.optString("source") != "native-invocation" ||
            !row.has("event_uptime_ms") || row.isNull("event_uptime_ms")) continue
          val event = row.optLong("event_uptime_ms", -1L)
          if (event < 0L || event > receipt) continue
          if (pending.put(key, Opening(event)) != null) missingEndpoints += 1
        }
        GuardTimingPhase.NATIVE_CLOSED.name -> {
          if (row.optString("source") != "native-invocation") continue
          val open = pending.remove(key)
          if (open == null || receipt < open.timeMs) {
            missingEndpoints += 1
            continue
          }
          intervals.put(JSONObject().apply {
            put("mode", mode)
            put("generation", generation)
            put("native_open_uptime_ms", open.timeMs)
            put("native_closed_uptime_ms", receipt)
            put("duration_ms", receipt - open.timeMs)
          })
        }
        GuardTimingPhase.ESCAPE.name, GuardTimingPhase.DISARMED.name -> {
          if (pending.remove(key) != null) escapeExcluded += 1
        }
      }
    }
    missingEndpoints += pending.size
    val summaries = JSONArray()
    for (mode in modes) {
      val values = (0 until intervals.length()).map { intervals.getJSONObject(it) }
        .filter { it.getString("mode") == mode.id }.map { it.getLong("duration_ms") }
      summaries.put(JSONObject().apply {
        put("mode", mode.id)
        put("count", values.size)
        put("min_ms", values.minOrNull() ?: JSONObject.NULL)
        put("mean_ms", if (values.isEmpty()) JSONObject.NULL else values.average())
        put("max_ms", values.maxOrNull() ?: JSONObject.NULL)
      })
    }
    return JSONObject().apply {
      put("metric", "native-open-to-native-closed")
      put("clock", "android-uptime-millis")
      put("pixel_timing", false)
      put("causal_recovery_ack", false)
      put("intervals", intervals)
      put("by_mode", summaries)
      put("unpaired_endpoints", missingEndpoints)
      put("escape_or_disarm_excluded", escapeExcluded)
    }
  }

  fun summary(rows: JSONArray): String {
    val modes = summarize(rows).getJSONArray("by_mode")
    return (0 until modes.length()).joinToString("; ") { index ->
      val mode = modes.getJSONObject(index)
      if (mode.getInt("count") == 0) {
        "${mode.getString("mode")}: no paired native timing"
      } else {
        "${mode.getString("mode")}: ${mode.getInt("count")} native intervals, " +
          "${mode.getLong("min_ms")}–${mode.getLong("max_ms")} ms"
      }
    }
  }
}
