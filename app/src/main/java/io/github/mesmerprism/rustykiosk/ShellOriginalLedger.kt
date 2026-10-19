package io.github.mesmerprism.rustykiosk

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONObject

internal data class ShellOriginalClaim(
  val helper: ShellCompanionIdentity,
  val target: ShellGuardTarget,
  val commandId: String,
  val openedMs: Long,
  val dueMs: Long,
)

internal data class ShellOriginalTerminal(
  val outcome: String,
  val success: Boolean,
  val dispatchMs: Long?,
  val callbackMs: Long?,
  val completionMs: Long?,
)

internal sealed interface ShellOriginalRecord {
  data object Empty : ShellOriginalRecord
  data object Damaged : ShellOriginalRecord
  data class Pending(val claim: ShellOriginalClaim) : ShellOriginalRecord
  data class Retired(val claim: ShellOriginalClaim, val terminal: ShellOriginalTerminal) : ShellOriginalRecord
}

internal interface ShellOriginalLedger {
  fun read(): ShellOriginalRecord
  fun claim(original: ShellOriginalClaim): Boolean
  fun retire(original: ShellOriginalClaim, terminal: ShellOriginalTerminal): Boolean
}

internal class MemoryShellOriginalLedger : ShellOriginalLedger {
  private var record: ShellOriginalRecord = ShellOriginalRecord.Empty
  override fun read(): ShellOriginalRecord = record
  override fun claim(original: ShellOriginalClaim): Boolean {
    if (record is ShellOriginalRecord.Pending || record == ShellOriginalRecord.Damaged ||
      (record as? ShellOriginalRecord.Retired)?.claim?.commandId == original.commandId) return false
    record = ShellOriginalRecord.Pending(original)
    return true
  }
  override fun retire(original: ShellOriginalClaim, terminal: ShellOriginalTerminal): Boolean {
    if (record != ShellOriginalRecord.Pending(original)) return false
    record = ShellOriginalRecord.Retired(original, terminal)
    return true
  }
}

/** One durable original slot. Absent, retired, pending and damaged are distinct. */
internal class StoredShellOriginalLedger(private val preferences: SharedPreferences) : ShellOriginalLedger {
  override fun read(): ShellOriginalRecord = try {
    if (!preferences.contains(KEY)) ShellOriginalRecord.Empty else {
      val text = preferences.getString(KEY, null) ?: error("null-original-record")
      require(text.length <= MAX_BYTES)
      val record = JSONObject(text)
      val state = record.string("state")
      record.exact(if (state == "pending") setOf("schema", "state", "original") else setOf("schema", "state", "original", "terminal"))
      require(record.string("schema") == SCHEMA)
      val original = decodeOriginal(record.getJSONObject("original"))
      when (state) {
        "pending" -> ShellOriginalRecord.Pending(original)
        "retired" -> ShellOriginalRecord.Retired(original, decodeTerminal(record.getJSONObject("terminal"), original))
        else -> error("unknown-original-state")
      }
    }
  } catch (_: Exception) { ShellOriginalRecord.Damaged }

  override fun claim(original: ShellOriginalClaim): Boolean = try {
    val before = read()
    if (before is ShellOriginalRecord.Pending || before == ShellOriginalRecord.Damaged ||
      (before as? ShellOriginalRecord.Retired)?.claim?.commandId == original.commandId) false else {
      validate(original)
      save(JSONObject().put("schema", SCHEMA).put("state", "pending").put("original", encode(original)))
    }
  } catch (_: Exception) { false }

  override fun retire(original: ShellOriginalClaim, terminal: ShellOriginalTerminal): Boolean = try {
    if (read() != ShellOriginalRecord.Pending(original)) false else {
      validate(terminal, original)
      save(JSONObject().put("schema", SCHEMA).put("state", "retired")
        .put("original", encode(original)).put("terminal", encode(terminal)))
    }
  } catch (_: Exception) { false }

  private fun save(record: JSONObject): Boolean {
    val text = record.toString()
    require(text.length <= MAX_BYTES)
    return preferences.edit().putString(KEY, text).commit()
  }

