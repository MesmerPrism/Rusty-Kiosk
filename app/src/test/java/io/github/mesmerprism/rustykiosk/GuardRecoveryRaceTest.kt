package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardRecoveryRaceTest {
  private val targetPackage = "com.example.target"
  private val homePackage = "com.oculus.vrshell"
  private val libraryPackage = "com.oculus.panelapp.library"

  @Test
  fun acceptedLaunchRequestDoesNotPretendTargetFocusWasObserved() {
    val engine = armedEngine()
    assertTrue(engine.observeHomeInvocation(homePackage, 1_000L) is GuardDecision.ScheduleRecovery)

    engine.noteRecoveryRequested(1_005L)

    assertEquals(homePackage, engine.currentPackage)
    assertTrue(engine.observe(libraryPackage, 1_010L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun retryBurstUsesMinimumSpacingAndStopsAfterThreeRequests() {
    val engine = armedEngine()
    assertEquals(
      GuardDecision.ScheduleRecovery(0L),
      engine.observeHomeInvocation(homePackage, 1_000L),
    )
    engine.noteRecoveryRequested(1_000L)

    assertEquals(GuardDecision.ScheduleRecovery(22L), engine.observe(libraryPackage, 1_010L))
    engine.noteRecoveryRequested(1_032L)
    assertEquals(GuardDecision.ScheduleRecovery(24L), engine.observe(homePackage, 1_040L))
    engine.noteRecoveryRequested(1_064L)

    assertEquals(GuardDecision.NoChange, engine.observe(libraryPackage, 1_100L))
  }

  @Test
  fun observedTargetCancelsRecoveryAndNextDriftStartsFreshBurst() {
    val engine = armedEngine()
    engine.observeHomeInvocation(homePackage, 1_000L)
    engine.noteRecoveryRequested(1_000L)

    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_020L))
    assertEquals(
      GuardDecision.ScheduleRecovery(0L),
      engine.observe("com.example.other", 2_500L),
    )
  }

  @Test
  fun lateShellTailAfterTargetConfirmationReusesSameBoundedHomeBurst() {
    val engine = armedEngine()
    engine.observeHomeInvocation(homePackage, 1_000L)
    engine.noteRecoveryRequested(1_000L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_010L))

    assertEquals(
      GuardDecision.ScheduleRecovery(12L),
      engine.observe(libraryPackage, 1_020L),
    )
    engine.noteRecoveryRequested(1_032L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_040L))

    assertTrue(engine.observeHomeInvocation(homePackage, 2_300L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_310L))
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 3_600L))
  }

  private fun armedEngine(): GuardDecisionEngine =
    GuardDecisionEngine(targetPackage).also { engine ->
      assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 0L))
    }
}
