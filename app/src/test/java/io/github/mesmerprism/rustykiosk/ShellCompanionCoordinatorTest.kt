package io.github.mesmerprism.rustykiosk

import org.junit.Assert.*
import org.junit.Test

class ShellCompanionCoordinatorTest {
  private val helper = ShellCompanionIdentity("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", 123)
  private val target = ShellGuardTarget(42L, "example.target", "example.target.Main")
  private fun ready(): ShellCompanionCoordinator = ShellCompanionCoordinator { java.util.UUID.randomUUID().toString() }.also {
    it.register(helper, true, 0L)
    it.armTarget(target)
    assertEquals("target_qualified", it.targetState(helper, 42L, true, 0L).status)
  }
  private fun open(c: ShellCompanionCoordinator, time: Long, sequence: Long = time) =
    c.nativeEvent(helper, 42L, sequence, time, time, true, time)
  private fun closed(c: ShellCompanionCoordinator, time: Long, sequence: Long = time) =
    c.nativeEvent(helper, 42L, sequence, time, time, false, time)

  @Test fun onlyImmediateUid2000AndProtocolOneAreAdmitted() {
    assertTrue(ShellCompanionAdmissionPolicy.authorized(2000, 123, 123, 1))
    assertFalse(ShellCompanionAdmissionPolicy.authorized(1000, 123, 123, 1))
    assertFalse(ShellCompanionAdmissionPolicy.authorized(0, 123, 123, 1))
    assertFalse(ShellCompanionAdmissionPolicy.authorized(2000, 124, 123, 1))
    assertFalse(ShellCompanionAdmissionPolicy.authorized(2000, 123, 123, 2))
  }

  @Test fun readinessPrecedesTargetAndNeverFabricatesFocus() {
    val c = ShellCompanionCoordinator { "command" }
    c.register(helper, true, 0L)
    assertTrue(c.ready(0L))
    c.armTarget(target)
    assertEquals("awaiting_target_focus", open(c, 100L).status)
    closed(c, 101L)
    assertEquals("awaiting_target_focus", c.targetState(helper, 42L, false, 102L).status)
    assertEquals("target_qualified", c.targetState(helper, 42L, true, 103L).status)
    assertEquals("close", open(c, 200L).action)
  }

  @Test fun staleHelperGenerationAndReplayCannotAuthorizeClose() {
    val c = ready()
    assertEquals("generation_mismatch", c.nativeEvent(helper, 43L, 1L, 100L, 100L, true, 100L).status)
    val first = open(c, 100L)
    assertEquals("stale_native_event", open(c, 101L, 100L).status)
    assertEquals("wait", c.claim(helper, 42L, first.commandId!!, 179L).action)
    assertEquals("helper_stale", c.claim(helper, 42L, first.commandId, 3001L).status)
    assertFalse(c.ready(3001L))
    assertTrue(c.heartbeat(helper, 3001L).disarm)
    assertEquals("guard_not_armed", c.claim(helper, 42L, first.commandId, 3002L).status)
  }

  @Test fun nativeClosedCancelsOnlyUnsentAndNeverQualifiesTarget() {
    val c = ready()
    val first = open(c, 100L)
    closed(c, 150L)
    assertEquals("no_candidate", c.claim(helper, 42L, first.commandId!!, 180L).status)
    val second = open(c, 200L)
    assertEquals("execute", c.claim(helper, 42L, second.commandId!!, 280L).action)
    closed(c, 290L)
    assertEquals(second.commandId, c.pendingCommandId)
    assertEquals("no_candidate", c.claim(helper, 42L, second.commandId, 300L).status)
  }

  @Test fun newEdgeWaitsForKnownCallbackAndClaimNeverReplays() {
    val c = ready()
    val first = open(c, 100L)
    assertEquals("execute", c.claim(helper, 42L, first.commandId!!, 180L).action)
    assertEquals("no_candidate", c.claim(helper, 42L, first.commandId, 181L).status)
    closed(c, 200L)
    val second = open(c, 1300L)
    assertEquals("wait", c.claim(helper, 42L, second.commandId!!, 1380L).action)
    assertEquals("original_mismatch", c.result(helper, 42L, "wrong", "callback", true, 180L, 1400L, 1400L).status)
    assertEquals("callback_known", c.result(helper, 42L, first.commandId, "callback", true, 180L, 1400L, 1400L).status)
    assertEquals("execute", c.claim(helper, 42L, second.commandId, 1401L).action)
  }

