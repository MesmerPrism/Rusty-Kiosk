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
  private var pendingInitialHandoffConfirmation: Runnable? = null
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
    ForegroundSignalRouter.attach(this)
    registerUserControlReceiver()
    registerDebugReceiver()
    reloadConfiguration()
    Log.i(
      TAG,
      "status=service-connected readsUiContent=false eventTypes=window-state-changed,windows-changed",
    )
  }

  internal fun enqueueForegroundSignal(signal: ForegroundSignal) {
    handler.post { handleForegroundSignal(signal) }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val packageName = event.packageName?.toString()?.trim().orEmpty()
    if (packageName.isEmpty()) return
    val eventType = event.eventType
    val windowChanges =
      if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0
    if (!GuardWindowEventPolicy.shouldObserve(eventType, windowChanges)) {
      Log.i(
        TAG,
        "status=window-event-ignored reason=non-focus-change " +
          "event_type=${GuardWindowEventPolicy.eventTypeName(eventType)} " +
          "window_changes=$windowChanges package=$packageName",
      )
      return
    }

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
    val telemetry =
      GuardEventTelemetry(
        signalAtMs = nowMs,
        eventAgeMs = (nowMs - event.eventTime).coerceAtLeast(0L),
        source = "accessibility",
        eventType = eventType,
        windowChanges = windowChanges,
        packageName = packageName,
        className = className,
        exactHome = homeInvocation,
      )
    val decision =
      if (homeInvocation) {
        engine?.observeHomeInvocation(packageName, nowMs, className)
      } else {
        engine?.observe(packageName, nowMs)
      } ?: return

    Log.i(
      TAG,
        "status=window-event-observed source=${telemetry.source} " +
        "event_type=${telemetry.eventTypeName} " +
        "window_changes=${telemetry.windowChanges} event_age_ms=${telemetry.eventAgeMs} " +
        "package=${telemetry.packageName} " +
        "class=${telemetry.className.ifEmpty { "-" }} exact_home=${telemetry.exactHome} " +
        "decision=${decision.wireName()}${decision.telemetrySuffix()}",
    )
    applyDecision(config, decision, telemetry)
  }

  private fun applyDecision(
    config: GuardConfig,
    decision: GuardDecision,
    telemetry: GuardEventTelemetry? = null,
  ) {
    when (decision) {
      GuardDecision.NoChange -> Unit
      GuardDecision.IgnoreRepeatedHomeSignal -> Unit
      GuardDecision.IgnoreRepeatedForegroundLossSignal -> Unit
      GuardDecision.IgnoreConfirmedTargetTail -> Unit
      GuardDecision.CancelRecovery -> cancelRecovery()
      is GuardDecision.AwaitInitialHandoff -> {
        cancelRecovery()
        decision.ignoredPackage?.let { packageName ->
          Log.i(TAG, "status=ignored-initial-handoff-tail package=$packageName")
        }
        scheduleInitialHandoffConfirmation(config, decision.delayMs)
      }
      is GuardDecision.ScheduleRecovery -> {
        scheduleRecovery(config, decision, telemetry)
      }
      GuardDecision.DisarmAndReturn -> {
        cancelRecovery()
        guardLaunchHandoffLease.clear()
        store.disarm("triple-home-escape")
        clearActiveConfiguration()
        val signalToDisarmMs =
          telemetry?.let { (SystemClock.elapsedRealtime() - it.signalAtMs).coerceAtLeast(0L) }
        Log.i(
          TAG,
          "status=disarmed reason=triple-home-escape " +
            "signal_to_disarm_ms=${signalToDisarmMs ?: -1}",
        )
        launchReturnToKiosk()
      }
    }
  }

  override fun onInterrupt() {
    cancelRecovery()
    cancelInitialHandoffConfirmation()
    Log.w(TAG, "status=service-interrupted")
  }

  override fun onDestroy() {
    cancelRecovery()
    cancelInitialHandoffConfirmation()
    unregisterUserControlReceiver()
    unregisterDebugReceiver()
    ForegroundSignalRouter.detach(this)
    super.onDestroy()
  }

  override fun onUnbind(intent: Intent?): Boolean {
    ForegroundSignalRouter.detach(this)
    return super.onUnbind(intent)
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

  private fun handleForegroundSignal(signal: ForegroundSignal) {
    val config = reloadConfiguration()
    if (
      config == null ||
        config.generation != signal.generation ||
        config.target.packageName != signal.callerPackage ||
        config.foregroundSignalProtocolVersion == null
    ) {
      Log.i(TAG, "status=direct-signal-dropped reason=configuration-changed")
      return
    }
    val decision =
      engine?.observeForegroundLoss(signal.receivedAtMs) ?: GuardDecision.NoChange
    val telemetry =
      GuardEventTelemetry(
        signalAtMs = signal.receivedAtMs,
        eventAgeMs = signal.transportLatencyMs ?: -1L,
        source = "direct-app",
        eventType = FOREGROUND_LOSS_SIGNAL_EVENT_TYPE,
        windowChanges = 0,
        packageName = signal.callerPackage,
        className = signal.source,
        exactHome = false,
      )
    Log.i(
      TAG,
      "status=foreground-loss-signal-observed source=${telemetry.source} " +
        "signal_source=${signal.source} protocol=${signal.protocolVersion} " +
        "transport_latency_ms=${telemetry.eventAgeMs} package=${telemetry.packageName} " +
        "decision=${decision.wireName()}${decision.telemetrySuffix()}",
    )
    applyDecision(config, decision, telemetry)
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
      cancelInitialHandoffConfirmation()
      activeConfig = config
      activeGeneration = config.generation
      engine = GuardDecisionEngine(targetPackage = config.target.packageName)
      Log.i(TAG, "status=configuration-loaded")
      Log.i(
        TAG,
        if (config.foregroundSignalProtocolVersion != null) {
          "status=foreground-signal-mode mode=direct-preferred-accessibility-fallback " +
            "protocol=${config.foregroundSignalProtocolVersion}"
        } else {
          "status=foreground-signal-mode mode=accessibility-fallback-only"
        },
      )
    }
    return activeConfig
  }

  private fun scheduleInitialHandoffConfirmation(config: GuardConfig, delayMs: Long) {
    cancelInitialHandoffConfirmation()
    val generation = config.generation
    val confirmation =
      Runnable {
        pendingInitialHandoffConfirmation = null
        val current = store.loadArmed()
        if (current?.generation != generation) return@Runnable
        if (engine?.confirmInitialHandoff(SystemClock.elapsedRealtime()) == true) {
          Log.i(TAG, "status=initial-handoff-confirmed")
        }
      }
    pendingInitialHandoffConfirmation = confirmation
    handler.postDelayed(confirmation, delayMs)
  }

  private fun scheduleRecovery(
    config: GuardConfig,
    decision: GuardDecision.ScheduleRecovery,
    telemetry: GuardEventTelemetry?,
  ) {
    if (pendingRecovery != null) {
      Log.i(
        TAG,
        "status=target-recovery-coalesced episode=${decision.episodeId} " +
          "event_type=${telemetry?.eventTypeName ?: "internal"}",
      )
      return
    }
    val generation = config.generation
    val recovery =
      Runnable {
        pendingRecovery = null
        val current = store.loadArmed()
        if (current?.generation != generation) return@Runnable
        if (engine?.currentPackage == current.target.packageName) return@Runnable
        val attempt =
          engine?.claimRecovery(
            episodeId = decision.episodeId,
            nowMs = SystemClock.elapsedRealtime(),
          )
        if (attempt == null) {
          Log.i(TAG, "status=target-recovery-skipped episode=${decision.episodeId}")
          return@Runnable
        }
        launchTarget(current, attempt, telemetry)
      }
    pendingRecovery = recovery
    handler.postDelayed(recovery, decision.delayMs)
  }

  private fun launchTarget(
    config: GuardConfig,
    attempt: GuardRecoveryAttempt,
    telemetry: GuardEventTelemetry?,
  ): Boolean =
    runCatching {
        startActivity(config.toTargetIntent())
        val signalToRequestMs =
          telemetry?.let { (SystemClock.elapsedRealtime() - it.signalAtMs).coerceAtLeast(0L) }
        Log.i(
          TAG,
          "status=target-recovery-requested episode=${attempt.episodeId} " +
            "attempt=${attempt.attemptNumber} signal_to_request_ms=${signalToRequestMs ?: -1} " +
            "event_type=${telemetry?.eventTypeName ?: "internal"}",
        )
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

  private fun cancelInitialHandoffConfirmation() {
    pendingInitialHandoffConfirmation?.let(handler::removeCallbacks)
    pendingInitialHandoffConfirmation = null
  }

  private fun clearActiveConfiguration() {
    cancelInitialHandoffConfirmation()
    activeGeneration = null
    activeConfig = null
    engine = null
  }

  companion object {
    private const val TAG = "RustyKioskGuard"
    private const val FOREGROUND_LOSS_SIGNAL_EVENT_TYPE = -1
    private const val ACTION_DISABLE_SELF =
      "io.github.mesmerprism.rustykiosk.action.DISABLE_ACCESSIBILITY_SELF"

    fun requestUserDisable(context: Context) {
      context.sendBroadcast(
        Intent(ACTION_DISABLE_SELF).setPackage(context.applicationContext.packageName)
      )
    }
  }
}

