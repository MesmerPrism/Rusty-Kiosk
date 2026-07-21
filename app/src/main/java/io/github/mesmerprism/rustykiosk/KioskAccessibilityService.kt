package io.github.mesmerprism.rustykiosk

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class KioskAccessibilityService : AccessibilityService() {
  private val handler = Handler(Looper.getMainLooper())
  private val store by lazy(LazyThreadSafetyMode.NONE) { GuardStateStore(this) }
  private val guardLaunchHandoffLease by lazy(LazyThreadSafetyMode.NONE) {
    GuardLaunchHandoffLease(this)
  }
  private var activeGeneration: Long? = null
  private var activeConfig: GuardConfig? = null
  private var engine: GuardDecisionEngine? = null
  private var pendingRecovery: Runnable? = null
  private var userControlReceiverRegistered = false
  private var debugReceiverRegistered = false
  private val userControlReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_DISABLE_SELF) return
        cancelRecovery()
        guardLaunchHandoffLease.clear()
        store.disarm("user-disabled-accessibility")
        clearActiveConfiguration()
        Log.i(TAG, "status=user-disable-requested")
        disableSelf()
      }
    }
  private val debugReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (!BuildConfig.DEBUG ||
          intent?.action != GuardDebugContract.ACTION_INTERNAL_HOME_TRANSITION
        ) return
        val requestId = intent.getStringExtra(GuardDebugContract.EXTRA_REQUEST_ID) ?: return
        if (!GuardDebugContract.validRequestId(requestId)) return
        handleDebugHomeTransition(requestId)
      }
    }

  override fun onServiceConnected() {
    super.onServiceConnected()
    registerUserControlReceiver()
    registerDebugReceiver()
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
      val armed = store.loadArmed()
      if (armed != null &&
        guardLaunchHandoffLease.shouldIgnoreOwnForeground(armed.target.packageName)
      ) {
        Log.i(TAG, "status=ignored-launch-handoff-self-event")
        return
      }
      guardLaunchHandoffLease.clear()
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

    applyDecision(config, decision)
  }

  private fun applyDecision(config: GuardConfig, decision: GuardDecision) {
    when (decision) {
      GuardDecision.NoChange -> Unit
      GuardDecision.CancelRecovery -> cancelRecovery()
      is GuardDecision.ScheduleRecovery -> scheduleRecovery(config, decision.delayMs)
      GuardDecision.DisarmAndReturn -> {
        cancelRecovery()
        guardLaunchHandoffLease.clear()
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
    unregisterUserControlReceiver()
    unregisterDebugReceiver()
    super.onDestroy()
  }

  private fun registerUserControlReceiver() {
    if (userControlReceiverRegistered) return
    val filter = IntentFilter(ACTION_DISABLE_SELF)
    registerReceiver(userControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    userControlReceiverRegistered = true
  }

  private fun unregisterUserControlReceiver() {
    if (!userControlReceiverRegistered) return
    runCatching { unregisterReceiver(userControlReceiver) }
    userControlReceiverRegistered = false
  }

  private fun registerDebugReceiver() {
    if (!BuildConfig.DEBUG || debugReceiverRegistered) return
    registerReceiver(
      debugReceiver,
      IntentFilter(GuardDebugContract.ACTION_INTERNAL_HOME_TRANSITION),
      Context.RECEIVER_NOT_EXPORTED,
    )
    debugReceiverRegistered = true
  }

  private fun unregisterDebugReceiver() {
    if (!debugReceiverRegistered) return
    runCatching { unregisterReceiver(debugReceiver) }
    debugReceiverRegistered = false
  }

  private fun handleDebugHomeTransition(requestId: String) {
    val config = reloadConfiguration()
    if (config == null) {
      GuardDebugResultStore(this).record(
        requestId = requestId,
        accepted = false,
        decision = "not_armed",
        message = "No kiosk target is armed.",
        guardArmed = false,
      )
      return
    }
    val decision =
      engine?.observeHomeInvocation(
        GuardDebugContract.META_SHELL_PACKAGE,
        SystemClock.elapsedRealtime(),
      ) ?: GuardDecision.NoChange
    applyDecision(config, decision)
    GuardDebugResultStore(this).record(
      requestId = requestId,
      accepted = true,
      decision = decision.wireName(),
      message = "One exact debug watchdog Home transition was applied.",
      guardArmed = store.loadArmed() != null,
    )
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
    private const val ACTION_DISABLE_SELF =
      "io.github.mesmerprism.rustykiosk.action.DISABLE_ACCESSIBILITY_SELF"

    fun requestUserDisable(context: Context) {
      context.sendBroadcast(
        Intent(ACTION_DISABLE_SELF).setPackage(context.applicationContext.packageName)
      )
    }
  }
}

private fun GuardDecision.wireName(): String =
  when (this) {
    GuardDecision.NoChange -> "no_change"
    GuardDecision.CancelRecovery -> "cancel_recovery"
    is GuardDecision.ScheduleRecovery -> "schedule_recovery"
    GuardDecision.DisarmAndReturn -> "disarm_and_return"
  }
