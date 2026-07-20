package io.github.mesmerprism.rustykiosk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class KioskAccessibilityService : AccessibilityService() {
  private val handler = Handler(Looper.getMainLooper())
  private val store by lazy(LazyThreadSafetyMode.NONE) { GuardStateStore(this) }
  private var activeGeneration: Long? = null
  private var activeConfig: GuardConfig? = null
  private var engine: GuardDecisionEngine? = null
  private var pendingRecovery: Runnable? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    reloadConfiguration()
    Log.i(
      TAG,
      "status=service-connected readsUiContent=false eventTypes=window-state-changed,windows-changed",
    )
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    if (
      event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
    ) {
      return
    }
    val packageName = event.packageName?.toString()?.trim().orEmpty()
    if (packageName.isEmpty()) return

    if (packageName == applicationContext.packageName) {
      cancelRecovery()
      store.disarm("rusty-kiosk-foreground")
      clearActiveConfiguration()
      Log.i(TAG, "status=disarmed reason=rusty-kiosk-foreground")
      return
    }

    val config = reloadConfiguration() ?: return
    val className = event.className?.toString()?.trim().orEmpty()
    val nowMs = SystemClock.elapsedRealtime()
    val homeInvocation =
      event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        GuardContract.isMetaHomeInvocation(packageName, className)
    val decision =
      if (homeInvocation) {
        engine?.observeHomeInvocation(packageName, nowMs)
      } else {
        engine?.observe(packageName, nowMs)
      } ?: return

    when (decision) {
      GuardDecision.NoChange -> Unit
      GuardDecision.CancelRecovery -> cancelRecovery()
      is GuardDecision.ScheduleRecovery -> scheduleRecovery(config, decision.delayMs)
      GuardDecision.DisarmAndReturn -> {
        cancelRecovery()
        store.disarm("triple-home-escape")
        clearActiveConfiguration()
        Log.i(TAG, "status=disarmed reason=triple-home-escape")
        launchReturnToKiosk()
      }
    }
  }

  override fun onInterrupt() {
    cancelRecovery()
    Log.w(TAG, "status=service-interrupted")
  }

  override fun onDestroy() {
    cancelRecovery()
    super.onDestroy()
  }

  private fun reloadConfiguration(): GuardConfig? {
    val config = store.loadArmed()
    if (config == null) {
      if (activeGeneration != null) cancelRecovery()
      clearActiveConfiguration()
      return null
    }
    if (config.generation != activeGeneration) {
      cancelRecovery()
      activeConfig = config
      activeGeneration = config.generation
      engine = GuardDecisionEngine(targetPackage = config.target.packageName)
      Log.i(TAG, "status=configuration-loaded")
    }
    return activeConfig
  }

  private fun scheduleRecovery(config: GuardConfig, delayMs: Long) {
    if (pendingRecovery != null) return
    val generation = config.generation
    val recovery =
      Runnable {
        pendingRecovery = null
        val current = store.loadArmed()
        if (current?.generation != generation) return@Runnable
        if (engine?.currentPackage == current.target.packageName) return@Runnable
        if (launchTarget(current)) {
          engine?.noteTargetRecoveryLaunched(SystemClock.elapsedRealtime())
        }
      }
    pendingRecovery = recovery
    handler.postDelayed(recovery, delayMs)
  }

  private fun launchTarget(config: GuardConfig): Boolean =
    runCatching {
        startActivity(config.toTargetIntent())
        Log.i(TAG, "status=target-recovery-requested")
        true
      }
      .getOrElse { throwable ->
        Log.e(TAG, "status=target-recovery-failed error=${throwable.javaClass.simpleName}")
        false
      }

  private fun launchReturnToKiosk() {
    val intent =
      Intent(GuardContract.ACTION_RETURN_TO_KIOSK, null, this, RustyKioskActivity::class.java)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    runCatching { startActivity(intent) }
      .onFailure { throwable ->
        Log.e(TAG, "status=kiosk-return-failed error=${throwable.javaClass.simpleName}")
      }
  }

  private fun cancelRecovery() {
    pendingRecovery?.let(handler::removeCallbacks)
    pendingRecovery = null
  }

  private fun clearActiveConfiguration() {
    activeGeneration = null
    activeConfig = null
    engine = null
  }

  companion object {
    private const val TAG = "RustyKioskGuard"
  }
}
