package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardRecoveryRaceTest {
  private val targetPackage = "com.example.target"
  private val homePackage = "com.oculus.vrshell"
  private val libraryPackage = "com.oculus.panelapp.library"

  @Test
  fun acceptedLaunchRequestDoesNotPretendTargetFocusWasObserved() {
    val engine = readyEngine()
    val first = engine.observeHomeInvocation(homePackage, 1_000L) as GuardDecision.ScheduleRecovery

    assertEquals(1, engine.claimRecovery(first.episodeId, 1_005L)?.attemptNumber)
    assertEquals(homePackage, engine.currentPackage)
    assertTrue(engine.observe(libraryPackage, 1_010L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun retryEpisodeUsesMinimumSpacingAndRejectsAFourthClaim() {
    val engine = readyEngine(retryMs = 96L)
    val first = engine.observeHomeInvocation(homePackage, 1_000L) as GuardDecision.ScheduleRecovery
    assertEquals(GuardDecision.ScheduleRecovery(0L, first.episodeId), first)
    assertEquals(1, engine.claimRecovery(first.episodeId, 1_000L)?.attemptNumber)

    val second = engine.observe(libraryPackage, 1_010L) as GuardDecision.ScheduleRecovery
    assertEquals(GuardDecision.ScheduleRecovery(86L, first.episodeId), second)
    assertEquals(2, engine.claimRecovery(second.episodeId, 1_096L)?.attemptNumber)

    val third = engine.observe(homePackage, 1_100L) as GuardDecision.ScheduleRecovery
    assertEquals(GuardDecision.ScheduleRecovery(92L, first.episodeId), third)
    assertEquals(3, engine.claimRecovery(third.episodeId, 1_192L)?.attemptNumber)

    assertEquals(GuardDecision.NoChange, engine.observe(libraryPackage, 1_300L))
    assertNull(engine.claimRecovery(first.episodeId, 1_300L))
  }

  @Test
  fun defaultRetrySpacingLeavesOneTargetConfirmationWindow() {
    val engine = readyEngine()
    val first = engine.observeHomeInvocation(homePackage, 1_000L) as
      GuardDecision.ScheduleRecovery
    assertEquals(1, engine.claimRecovery(first.episodeId, 1_000L)?.attemptNumber)

    val retry = engine.observe(libraryPackage, 1_010L) as GuardDecision.ScheduleRecovery
    assertEquals(GuardDecision.ScheduleRecovery(150L, first.episodeId), retry)
  }

  @Test
  fun staleScheduledCallbackCannotLaunchAfterTargetConfirmationOrNewEpisode() {
    val engine = readyEngine()
    val stale = engine.observeHomeInvocation(homePackage, 1_000L) as GuardDecision.ScheduleRecovery

    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_020L))
    assertNull(engine.claimRecovery(stale.episodeId, 1_030L))

    val fresh = engine.observeHomeInvocation(homePackage, 2_300L) as GuardDecision.ScheduleRecovery
    assertTrue(fresh.episodeId > stale.episodeId)
    assertNull(engine.claimRecovery(stale.episodeId, 2_301L))
    assertEquals(1, engine.claimRecovery(fresh.episodeId, 2_301L)?.attemptNumber)
  }

  @Test
  fun lateShellTailsReuseTheSameEpisodeAcrossTargetConfirmations() {
    val engine = readyEngine(retryMs = 32L)
    val first = engine.observeHomeInvocation(homePackage, 1_000L) as GuardDecision.ScheduleRecovery
    assertEquals(1, engine.claimRecovery(first.episodeId, 1_000L)?.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_010L))

    assertEquals(
      GuardDecision.IgnoreConfirmedTargetTail,
      engine.observe(libraryPackage, 1_020L),
    )
    val second = engine.observe(libraryPackage, 1_171L) as GuardDecision.ScheduleRecovery
    assertEquals(first.episodeId, second.episodeId)
    assertEquals(2, engine.claimRecovery(second.episodeId, 1_171L)?.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_180L))

    assertEquals(
      GuardDecision.IgnoreConfirmedTargetTail,
      engine.observe(homePackage, 1_190L),
    )
    val third = engine.observe(homePackage, 1_341L) as GuardDecision.ScheduleRecovery
    assertEquals(first.episodeId, third.episodeId)
    assertEquals(3, engine.claimRecovery(third.episodeId, 1_341L)?.attemptNumber)
    assertEquals(GuardDecision.NoChange, engine.observe(libraryPackage, 1_500L))
  }

  @Test
  fun sharedModePanelSystemUiAndExactHomeShareOneBoundedRecoveryFamily() {
    val engine = readyEngine(retryMs = 0L)
    val managedPanelPackage = "com.oculus.panelapp.kiosk"
    val systemUiPackage = "com.meta.systemui"
    val focusPlaceholder = "com.oculus.vrshell.FocusPlaceholderActivity"

    val first =
      engine.observe(managedPanelPackage, 1_000L) as GuardDecision.ScheduleRecovery
    assertEquals(1, engine.claimRecovery(first.episodeId, 1_000L)?.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_020L))

    assertEquals(
      GuardDecision.IgnoreConfirmedTargetTail,
      engine.observe(systemUiPackage, 1_021L),
    )

    val exactHome =
      engine.observeHomeInvocation(
        homePackage,
        1_040L,
        focusPlaceholder,
      ) as GuardDecision.ScheduleRecovery
    assertEquals(first.episodeId, exactHome.episodeId)
    assertEquals(2, engine.claimRecovery(exactHome.episodeId, 1_040L)?.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_050L))

    val latePanel = engine.observe(managedPanelPackage, 1_211L) as GuardDecision.ScheduleRecovery
    assertEquals(first.episodeId, latePanel.episodeId)
    assertEquals(3, engine.claimRecovery(latePanel.episodeId, 1_211L)?.attemptNumber)
    assertEquals(GuardDecision.NoChange, engine.observe(libraryPackage, 1_212L))
    assertNull(engine.claimRecovery(first.episodeId, 1_211L))

    val second =
      engine.observe(managedPanelPackage, 2_301L) as GuardDecision.ScheduleRecovery
    assertTrue(second.episodeId > first.episodeId)
    assertEquals(1, engine.claimRecovery(second.episodeId, 2_301L)?.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_320L))
    val secondExact =
      engine.observeHomeInvocation(
        homePackage,
        2_340L,
        focusPlaceholder,
      ) as GuardDecision.ScheduleRecovery
    assertEquals(second.episodeId, secondExact.episodeId)
    assertEquals(2, engine.claimRecovery(secondExact.episodeId, 2_340L)?.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_350L))

    val third =
      engine.observe(managedPanelPackage, 3_602L) as GuardDecision.ScheduleRecovery
    assertTrue(third.episodeId > second.episodeId)
    assertEquals(1, engine.claimRecovery(third.episodeId, 3_602L)?.attemptNumber)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 3_620L))
    assertEquals(
      GuardDecision.DisarmAndReturn,
      engine.observeHomeInvocation(homePackage, 3_640L, focusPlaceholder),
    )
  }

  @Test
  fun unconfirmedGenericTailsCannotResetAnExhaustedRecoveryFamily() {
    val engine = readyEngine(retryMs = 0L)
    val first =
      engine.observe("com.oculus.panelapp.kiosk", 1_000L) as
        GuardDecision.ScheduleRecovery
    assertEquals(1, engine.claimRecovery(first.episodeId, 1_000L)?.attemptNumber)
    val second = engine.observe(libraryPackage, 1_100L) as GuardDecision.ScheduleRecovery
    assertEquals(first.episodeId, second.episodeId)
    assertEquals(2, engine.claimRecovery(second.episodeId, 1_100L)?.attemptNumber)
    val third = engine.observe(homePackage, 1_200L) as GuardDecision.ScheduleRecovery
    assertEquals(first.episodeId, third.episodeId)
    assertEquals(3, engine.claimRecovery(third.episodeId, 1_200L)?.attemptNumber)

    assertEquals(GuardDecision.NoChange, engine.observe("com.meta.systemui", 2_500L))
    assertNull(engine.claimRecovery(first.episodeId, 2_500L))
  }

  private fun readyEngine(retryMs: Long = GuardContract.RECOVERY_RETRY_MIN_INTERVAL_MS): GuardDecisionEngine =
    GuardDecisionEngine(
      targetPackage = targetPackage,
      recoveryRetryMinIntervalMs = retryMs,
    ).also { engine ->
      assertTrue(engine.observe(targetPackage, 0L) is GuardDecision.AwaitInitialHandoff)
      assertTrue(engine.confirmInitialHandoff(GuardContract.INITIAL_HANDOFF_QUIET_MS))
    }
}
