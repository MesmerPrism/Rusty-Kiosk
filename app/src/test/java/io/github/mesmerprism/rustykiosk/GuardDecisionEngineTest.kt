package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDecisionEngineTest {
  private val targetPackage = "com.example.target"
  private val homePackage = "com.oculus.vrshell"
  private val libraryPackage = "com.oculus.panelapp.library"

  @Test
  fun ignoresDriftUntilTargetHasBeenObserved() {
    val engine = GuardDecisionEngine(targetPackage)

    assertEquals(GuardDecision.NoChange, engine.observe(homePackage, 100L))
  }

  @Test
  fun initialShellTailsExtendAQuietPeriodWithoutStartingRecovery() {
    val engine = GuardDecisionEngine(targetPackage, initialHandoffQuietMs = 160L)

    assertEquals(
      GuardDecision.AwaitInitialHandoff(160L),
      engine.observe(targetPackage, 0L),
    )
    assertEquals(
      GuardDecision.AwaitInitialHandoff(160L, homePackage),
      engine.observeHomeInvocation(homePackage, 100L),
    )
    assertEquals(
      GuardDecision.AwaitInitialHandoff(160L, libraryPackage),
      engine.observe(libraryPackage, 200L),
    )
    assertTrue(!engine.confirmInitialHandoff(359L))
    assertTrue(engine.confirmInitialHandoff(360L))
    assertTrue(engine.observeHomeInvocation(homePackage, 500L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun firstTwoHomeInvocationsRecoverAndThirdReturnsToKiosk() {
    val engine = readyEngine()

    claim(engine, engine.observeHomeInvocation(homePackage, 1_000L), 1_000L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_100L))
    claim(engine, engine.observeHomeInvocation(homePackage, 2_500L), 2_500L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_600L))
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 4_000L))
  }

  @Test
  fun foregroundLossSignalStartsRecoveryBeforeAnyAccessibilityShellEvent() {
    val engine = readyEngine()

    val direct = engine.observeForegroundLoss(1_000L)
    val attempt = claim(engine, direct, 1_001L)

    assertEquals(1, attempt.attemptNumber)
    assertTrue(engine.currentPackage?.contains("direct-foreground-loss") == true)
  }

  @Test
  fun foregroundLossAndAccessibilityHomeShareRecoveryButOnlyHomeCountsEscape() {
    val engine = readyEngine()

    claim(engine, engine.observeForegroundLoss(1_000L), 1_001L)
    assertTrue(engine.observeHomeInvocation(homePackage, 1_100L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_200L))

    claim(engine, engine.observeForegroundLoss(2_400L), 2_401L)
    assertTrue(engine.observeHomeInvocation(homePackage, 2_500L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_700L))

    claim(engine, engine.observeForegroundLoss(3_800L), 3_801L)
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 3_900L))
  }

  @Test
  fun foregroundLossSignalsNeverBecomeTripleHomeEscape() {
    val engine = GuardDecisionEngine(targetPackage)

    claim(engine, engine.observeForegroundLoss(1_000L), 1_001L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_200L))
    claim(engine, engine.observeForegroundLoss(2_300L), 2_301L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_500L))
    claim(engine, engine.observeForegroundLoss(3_600L), 3_601L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 3_800L))
    assertTrue(engine.observeForegroundLoss(4_900L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun lateForegroundLossAfterAccessibilityRecoveryCannotStartAnotherRecovery() {
    val engine = readyEngine()

    claim(engine, engine.observeHomeInvocation(homePackage, 1_000L), 1_001L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_100L))
    assertEquals(
      GuardDecision.IgnoreConfirmedTargetTail,
      engine.observeForegroundLoss(1_110L),
    )
  }

  @Test
  fun oneHomeEventBurstCountsOnce() {
    val engine = readyEngine()

    claim(engine, engine.observeHomeInvocation(homePackage, 1_000L), 1_000L)
    claim(engine, engine.observeHomeInvocation(homePackage, 1_300L), 1_300L)
    assertTrue(engine.observeHomeInvocation(homePackage, 1_700L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_800L))
    assertTrue(engine.observeHomeInvocation(homePackage, 2_300L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_400L))
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 3_600L))
  }

  @Test
  fun identicalExactSignalsInsideSixtyFourMillisecondsDoNotSpendARetry() {
    val engine = readyEngine()
    val homeActivity = "com.oculus.vrshell.HomeActivity"
    val focusPlaceholder = "com.oculus.vrshell.FocusPlaceholderActivity"

    val first = engine.observeHomeInvocation(homePackage, 1_000L, homeActivity)
    assertEquals(1, claim(engine, first, 1_000L).attemptNumber)
    assertEquals(
      GuardDecision.IgnoreRepeatedHomeSignal,
      engine.observeHomeInvocation(homePackage, 1_040L, homeActivity),
    )

    val retry = engine.observeHomeInvocation(homePackage, 1_050L, focusPlaceholder)
    assertTrue(retry is GuardDecision.ScheduleRecovery)
    retry as GuardDecision.ScheduleRecovery
    assertEquals((first as GuardDecision.ScheduleRecovery).episodeId, retry.episodeId)
    assertEquals(2, engine.claimRecovery(retry.episodeId, 1_096L)?.attemptNumber)
  }

  @Test
  fun genericHomeEdgeAndExactEventFromOnePressCountOnce() {
    val engine = readyEngine()

    claim(engine, engine.observe(homePackage, 100L), 100L)
    assertTrue(engine.observeHomeInvocation(homePackage, 200L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 500L))
    claim(engine, engine.observeHomeInvocation(homePackage, 1_400L), 1_500L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_600L))
    assertEquals(
      GuardDecision.DisarmAndReturn,
      engine.observeHomeInvocation(homePackage, 2_700L),
    )
  }

  @Test
  fun shellWindowTailAfterRecoveryRequestsRefocusWithoutCreatingFalseHomePress() {
    val engine = readyEngine()
    claim(engine, engine.observe(homePackage, 100L), 200L)

    claim(engine, engine.observe(libraryPackage, 210L), 296L)
    claim(engine, engine.observe(homePackage, 300L), 392L)
    assertEquals(GuardDecision.NoChange, engine.observe(libraryPackage, 500L))
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 600L))
    assertTrue(engine.observeHomeInvocation(homePackage, 1_500L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_600L))
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 2_800L))
  }

  @Test
  fun targetConfirmationQuietPeriodSuppressesLateHomeBurstBeforeAllowingRetry() {
    val engine =
      GuardDecisionEngine(
        targetPackage = targetPackage,
        recoveryConfirmationQuietMs = 160L,
      ).also { candidate ->
        assertTrue(candidate.observe(targetPackage, 0L) is GuardDecision.AwaitInitialHandoff)
        assertTrue(candidate.confirmInitialHandoff(GuardContract.INITIAL_HANDOFF_QUIET_MS))
      }
    val first = engine.observeHomeInvocation(homePackage, 1_000L)
    val firstAttempt = claim(engine, first, 1_000L)
    assertEquals(1, firstAttempt.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_020L))

    assertEquals(
      GuardDecision.IgnoreConfirmedTargetTail,
      engine.observe("com.meta.systemui", 1_021L),
    )
    assertEquals(
      GuardDecision.IgnoreConfirmedTargetTail,
      engine.observeHomeInvocation(homePackage, 1_040L),
    )
    assertEquals(targetPackage, engine.currentPackage)

    val retry = engine.observe(libraryPackage, 1_181L)
    assertTrue(retry is GuardDecision.ScheduleRecovery)
    retry as GuardDecision.ScheduleRecovery
    assertEquals((first as GuardDecision.ScheduleRecovery).episodeId, retry.episodeId)
    assertEquals(2, engine.claimRecovery(retry.episodeId, 1_181L)?.attemptNumber)
  }

  @Test
  fun escapeWindowResets() {
    val engine = readyEngine()

    assertTrue(engine.observeHomeInvocation(homePackage, 1_000L) is GuardDecision.ScheduleRecovery)
    assertTrue(engine.observeHomeInvocation(homePackage, 7_000L) is GuardDecision.ScheduleRecovery)
    assertTrue(engine.observeHomeInvocation(homePackage, 13_000L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun unrelatedAppDriftRecoversWithoutAdvancingHomeEscape() {
    val engine = readyEngine()

    claim(engine, engine.observe("com.example.other", 1_000L), 1_000L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_100L))
    assertTrue(engine.observeHomeInvocation(homePackage, 2_500L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun ordinaryAppTransitionsNeverTriggerHomeEscape() {
    val engine = readyEngine()

    repeat(3) { index ->
      engine.observe(targetPackage, (index * 2_000).toLong())
      assertTrue(
        engine.observe("com.example.other$index", (index * 2_000 + 100).toLong()) is
          GuardDecision.ScheduleRecovery
      )
    }
  }

  private fun readyEngine(): GuardDecisionEngine =
    GuardDecisionEngine(targetPackage).also { engine ->
      assertTrue(engine.observe(targetPackage, 0L) is GuardDecision.AwaitInitialHandoff)
      assertTrue(engine.confirmInitialHandoff(GuardContract.INITIAL_HANDOFF_QUIET_MS))
    }

  private fun claim(
    engine: GuardDecisionEngine,
    decision: GuardDecision,
    nowMs: Long,
  ): GuardRecoveryAttempt {
    assertTrue(decision is GuardDecision.ScheduleRecovery)
    decision as GuardDecision.ScheduleRecovery
    return engine.claimRecovery(decision.episodeId, nowMs).also(::assertNotNull)!!
  }
}
