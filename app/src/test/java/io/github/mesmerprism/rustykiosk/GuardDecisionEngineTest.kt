package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
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
  fun firstTwoHomeInvocationsRecoverAndThirdReturnsToKiosk() {
    val engine = GuardDecisionEngine(targetPackage)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 0L))

    assertTrue(engine.observeHomeInvocation(homePackage, 1_000L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(1_000L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_100L))
    assertTrue(engine.observeHomeInvocation(homePackage, 2_500L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(2_500L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_600L))
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 4_000L))
  }

  @Test
  fun oneHomeEventBurstCountsOnce() {
    val engine = GuardDecisionEngine(targetPackage)
    engine.observe(targetPackage, 0L)

    assertTrue(engine.observeHomeInvocation(homePackage, 1_000L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(1_000L)
    assertTrue(engine.observeHomeInvocation(homePackage, 1_300L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(1_300L)
    assertTrue(engine.observeHomeInvocation(homePackage, 1_700L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_800L))
    assertTrue(engine.observeHomeInvocation(homePackage, 2_300L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 2_400L))
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 3_600L))
  }

  @Test
  fun genericHomeEdgeAndExactEventFromOnePressCountOnce() {
    val engine = GuardDecisionEngine(targetPackage)
    engine.observe(targetPackage, 0L)

    assertTrue(engine.observe(homePackage, 100L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(100L)
    assertTrue(engine.observeHomeInvocation(homePackage, 200L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 500L))
    assertTrue(engine.observeHomeInvocation(homePackage, 1_400L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(1_500L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_600L))
    assertEquals(
      GuardDecision.DisarmAndReturn,
      engine.observeHomeInvocation(homePackage, 2_700L),
    )
  }

  @Test
  fun shellWindowTailAfterRecoveryRequestsRefocusWithoutCreatingFalseHomePress() {
    val engine = GuardDecisionEngine(targetPackage)
    engine.observe(targetPackage, 0L)
    assertTrue(engine.observe(homePackage, 100L) is GuardDecision.ScheduleRecovery)

    engine.noteRecoveryRequested(200L)
    assertTrue(engine.observe(libraryPackage, 210L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(232L)
    assertTrue(engine.observe(homePackage, 250L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(264L)
    assertEquals(GuardDecision.NoChange, engine.observe(libraryPackage, 300L))
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 400L))
    assertTrue(engine.observeHomeInvocation(homePackage, 1_500L) is GuardDecision.ScheduleRecovery)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_600L))
    assertEquals(GuardDecision.DisarmAndReturn, engine.observeHomeInvocation(homePackage, 2_800L))
  }

  @Test
  fun escapeWindowResets() {
    val engine = GuardDecisionEngine(targetPackage)
    engine.observe(targetPackage, 0L)

    assertTrue(engine.observeHomeInvocation(homePackage, 1_000L) is GuardDecision.ScheduleRecovery)
    assertTrue(engine.observeHomeInvocation(homePackage, 7_000L) is GuardDecision.ScheduleRecovery)
    assertTrue(engine.observeHomeInvocation(homePackage, 13_000L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun unrelatedAppDriftRecoversWithoutAdvancingHomeEscape() {
    val engine = GuardDecisionEngine(targetPackage)
    engine.observe(targetPackage, 0L)

    assertTrue(engine.observe("com.example.other", 1_000L) is GuardDecision.ScheduleRecovery)
    engine.noteRecoveryRequested(1_000L)
    assertEquals(GuardDecision.CancelRecovery, engine.observe(targetPackage, 1_100L))
    assertTrue(engine.observeHomeInvocation(homePackage, 2_500L) is GuardDecision.ScheduleRecovery)
  }

  @Test
  fun ordinaryAppTransitionsNeverTriggerHomeEscape() {
    val engine = GuardDecisionEngine(targetPackage)

    repeat(3) { index ->
      engine.observe(targetPackage, (index * 2_000).toLong())
      assertTrue(
        engine.observe("com.example.other$index", (index * 2_000 + 100).toLong()) is
          GuardDecision.ScheduleRecovery
      )
    }
  }
}
