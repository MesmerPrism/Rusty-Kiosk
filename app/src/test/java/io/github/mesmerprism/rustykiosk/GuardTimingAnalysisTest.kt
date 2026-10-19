package io.github.mesmerprism.rustykiosk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTimingAnalysisTest {
  private fun row(mode: String, generation: Long, phase: String, time: Long,
    event: Long? = null, source: String = "native-invocation"): JSONObject = JSONObject().apply {
    put("mode", mode); put("generation", generation); put("phase", phase)
    put("uptime_ms", time); put("event_uptime_ms", event ?: JSONObject.NULL); put("source", source)
  }

  @Test fun commonNativeEndpointsCompareBothModesWithSourceTimes() {
    val rows = JSONArray().put(row("accessibility", 1, "SIGNAL_OBSERVED", 110, 100))
      .put(row("accessibility", 1, "NATIVE_CLOSED", 450))
      .put(row("shell", 2, "SIGNAL_OBSERVED", 1010, 1000))
      .put(row("shell", 2, "NATIVE_CLOSED", 1200))
    val result = GuardTimingAnalysis.summarize(rows)
    val modes = result.getJSONArray("by_mode")
    assertEquals(350L, modes.getJSONObject(0).getLong("min_ms"))
    assertEquals(200L, modes.getJSONObject(1).getLong("min_ms"))
    assertFalse(result.getBoolean("pixel_timing"))
    assertFalse(result.getBoolean("causal_recovery_ack"))
  }

  @Test fun missingGenerationModeOrNativeSourceNeverCreatesAPair() {
    val rows = JSONArray().put(row("accessibility", 1, "SIGNAL_OBSERVED", 110, 100, "accessibility-window"))
      .put(row("accessibility", 1, "NATIVE_CLOSED", 200))
      .put(row("shell", 2, "SIGNAL_OBSERVED", 310, 300))
      .put(row("shell", 3, "NATIVE_CLOSED", 400))
      .put(row("shell", 2, "SIGNAL_OBSERVED", 510, 500))
    val result = GuardTimingAnalysis.summarize(rows)
    assertEquals(0, result.getJSONArray("intervals").length())
    assertTrue(result.getJSONArray("by_mode").getJSONObject(0).isNull("mean_ms"))
    assertTrue(GuardTimingAnalysis.summary(rows).contains("no paired native timing"))
  }

  @Test fun thirdTriggerEscapeAndDisarmExcludeSubsequentManualClose() {
    for (phase in listOf("ESCAPE", "DISARMED")) {
      val rows = JSONArray().put(row("shell", 1, "SIGNAL_OBSERVED", 110, 100))
        .put(row("shell", 1, phase, 120)).put(row("shell", 1, "NATIVE_CLOSED", 500))
      val result = GuardTimingAnalysis.summarize(rows)
      assertEquals(0, result.getJSONArray("intervals").length())
      assertEquals(1, result.getInt("escape_or_disarm_excluded"))
    }
  }

  @Test fun lateCallbackRowsDoNotChangeEndpointPairingAndNegativeIntervalsAreRejected() {
    val rows = JSONArray().put(row("shell", 1, "SIGNAL_OBSERVED", 110, 100))
      .put(row("shell", 1, "NATIVE_CLOSED", 250))
      .put(row("shell", 1, "RECOVERY_DISPATCHED", 180))
      .put(row("shell", 1, "ORIGINAL_CALLBACK", 230))
      .put(row("shell", 1, "SIGNAL_OBSERVED", 1010, 1000))
      .put(row("shell", 1, "NATIVE_CLOSED", 900))
    val result = GuardTimingAnalysis.summarize(rows)
    assertEquals(1, result.getJSONArray("intervals").length())
    assertEquals(150L, result.getJSONArray("by_mode").getJSONObject(1).getLong("max_ms"))
  }
}
