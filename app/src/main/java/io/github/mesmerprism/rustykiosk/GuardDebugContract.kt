package io.github.mesmerprism.rustykiosk

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

internal object GuardDebugContract {
  const val ACTION_EXTERNAL_HOME_TRANSITION =
    "io.github.mesmerprism.rustykiosk.debug.action.GUARD_HOME_TRANSITION"
  const val ACTION_INTERNAL_HOME_TRANSITION =
    "io.github.mesmerprism.rustykiosk.action.INTERNAL_DEBUG_GUARD_HOME_TRANSITION"
  const val EXTRA_REQUEST_ID = "rusty_kiosk_guard_cli_request_id"
  const val RESULT_RELATIVE_PATH = "cli/guard-last-result.json"
  const val SCHEMA = "rusty.kiosk.guard_cli_result.v1"
  const val META_SHELL_PACKAGE = "com.oculus.vrshell"

  fun validRequestId(value: String?): Boolean =
    value != null && REQUEST_ID_PATTERN.matches(value)

  private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,64}")
}

internal class GuardDebugResultStore(context: Context) {
  private val appContext = context.applicationContext

  fun record(
    requestId: String,
    accepted: Boolean,
    decision: String,
    message: String,
    guardArmed: Boolean,
  ) {
    val json =
      JSONObject()
        .put("schema", GuardDebugContract.SCHEMA)
        .put("request_id", requestId)
        .put("command", "guard-home-transition")
        .put("accepted", accepted)
        .put("completed", true)
        .put("decision", decision)
        .put("guard_armed", guardArmed)
        .put("message", message.take(160))
        .put("recorded_at_ms", System.currentTimeMillis())
    val resultFile = File(appContext.filesDir, GuardDebugContract.RESULT_RELATIVE_PATH)
    resultFile.parentFile?.mkdirs()
    val temp = File(resultFile.parentFile, "${resultFile.name}.tmp")
    temp.writeText(json.toString(), StandardCharsets.UTF_8)
    check(temp.renameTo(resultFile) || runCatching {
      resultFile.writeText(json.toString(), StandardCharsets.UTF_8)
      temp.delete()
      true
    }.getOrDefault(false)) { "Could not record guard CLI result." }
  }
}