  private fun encode(original: ShellOriginalClaim): JSONObject = JSONObject()
    .put("session", original.helper.session).put("boot", original.helper.boot).put("pid", original.helper.pid)
    .put("generation", original.target.generation).put("mode", original.target.mode.id)
    .put("target_package", original.target.packageName).put("target_activity", original.target.activityName)
    .put("command_id", original.commandId).put("opened_ms", original.openedMs).put("due_ms", original.dueMs)

  private fun decodeOriginal(record: JSONObject): ShellOriginalClaim {
    record.exact(setOf("session", "boot", "pid", "generation", "mode", "target_package", "target_activity", "command_id", "opened_ms", "due_ms"))
    require(record.string("mode") == GuardMode.SHELL.id)
    val pid = record.long("pid")
    require(pid in 1..Int.MAX_VALUE.toLong())
    return ShellOriginalClaim(
      ShellCompanionIdentity(record.string("session"), record.string("boot"), pid.toInt()),
      ShellGuardTarget(record.long("generation"), record.string("target_package"), record.string("target_activity")),
      record.string("command_id"), record.long("opened_ms"), record.long("due_ms"),
    ).also(::validate)
  }

  private fun encode(terminal: ShellOriginalTerminal): JSONObject = JSONObject().put("outcome", terminal.outcome)
    .put("success", terminal.success).put("dispatch_ms", terminal.dispatchMs ?: JSONObject.NULL)
    .put("callback_ms", terminal.callbackMs ?: JSONObject.NULL).put("completion_ms", terminal.completionMs ?: JSONObject.NULL)

  private fun decodeTerminal(record: JSONObject, original: ShellOriginalClaim): ShellOriginalTerminal {
    record.exact(setOf("outcome", "success", "dispatch_ms", "callback_ms", "completion_ms"))
    val success = record.get("success")
    require(success is Boolean)
    return ShellOriginalTerminal(record.string("outcome"), success, record.nullableLong("dispatch_ms"),
      record.nullableLong("callback_ms"), record.nullableLong("completion_ms")).also { validate(it, original) }
  }

  private fun validate(original: ShellOriginalClaim) {
    for (id in listOf(original.helper.session, original.helper.boot, original.commandId))
      require(id.length == 36 && UUID.fromString(id).toString() == id)
    require(original.helper.pid > 0 && original.target.generation != 0L && original.target.mode == GuardMode.SHELL)
    require(original.target.packageName.length <= 255 && original.target.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
    require(original.target.activityName.length <= 512 && original.target.activityName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")))
    require(original.openedMs in 0..(Long.MAX_VALUE - ShellCompanionCoordinator.CLOSE_DELAY_MS) &&
      original.dueMs == original.openedMs + ShellCompanionCoordinator.CLOSE_DELAY_MS)
  }

  private fun validate(terminal: ShellOriginalTerminal, original: ShellOriginalClaim) {
    when (terminal.outcome) {
      "callback" -> require(terminal.dispatchMs != null && terminal.dispatchMs >= original.dueMs &&
        terminal.callbackMs != null && terminal.callbackMs >= terminal.dispatchMs && terminal.completionMs == null)
      "not-executed" -> require(!terminal.success && terminal.dispatchMs == null && terminal.callbackMs == null &&
        terminal.completionMs != null && terminal.completionMs >= original.dueMs)
      else -> error("unknown-original-terminal")
    }
  }

  private fun JSONObject.exact(keys: Set<String>) { require(keys().asSequence().toSet() == keys) }
  private fun JSONObject.string(key: String): String = get(key).also { require(it is String) } as String
  private fun JSONObject.long(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
  private fun JSONObject.nullableLong(key: String): Long? = if (get(key) === JSONObject.NULL) null else long(key)

  companion object {
    const val PREFERENCES = "shell-companion-original"
    const val KEY = "record"
    private const val MAX_BYTES = 16_384
    private const val SCHEMA = "rusty.kiosk.shell_original.v1"
  }
}