  @Test fun unknownOriginalIsHeldAcrossDisarmAndLateCallbackCanRetireIt() {
    val c = ready()
    val first = open(c, 100L)
    c.claim(helper, 42L, first.commandId!!, 180L)
    val unknown = c.result(helper, 42L, first.commandId, "unknown", false, 180L, null, 200L)
    assertTrue(unknown.disarm)
    assertTrue(c.originalHeld)
    assertFalse(c.ready(200L))
    c.disarm()
    c.synchronizeTarget(target) // Even a failed persistent disarm may not re-arm this generation.
    assertNull(c.armedTarget)
    val foreign = helper.copy(session = "other")
    assertEquals("original_helper_mismatch", c.register(foreign, true, 5000L).status)
    assertEquals("callback_known", c.result(helper, 42L, first.commandId, "callback", true, 180L, 5000L, 5000L).status)
    assertNull(c.pendingCommandId)
    assertFalse(c.originalHeld)
    assertNull(c.armedTarget)
  }

  @Test fun thirdHomeWithinInclusiveWindowCancelsQueuedCloseBeforeItCanExecute() {
    val c = ready()
    val first = open(c, 100L)
    c.claim(helper, 42L, first.commandId!!, 180L)
    closed(c, 200L)
    open(c, 1300L)
    closed(c, 1400L)
    c.heartbeat(helper, 3000L)
    val third = open(c, 5100L)
    assertEquals("escape", third.action)
    assertTrue(third.disarm)
    assertNull(c.candidateCommandId)
    assertEquals(first.commandId, c.pendingCommandId)
    assertEquals("guard_not_armed", c.claim(helper, 42L, third.commandId!!, 5180L).status)
    assertEquals("callback_known", c.result(helper, 42L, first.commandId, "callback", true, 180L, 5200L, 5200L).status)
    assertNull(c.armedTarget)
  }

  @Test fun debounceAndWindowPruningRetainExistingGuardSemantics() {
    val c = ready()
    open(c, 100L); closed(c, 110L)
    open(c, 1299L); closed(c, 1300L) // Same1200ms family; not a second escape count.
    assertEquals("close", open(c, 1364L).action); closed(c, 1400L)
    c.heartbeat(helper, 3000L); c.heartbeat(helper, 5000L)
    assertEquals("close", open(c, 5101L).action) // First count5101-100>5000expired.
  }

  @Test fun passiveAccessibilityModeNeverClaimsOrCountsEscape() {
    val c = ready()
    c.synchronizeTarget(target.copy(mode = GuardMode.ACCESSIBILITY))
    for (time in listOf(100L, 1300L, 2500L)) {
      assertEquals("observe-only", open(c, time).action)
      assertEquals("observe-only", closed(c, time + 1L).action)
    }
    assertEquals("observe-only", c.claim(helper, 42L, "any", 2600L).action)
    assertNull(c.pendingCommandId)
    assertNotNull(c.armedTarget)
  }

  @Test fun knownNotExecutedClearsOnlyMatchingClaimWithoutCallbackClaim() {
    val c = ready()
    val first = open(c, 100L)
    c.claim(helper, 42L, first.commandId!!, 180L)
    closed(c, 181L)
    assertEquals("known_not_executed", c.result(helper, 42L, first.commandId, "not-executed", false, null, null, 182L, 182L).status)
    assertNull(c.pendingCommandId)
    assertNotNull(c.armedTarget)
  }