private data class GuardEventTelemetry(
  val signalAtMs: Long,
  val eventAgeMs: Long,
  val source: String,
  val eventType: Int,
  val windowChanges: Int,
  val packageName: String,
  val className: String,
  val exactHome: Boolean,
) {
  val eventTypeName: String
    get() =
      if (eventType == -1) {
        "foreground-loss-signal"
      } else {
        GuardWindowEventPolicy.eventTypeName(eventType)
      }
}

private fun GuardDecision.wireName(): String =
  when (this) {
    GuardDecision.NoChange -> "no_change"
    GuardDecision.IgnoreRepeatedHomeSignal -> "ignore_repeated_home_signal"
    GuardDecision.IgnoreRepeatedForegroundLossSignal ->
      "ignore_repeated_foreground_loss_signal"
    GuardDecision.IgnoreConfirmedTargetTail -> "ignore_confirmed_target_tail"
    GuardDecision.CancelRecovery -> "cancel_recovery"
    is GuardDecision.AwaitInitialHandoff -> "await_initial_handoff"
    is GuardDecision.ScheduleRecovery -> "schedule_recovery"
    GuardDecision.DisarmAndReturn -> "disarm_and_return"
  }

private fun GuardDecision.telemetrySuffix(): String =
  when (this) {
    is GuardDecision.ScheduleRecovery -> " recovery_episode=$episodeId"
    else -> ""
  }
