package io.github.mesmerprism.rustykiosk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import java.util.UUID

/** Fixed shell observation/actuation protocol, not a command, Intent or filesystem bridge. */
class ShellCompanionProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val ctx = context ?: error("Provider unavailable")
    ctx.enforceCallingPermission("android.permission.DUMP", "Authorized ADB shell required")
    val input = extras ?: throw IllegalArgumentException("Typed extras required")
    val protocol = input.required<Int>("protocol")
    val pid = input.required<Int>("pid")
    if (!ShellCompanionAdmissionPolicy.authorized(Binder.getCallingUid(), Binder.getCallingPid(), pid, protocol)) {
      throw SecurityException("Only the immediate UID2000 shell companion may call this provider")
    }
    require(arg == null) { "Arguments are unsupported" }
    val identity = ShellCompanionIdentity(input.uuid("session"), input.uuid("boot"), pid)
    val specific = when (method) {
      "register" -> setOf("ready")
      "poll" -> emptySet()
      "target-state" -> setOf("generation", "own_focused", "observed_uptime_ms")
      "native-open", "native-closed" -> setOf("generation", "sequence", "event_uptime_ms", "observed_uptime_ms")
      "claim-close" -> setOf("generation", "command_id")
      "close-result" -> setOf("generation", "command_id", "outcome", "success", "dispatch_uptime_ms", "callback_uptime_ms", "completion_uptime_ms")
      else -> throw IllegalArgumentException("Unknown operation")
    }
    require(input.keySet().all { it in COMMON || it in specific }) { "Unknown field" }
    val now = SystemClock.uptimeMillis()
    return ShellCompanionBridge.withCoordinator(ctx) { coordinator ->
      val targetBefore = if (method == "close-result") coordinator.originalTarget else coordinator.armedTarget
      val generation = if ("generation" in specific) input.required<Long>("generation") else null
      val reply = when (method) {
        "register" -> coordinator.register(identity, input.required("ready"), now)
        "poll" -> coordinator.heartbeat(identity, now)
        "target-state" -> coordinator.targetState(identity, generation!!, input.required("own_focused"), now,
          input.required("observed_uptime_ms"))
        "native-open", "native-closed" -> coordinator.nativeEvent(identity, generation!!,
          input.required("sequence"), input.required("event_uptime_ms"), input.required("observed_uptime_ms"), method == "native-open", now)
        "claim-close" -> coordinator.claim(identity, generation!!, input.uuid("command_id"), now)
        "close-result" -> {
          val outcome = input.required<String>("outcome")
          require(outcome in setOf("callback", "unknown", "error", "not-executed")) { "Unknown outcome" }
          coordinator.result(identity, generation!!, input.uuid("command_id"), outcome,
            input.required("success"), input.optionalLong("dispatch_uptime_ms"), input.optionalLong("callback_uptime_ms"), now,
            input.optionalLong("completion_uptime_ms"))
        }
        else -> error("Unreachable")
      }
      val timing = GuardTimingStore(ctx)
      if (targetBefore != null) {
        val id = reply.commandId ?: if (method == "close-result") input.uuid("command_id") else null
        fun record(phase: GuardTimingPhase, timestamp: Long = now, event: Long? = null) =
          timing.record(targetBefore.mode, targetBefore.generation, phase, timestamp, id, "native-invocation", event)
        when {
          method == "native-open" && reply.status in setOf("close_scheduled", "triple_home_escape", "home_coalesced", "accessibility_observer") ->
            record(GuardTimingPhase.SIGNAL_OBSERVED, event = input.required("event_uptime_ms"))
          method == "native-closed" && reply.status in setOf("native_closed", "accessibility_observer") ->
            record(GuardTimingPhase.NATIVE_CLOSED, input.required("event_uptime_ms"))
          method == "target-state" && input.required<Boolean>("own_focused") && reply.status in setOf("target_qualified", "target_observed") ->
            record(GuardTimingPhase.TARGET_FOCUSED)
        }
        if (method == "close-result" && reply.status in setOf("callback_known", "callback_failed", "original_unknown_held")) {
          record(GuardTimingPhase.RECOVERY_DISPATCHED, input.required("dispatch_uptime_ms"))
          input.optionalLong("callback_uptime_ms")?.let { record(GuardTimingPhase.ORIGINAL_CALLBACK, it) }
        }
        if (reply.action == "escape") record(GuardTimingPhase.ESCAPE)
      }
      if (reply.disarm) {
        val active = GuardStateStore(ctx).loadArmed()
        check(active == null || (active.mode == GuardMode.SHELL &&
          active.generation == targetBefore?.generation)) { "Refusing to revoke another guard generation" }
        GuardStateStore(ctx).disarm(reply.status)
        coordinator.disarm()
        check(GuardStateStore(ctx).loadArmed() == null) { "Guard disarm was not persisted" }
      }
      val target = coordinator.armedTarget
      Bundle().apply {
        putInt("protocol", 1); putString("status", reply.status); putString("action", reply.action)
        putBoolean("ready", coordinator.ready(now)); putBoolean("armed", target != null)
        putBoolean("healthy", coordinator.healthy(now))
        putString("mode", target?.mode?.id ?: GuardStateStore(ctx).selectedMode().id)
        putLong("delay_ms", ShellCompanionCoordinator.CLOSE_DELAY_MS)
        target?.let {
          putLong("generation", it.generation); putString("mode", it.mode.id)
          putString("target_package", it.packageName); putString("target_activity", it.activityName)
        }
        reply.commandId?.let { putString("command_id", it) }
        reply.dueUptimeMs?.let { putLong("due_uptime_ms", it) }
      }
    }
  }

  override fun getType(uri: Uri): String? = null
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = unsupported()
  override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()

  private fun unsupported(): Nothing = throw UnsupportedOperationException("Shell companion supports call() only")
  private inline fun <reified T> Bundle.required(key: String): T {
    @Suppress("DEPRECATION") val value = get(key)
    require(value is T) { "Missing or incorrectly typed $key" }
    return value
  }
  private fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) required<Long>(key) else null
  private fun Bundle.uuid(key: String): String = required<String>(key).also {
    require(it.length == 36 && runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Invalid identifier" }
  }
  private companion object { val COMMON = setOf("protocol", "session", "boot", "pid") }
}