  @Test fun processRestartCannotReconstructPersistedShellArmOrEscapeHistory() {
    val restarted = ShellCompanionCoordinator { "command" }
    restarted.register(helper, true, 0L)
    assertFalse(restarted.synchronizeTarget(target))
    assertNull(restarted.armedTarget)
    assertFalse(restarted.armTarget(target))
    assertEquals("guard_not_armed", open(restarted, 100L).status)
    assertTrue(restarted.armTarget(target.copy(generation = 43L)))
    assertEquals("target_qualified", restarted.targetState(helper, 43L, true, 101L).status)
    val passive = ShellCompanionCoordinator { "observer" }
    assertTrue(passive.synchronizeTarget(target.copy(mode = GuardMode.ACCESSIBILITY)))
  }

  @Test fun repeatedFocusedSnapshotsDoNotFloodTimingRingAndNativeFalseIsNotFocusProof() {
    val c = ready()
    assertEquals("target_focus_unchanged", c.targetState(helper, 42L, true, 1L).status)
    open(c, 100L); closed(c, 200L)
    assertEquals("target_focus_unchanged", c.targetState(helper, 42L, true, 201L).status)
    c.targetState(helper, 42L, false, 202L)
    assertEquals("target_qualified", c.targetState(helper, 42L, true, 203L).status)
  }

  @Test fun pendingOriginalPreventsShellRearmEvenAfterDisarmUntilKnownResult() {
    val c = ready()
    val first = open(c, 100L)
    c.claim(helper, 42L, first.commandId!!, 180L)
    assertFalse(c.ready(181L))
    assertTrue(c.healthy(181L))
    assertEquals("heartbeat", c.heartbeat(helper, 181L).status)
    assertTrue(c.healthy(181L))
    c.disarm()
    assertFalse(c.armTarget(target.copy(generation = 43L)))
    assertNull(c.armedTarget)
    c.result(helper, 42L, first.commandId, "callback", true, 180L, 200L, 200L)
    assertTrue(c.ready(200L))
    assertTrue(c.armTarget(target.copy(generation = 43L)))
  }

  @Test fun focusProofMustBeFreshAndCannotPredateAnAcceptedNativeEdge() {
    val c = ready()
    open(c, 100L)
    assertEquals("stale_target_focus", c.targetState(helper, 42L, true, 110L, 99L).status)
    assertEquals("stale_target_focus", c.targetState(helper, 42L, true, 2200L, 100L).status)
    assertEquals("stale_target_focus", c.targetState(helper, 42L, true, 110L, 111L).status)
  }

  @Test fun optionalCompanionExpiryOrUnreadyCannotDisarmAccessibility() {
    val c = ready()
    c.synchronizeTarget(target.copy(mode = GuardMode.ACCESSIBILITY))
    val stale = c.nativeEvent(helper, 42L, 1L, 4000L, 4000L, true, 4000L)
    assertFalse(stale.disarm)
    assertFalse(c.heartbeat(helper, 4000L).disarm)
    assertEquals(GuardMode.ACCESSIBILITY, c.armedTarget!!.mode)
    c.register(helper, false, 4001L)
    assertFalse(c.targetState(helper, 42L, true, 4002L).disarm)
    assertNotNull(c.armedTarget)
  }

  @Test fun lateOldShellFailureOrUnknownCannotDisarmNewAccessibilityGeneration() {
    for (outcome in listOf("callback", "unknown")) {
      val c = ready()
      val first = open(c, 100L)
      c.claim(helper, 42L, first.commandId!!, 180L)
      c.disarm()
      val access = target.copy(generation = 43L, mode = GuardMode.ACCESSIBILITY)
      assertTrue(c.synchronizeTarget(access))
      val reply = c.result(helper, 42L, first.commandId, outcome, false, 180L,
        if (outcome == "callback") 200L else null, 200L)
      assertFalse(reply.disarm)
      assertEquals(access, c.armedTarget)
      if (outcome == "unknown") {
        assertTrue(c.originalHeld)
        assertEquals(first.commandId, c.pendingCommandId)
        c.result(helper, 42L, first.commandId, "callback", true, 180L, 210L, 210L)
        assertEquals(access, c.armedTarget)
      }
    }
  }
}
