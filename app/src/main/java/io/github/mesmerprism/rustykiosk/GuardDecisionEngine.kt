package io.github.mesmerprism.rustykiosk

internal sealed interface GuardDecision {
  data object NoChange : GuardDecision
  data object CancelRecovery : GuardDecision
  data object IgnoreRepeatedHomeSignal : GuardDecision
  data object IgnoreRepeatedForegroundLossSignal : GuardDecision
  data object IgnoreConfirmedTargetTail : GuardDecision
  data class AwaitInitialHandoff(
    val delayMs: Long,
    val ignoredPackage: String? = null,
  ) : GuardDecision
  data class ScheduleRecovery(
    val delayMs: Long,
    val episodeId: Long,
  ) : GuardDecision
  data object DisarmAndReturn : GuardDecision
}

internal data class GuardRecoveryAttempt(
  val episodeId: Long,
  val attemptNumber: Int,
)

internal class GuardDecisionEngine(
  private val targetPackage: String,
  private val metaShellPackages: Set<String> = GuardContract.metaShellPackages,
  private val gracePeriodMs: Long = GuardContract.RECOVERY_GRACE_MS,
  private val triggerCount: Int = GuardContract.HOME_TRIGGER_COUNT,
  private val triggerWindowMs: Long = GuardContract.HOME_TRIGGER_WINDOW_MS,
  private val homeInvocationDebounceMs: Long = GuardContract.HOME_INVOCATION_DEBOUNCE_MS,
  private val homeSignalRepeatQuietMs: Long = GuardContract.HOME_SIGNAL_REPEAT_QUIET_MS,
  private val initialHandoffQuietMs: Long = GuardContract.INITIAL_HANDOFF_QUIET_MS,
  private val recoveryConfirmationQuietMs: Long =
    GuardContract.RECOVERY_CONFIRMATION_QUIET_MS,
  private val recoveryRetryMinIntervalMs: Long = GuardContract.RECOVERY_RETRY_MIN_INTERVAL_MS,
  private val recoveryMaxAttempts: Int = GuardContract.RECOVERY_MAX_ATTEMPTS,
) {
  private val homeTransitions = ArrayDeque<Long>()
  private var targetObserved = false
  private var initialHandoffPending = true
  private var initialHandoffLastEventMs = Long.MIN_VALUE
  private var lastHomeSignalMs = Long.MIN_VALUE
  private var lastHomeSignalClassName: String? = null
  private var lastForegroundLossSignalMs = Long.MIN_VALUE
  private var recoveryEpisodeId = 0L
  private var recoveryEpisodeStartedMs = Long.MIN_VALUE
  private var recoveryEpisodeConfirmedTarget = false
  private var recoveryEpisodeHomeCounted = false
  private var recoveryAttemptsRequested = 0
  private var lastRecoveryRequestMs = Long.MIN_VALUE
  private var lastTargetConfirmationMs = Long.MIN_VALUE

  var currentPackage: String? = null
    private set

  fun claimRecovery(episodeId: Long, nowMs: Long): GuardRecoveryAttempt? {
    if (
      episodeId != recoveryEpisodeId ||
        currentPackage == targetPackage ||
        recoveryAttemptsRequested >= recoveryMaxAttempts
    ) {
      return null
    }
    recoveryAttemptsRequested += 1
    lastRecoveryRequestMs = nowMs
    return GuardRecoveryAttempt(
      episodeId = recoveryEpisodeId,
      attemptNumber = recoveryAttemptsRequested,
    )
  }

  fun confirmInitialHandoff(nowMs: Long): Boolean {
    if (
      !initialHandoffPending ||
        !targetObserved ||
        initialHandoffLastEventMs == Long.MIN_VALUE ||
        nowMs - initialHandoffLastEventMs < initialHandoffQuietMs
    ) {
      return false
    }
    initialHandoffPending = false
    return true
  }

  fun observeHomeInvocation(
    packageName: String,
    nowMs: Long,
    className: String? = null,
  ): GuardDecision {
    if (initialHandoffPending) return observeInitialHandoffTail(packageName, nowMs)
    if (shouldIgnoreConfirmedTargetTail(nowMs, requireHomeCounted = true)) {
      return GuardDecision.IgnoreConfirmedTargetTail
    }
    if (
      !className.isNullOrEmpty() &&
        className == lastHomeSignalClassName &&
        lastHomeSignalMs != Long.MIN_VALUE &&
        nowMs - lastHomeSignalMs < homeSignalRepeatQuietMs
    ) {
      return GuardDecision.IgnoreRepeatedHomeSignal
    }
    if (!className.isNullOrEmpty()) {
      lastHomeSignalClassName = className
      lastHomeSignalMs = nowMs
    }
    targetObserved = true
    currentPackage = packageName
    if (!isRecoveryEpisodeRecent(nowMs)) {
      beginRecoveryEpisode(nowMs)
    }
    return if (countHomeInvocation(nowMs)) {
      GuardDecision.DisarmAndReturn
    } else {
      nextRecoveryDecision(nowMs)
    }
  }

  fun observeForegroundLoss(nowMs: Long): GuardDecision {
    if (
      lastForegroundLossSignalMs != Long.MIN_VALUE &&
        nowMs - lastForegroundLossSignalMs < GuardContract.DIRECT_SIGNAL_REPEAT_QUIET_MS
    ) {
      return GuardDecision.IgnoreRepeatedForegroundLossSignal
    }
    lastForegroundLossSignalMs = nowMs
    if (shouldIgnoreConfirmedTargetTail(nowMs)) {
      return GuardDecision.IgnoreConfirmedTargetTail
    }
    targetObserved = true
    initialHandoffPending = false
    if (
      recoveryEpisodeId == 0L ||
        !isRecoveryEpisodeRecent(nowMs) ||
        (currentPackage == targetPackage && recoveryEpisodeConfirmedTarget)
    ) {
      beginRecoveryEpisode(nowMs)
    }
    currentPackage = DIRECT_FOREGROUND_LOSS_PACKAGE
    return nextRecoveryDecision(nowMs)
  }

  fun observe(packageName: String, nowMs: Long): GuardDecision {
    if (packageName == targetPackage) {
      val shouldCancelRecovery = currentPackage != targetPackage
      targetObserved = true
      currentPackage = targetPackage
      if (recoveryEpisodeId > 0L) {
        recoveryEpisodeConfirmedTarget = true
      }
      lastTargetConfirmationMs = nowMs
      if (initialHandoffPending) {
        initialHandoffLastEventMs = nowMs
        return GuardDecision.AwaitInitialHandoff(initialHandoffQuietMs)
      }
      return if (shouldCancelRecovery) GuardDecision.CancelRecovery else GuardDecision.NoChange
    }
    if (initialHandoffPending) return observeInitialHandoffTail(packageName, nowMs)
    if (shouldIgnoreConfirmedTargetTail(nowMs)) {
      return GuardDecision.IgnoreConfirmedTargetTail
    }
    if (!targetObserved) {
      currentPackage = packageName
      return GuardDecision.NoChange
    }
    if (packageName == currentPackage) {
      if (!isRecoveryEpisodeRecent(nowMs) && recoveryEpisodeConfirmedTarget) {
        beginRecoveryEpisode(nowMs)
      }
      return if (recoveryEpisodeId > 0L) {
        nextRecoveryDecision(nowMs)
      } else {
        GuardDecision.NoChange
      }
    }
    currentPackage = packageName

    val episodeWasRecent = isRecoveryEpisodeRecent(nowMs)
    if (
      recoveryEpisodeId == 0L ||
        (!episodeWasRecent && recoveryEpisodeConfirmedTarget)
    ) {
      beginRecoveryEpisode(nowMs)
    }

    if (
      !recoveryEpisodeHomeCounted &&
        packageName in metaShellPackages
    ) {
      // This is the generic Home fallback for Horizon versions that do not expose a known
      // Home activity class. A panel that arrived first may already have opened the family.
      if (countHomeInvocation(nowMs)) return GuardDecision.DisarmAndReturn
    }

    return nextRecoveryDecision(nowMs)
  }

  private fun observeInitialHandoffTail(packageName: String, nowMs: Long): GuardDecision {
    if (!targetObserved) {
      currentPackage = packageName
      return GuardDecision.NoChange
    }
    initialHandoffLastEventMs = nowMs
    return GuardDecision.AwaitInitialHandoff(
      delayMs = initialHandoffQuietMs,
      ignoredPackage = packageName,
    )
  }

  private fun beginRecoveryEpisode(nowMs: Long) {
    recoveryEpisodeId += 1L
    recoveryEpisodeStartedMs = nowMs
    recoveryEpisodeConfirmedTarget = false
    recoveryEpisodeHomeCounted = false
    recoveryAttemptsRequested = 0
    lastRecoveryRequestMs = Long.MIN_VALUE
  }

  private fun shouldIgnoreConfirmedTargetTail(
    nowMs: Long,
    requireHomeCounted: Boolean = false,
  ): Boolean =
    currentPackage == targetPackage &&
      (!requireHomeCounted || recoveryEpisodeHomeCounted) &&
      isRecoveryEpisodeRecent(nowMs) &&
      lastTargetConfirmationMs != Long.MIN_VALUE &&
      nowMs - lastTargetConfirmationMs < recoveryConfirmationQuietMs

  private fun isRecoveryEpisodeRecent(nowMs: Long): Boolean =
    recoveryEpisodeStartedMs != Long.MIN_VALUE &&
      nowMs - recoveryEpisodeStartedMs < homeInvocationDebounceMs

  private fun countHomeInvocation(nowMs: Long): Boolean {
    if (recoveryEpisodeHomeCounted) return false
    recoveryEpisodeHomeCounted = true
    pruneHomeTransitions(nowMs)
    homeTransitions.addLast(nowMs)
    return homeTransitions.size >= triggerCount
  }

  private fun nextRecoveryDecision(nowMs: Long): GuardDecision {
    if (recoveryAttemptsRequested >= recoveryMaxAttempts) return GuardDecision.NoChange
    val retryDelayMs =
      if (lastRecoveryRequestMs == Long.MIN_VALUE) {
        0L
      } else {
        (recoveryRetryMinIntervalMs - (nowMs - lastRecoveryRequestMs)).coerceAtLeast(0L)
      }
    return GuardDecision.ScheduleRecovery(
      delayMs = maxOf(gracePeriodMs, retryDelayMs),
      episodeId = recoveryEpisodeId,
    )
  }

  private fun pruneHomeTransitions(nowMs: Long) {
    while (homeTransitions.isNotEmpty() && nowMs - homeTransitions.first() > triggerWindowMs) {
      homeTransitions.removeFirst()
    }
  }

  private companion object {
    const val DIRECT_FOREGROUND_LOSS_PACKAGE =
      "io.github.mesmerprism.rustykiosk.direct-foreground-loss"
  }
}
