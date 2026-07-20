package io.github.mesmerprism.rustykiosk

internal sealed interface GuardDecision {
  data object NoChange : GuardDecision
  data object CancelRecovery : GuardDecision
  data class ScheduleRecovery(val delayMs: Long) : GuardDecision
  data object DisarmAndReturn : GuardDecision
}

internal class GuardDecisionEngine(
  private val targetPackage: String,
  private val metaShellPackages: Set<String> = GuardContract.metaShellPackages,
  private val gracePeriodMs: Long = GuardContract.RECOVERY_GRACE_MS,
  private val triggerCount: Int = GuardContract.HOME_TRIGGER_COUNT,
  private val triggerWindowMs: Long = GuardContract.HOME_TRIGGER_WINDOW_MS,
  private val homeInvocationDebounceMs: Long = GuardContract.HOME_INVOCATION_DEBOUNCE_MS,
  private val recoverySettleMs: Long = GuardContract.HOME_RECOVERY_SETTLE_MS,
) {
  private val homeTransitions = ArrayDeque<Long>()
  private var targetObserved = false
  private var lastHomeInvocationMs = Long.MIN_VALUE
  private var ignoreNonTargetUntilMs = Long.MIN_VALUE

  var currentPackage: String? = null
    private set

  fun noteTargetRecoveryLaunched(nowMs: Long) {
    targetObserved = true
    currentPackage = targetPackage
    ignoreNonTargetUntilMs = nowMs + recoverySettleMs
  }

  fun observeHomeInvocation(packageName: String, nowMs: Long): GuardDecision {
    if (
      lastHomeInvocationMs != Long.MIN_VALUE &&
        nowMs - lastHomeInvocationMs < homeInvocationDebounceMs
    ) {
      return GuardDecision.NoChange
    }

    lastHomeInvocationMs = nowMs
    targetObserved = true
    currentPackage = packageName
    pruneHomeTransitions(nowMs)
    homeTransitions.addLast(nowMs)
    return if (homeTransitions.size >= triggerCount) {
      GuardDecision.DisarmAndReturn
    } else {
      GuardDecision.ScheduleRecovery(gracePeriodMs)
    }
  }

  fun observe(packageName: String, nowMs: Long): GuardDecision {
    if (packageName != targetPackage && nowMs < ignoreNonTargetUntilMs) {
      return GuardDecision.NoChange
    }
    if (
      packageName in metaShellPackages &&
        lastHomeInvocationMs != Long.MIN_VALUE &&
        nowMs - lastHomeInvocationMs < homeInvocationDebounceMs
    ) {
      // An exact Home signal already counted this invocation. Horizon can finish opening a
      // shell window after recovery, so request recovery without counting the event burst twice.
      currentPackage = packageName
      return GuardDecision.ScheduleRecovery(gracePeriodMs)
    }
    if (packageName == currentPackage) return GuardDecision.NoChange
    val previousPackage = currentPackage
    currentPackage = packageName

    if (packageName == targetPackage) {
      targetObserved = true
      return GuardDecision.CancelRecovery
    }
    if (!targetObserved) return GuardDecision.NoChange

    if (previousPackage == targetPackage && packageName in metaShellPackages) {
      pruneHomeTransitions(nowMs)
      // This is the generic target-to-shell fallback for Horizon versions that do not expose
      // a known Home activity class. Debounce a later exact event from the same physical press.
      lastHomeInvocationMs = nowMs
      homeTransitions.addLast(nowMs)
      if (homeTransitions.size >= triggerCount) return GuardDecision.DisarmAndReturn
    }

    return GuardDecision.ScheduleRecovery(gracePeriodMs)
  }

  private fun pruneHomeTransitions(nowMs: Long) {
    while (homeTransitions.isNotEmpty() && nowMs - homeTransitions.first() > triggerWindowMs) {
      homeTransitions.removeFirst()
    }
  }
}
