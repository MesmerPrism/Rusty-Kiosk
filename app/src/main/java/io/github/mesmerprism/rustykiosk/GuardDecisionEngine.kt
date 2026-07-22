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
  private val recoveryRetryMinIntervalMs: Long = GuardContract.RECOVERY_RETRY_MIN_INTERVAL_MS,
  private val recoveryMaxAttempts: Int = GuardContract.RECOVERY_MAX_ATTEMPTS,
) {
  private val homeTransitions = ArrayDeque<Long>()
  private var targetObserved = false
  private var lastHomeInvocationMs = Long.MIN_VALUE
  private var recoveryBurstActive = false
  private var recoveryAttemptsRequested = 0
  private var lastRecoveryRequestMs = Long.MIN_VALUE

  var currentPackage: String? = null
    private set

  fun noteRecoveryRequested(nowMs: Long) {
    if (!recoveryBurstActive || recoveryAttemptsRequested >= recoveryMaxAttempts) return
    recoveryAttemptsRequested += 1
    lastRecoveryRequestMs = nowMs
  }

  fun observeHomeInvocation(packageName: String, nowMs: Long): GuardDecision {
    if (
      lastHomeInvocationMs != Long.MIN_VALUE &&
        nowMs - lastHomeInvocationMs < homeInvocationDebounceMs
    ) {
      currentPackage = packageName
      recoveryBurstActive = true
      return nextRecoveryDecision(nowMs)
    }

    lastHomeInvocationMs = nowMs
    targetObserved = true
    currentPackage = packageName
    beginRecoveryBurst()
    pruneHomeTransitions(nowMs)
    homeTransitions.addLast(nowMs)
    return if (homeTransitions.size >= triggerCount) {
      recoveryBurstActive = false
      GuardDecision.DisarmAndReturn
    } else {
      nextRecoveryDecision(nowMs)
    }
  }

  fun observe(packageName: String, nowMs: Long): GuardDecision {
    if (packageName == targetPackage) {
      val shouldCancelRecovery = currentPackage != targetPackage || recoveryBurstActive
      targetObserved = true
      currentPackage = targetPackage
      recoveryBurstActive = false
      return if (shouldCancelRecovery) GuardDecision.CancelRecovery else GuardDecision.NoChange
    }
    if (
      packageName in metaShellPackages &&
        lastHomeInvocationMs != Long.MIN_VALUE &&
        nowMs - lastHomeInvocationMs < homeInvocationDebounceMs
    ) {
      // An exact Home signal already counted this invocation. Horizon can finish opening a
      // shell window after recovery, so request recovery without counting the event burst twice.
      currentPackage = packageName
      recoveryBurstActive = true
      return nextRecoveryDecision(nowMs)
    }
    if (!targetObserved) {
      currentPackage = packageName
      return GuardDecision.NoChange
    }
    if (packageName == currentPackage) {
      return if (recoveryBurstActive) nextRecoveryDecision(nowMs) else GuardDecision.NoChange
    }
    val previousPackage = currentPackage
    currentPackage = packageName

    if (previousPackage == targetPackage && packageName in metaShellPackages) {
      beginRecoveryBurst()
      pruneHomeTransitions(nowMs)
      // This is the generic target-to-shell fallback for Horizon versions that do not expose
      // a known Home activity class. Debounce a later exact event from the same physical press.
      lastHomeInvocationMs = nowMs
      homeTransitions.addLast(nowMs)
      if (homeTransitions.size >= triggerCount) {
        recoveryBurstActive = false
        return GuardDecision.DisarmAndReturn
      }
    } else if (previousPackage == targetPackage || !recoveryBurstActive) {
      beginRecoveryBurst()
    }

    return nextRecoveryDecision(nowMs)
  }

  private fun beginRecoveryBurst() {
    recoveryBurstActive = true
    recoveryAttemptsRequested = 0
    lastRecoveryRequestMs = Long.MIN_VALUE
  }

  private fun nextRecoveryDecision(nowMs: Long): GuardDecision {
    if (recoveryAttemptsRequested >= recoveryMaxAttempts) return GuardDecision.NoChange
    val retryDelayMs =
      if (lastRecoveryRequestMs == Long.MIN_VALUE) {
        0L
      } else {
        (recoveryRetryMinIntervalMs - (nowMs - lastRecoveryRequestMs)).coerceAtLeast(0L)
      }
    return GuardDecision.ScheduleRecovery(maxOf(gracePeriodMs, retryDelayMs))
  }

  private fun pruneHomeTransitions(nowMs: Long) {
    while (homeTransitions.isNotEmpty() && nowMs - homeTransitions.first() > triggerWindowMs) {
      homeTransitions.removeFirst()
    }
  }
}
