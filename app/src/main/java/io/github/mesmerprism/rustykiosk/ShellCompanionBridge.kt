package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.os.SystemClock
import java.util.UUID
import org.json.JSONObject

/** In-process app authority shared by the UI and the call-only shell provider. */
internal object ShellCompanionBridge {
  private val lock = Any()
  private var instance: ShellCompanionCoordinator? = null
  private fun coordinator(context: Context): ShellCompanionCoordinator = instance ?: ShellCompanionCoordinator(
    StoredShellOriginalLedger(context.applicationContext.getSharedPreferences(StoredShellOriginalLedger.PREFERENCES, Context.MODE_PRIVATE)),
  ) { UUID.randomUUID().toString() }.also { instance = it }

  fun isReady(context: Context): Boolean = synchronized(lock) {
    coordinator(context).ready(SystemClock.uptimeMillis()) && !VisibilityMenuBridge.outstandingCustody()
  }
  fun canArmNewGuard(context: Context): Boolean = synchronized(lock) { !coordinator(context).outstandingCustody }
  fun outstandingCustody(context: Context): Boolean = synchronized(lock) { coordinator(context).outstandingCustody }
  fun description(context: Context): String = synchronized(lock) {
    val coordinator = coordinator(context)
    when {
      coordinator.originalStorageDamaged -> "Shell original ownership could not be read or saved. New guards remain unavailable; no original is replayed."
      VisibilityMenuBridge.outstandingCustody() -> "Waiting for the prior visibility controller to retire before shell arming."
      coordinator.ready(SystemClock.uptimeMillis()) -> "Shell companion ready (fresh heartbeat)."
      coordinator.originalHeld -> "Shell recovery disarmed; waiting for the original callback."
      coordinator.healthy(SystemClock.uptimeMillis()) -> "Shell companion busy with one original call; new arming waits."
      else -> "Shell companion unavailable. Start the optional authorized ADB companion; Accessibility remains separate."
    }
  }

  fun onTargetArmed(context: Context, config: GuardConfig) = synchronized(lock) {
    check(config.mode == GuardMode.SHELL) { "Shell arming requires explicit Shell mode" }
    val coordinator = coordinator(context)
    check(coordinator.armTarget(config.asShellTarget())) { "A revoked shell generation cannot be reused" }
  }

  fun <T> armAtomically(operation: () -> T): T = synchronized(lock) { operation() }

  /** StateStore owns persistence. This method must not call StateStore.disarm recursively. */
  fun disarm(context: Context, reason: String) = synchronized(lock) {
    val coordinator = coordinator(context)
    val target = coordinator.armedTarget
    coordinator.disarm()
    if (target != null) GuardTimingStore(context).record(target.mode, target.generation, GuardTimingPhase.DISARMED,
      source = "app-control")
  }

  fun metricsSummary(context: Context): String = GuardTimingStore(context).summary()
  fun metricsJson(context: Context): JSONObject = GuardTimingStore(context).snapshotJson()

  fun <T> withCoordinator(context: Context, operation: (ShellCompanionCoordinator) -> T): T = synchronized(lock) {
    val coordinator = coordinator(context)
    if (!coordinator.synchronizeTarget(GuardStateStore(context).loadArmed()?.asShellTarget())) {
      GuardStateStore(context).disarm("shell-control-plane-restarted-or-revoked")
      check(GuardStateStore(context).loadArmed() == null) { "Could not persist shell revocation" }
    }
    operation(coordinator)
  }

  private fun GuardConfig.asShellTarget() = ShellGuardTarget(generation, target.packageName, target.activityName, mode)
}
