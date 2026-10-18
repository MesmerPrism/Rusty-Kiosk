package io.github.mesmerprism.rustykiosk

import org.junit.Assert.*
import org.junit.Test

class VisibilityMenuPolicyTest {
  private val pkg = "com.example.target"
  private val activity = "android.app.NativeActivity"
  private fun policy() = VisibilityMenuPolicy(pkg, activity, 100L)
  private fun qualify(p: VisibilityMenuPolicy, at: Long = 110L) =
    p.metadata(32, at, at, pkg, activity)
  private fun open(p: VisibilityMenuPolicy, at: Long): VisibilityMenuPolicy.Action {
    p.signal(false, at - 1, at - 1)
    return p.signal(true, at, at)
  }
  private fun reconcile(p: VisibilityMenuPolicy, at: Long) {
    p.originalReturned()
    p.signal(false, at, at)
    assertTrue(p.settle(p.serial, at + 160L))
  }

  @Test fun initialTrueNeverActsAndBothIndependentBaselinesAreRequired() {
    val p = policy()
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, p.signal(true, 101, 101))
    qualify(p)
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, p.signal(true, 120, 120))
    assertFalse(p.pending)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, open(p, 200))
    val q = policy()
    q.signal(false, 101, 101)
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, q.signal(true, 102, 102))
    qualify(q)
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, q.signal(true, 120, 120))
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, open(q, 200))
  }

  @Test fun duplicateTrueIsNotAHomeAndNoTargetReturnIsNeededForKnownFalseQuiet() {
    val p = policy(); qualify(p)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, open(p, 200))
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, p.signal(true, 210, 210))
    p.signal(false, 220, 220)
    assertFalse(p.settle(p.serial, 380)) // Transport original has not returned.
    p.originalReturned()
    val serial = p.serial
    assertFalse(p.settle(serial, 379))
    assertTrue(p.settle(serial, 380))
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, p.signal(true, 400, 400))
  }

  @Test fun threeDistinctOpeningsWithinFiveSecondsEscapeBeforeThirdAction() {
    val p = policy(); qualify(p)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, open(p, 200))
    reconcile(p, 220)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, p.signal(true, 700, 700))
    reconcile(p, 720)
    assertEquals(VisibilityMenuPolicy.Action.ESCAPE, p.signal(true, 1200, 1200))
    assertFalse(p.armed)
    p.disarm("reader-retired")
    assertEquals("triple-visibility-opening-escape", p.reason)
  }

  @Test fun freshOpeningWhileOriginalOrEffectPendingDisarmsWithoutRetry() {
    val p = policy(); qualify(p); open(p, 200)
    p.signal(false, 210, 210)
    assertEquals(VisibilityMenuPolicy.Action.DISARM, p.signal(true, 220, 220))
    assertFalse(p.canSend(220, 220))
  }

  @Test fun ownPanelsCannotQualifyOrDisarmAndOtherPackagesDisarm() {
    val p = policy()
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, p.metadata(32, 110, 110, pkg, "example.Panel"))
    assertFalse(p.qualified)
    qualify(p, 120)
    p.metadata(32, 130, 130, pkg, "example.Panel")
    assertTrue(p.qualified); assertTrue(p.armed)
    assertEquals(VisibilityMenuPolicy.Action.DISARM, p.metadata(32, 140, 140, "com.example.other", "Activity"))
  }

  @Test fun sourceMetadataFreshnessAndSameMillisecondSignalsFailClosed() {
    val p = policy()
    p.metadata(32, 110, 2111, pkg, activity)
    assertFalse(p.qualified)
    qualify(p, 2200)
    p.signal(false, 2300, 2300)
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, p.signal(true, 2300, 2300))
    assertFalse(p.pending)
    assertEquals(VisibilityMenuPolicy.Action.IGNORE, p.signal(true, 2301, 4302))
    assertFalse(p.pending)
  }

  @Test fun receiptQuietSerialRejectsAQueuedSettleAfterNewerFalseOrTrue() {
    val p = policy(); qualify(p); open(p, 200); p.originalReturned()
    p.signal(false, 210, 210)
    val old = p.serial
    p.signal(false, 300, 300)
    assertFalse(p.settle(old, 460))
    assertFalse(p.settle(p.serial, 459))
    assertTrue(p.settle(p.serial, 460))
  }

  @Test fun thirdAtExactlyFiveSecondWindowStillEscapesButExpiredHistoryDoesNot() {
    val p = policy(); qualify(p); open(p, 200); reconcile(p, 210)
    p.signal(true, 2700, 2700); reconcile(p, 2710)
    assertEquals(VisibilityMenuPolicy.Action.ESCAPE, p.signal(true, 5200, 5200))
    val q = policy(); qualify(q); open(q, 200); reconcile(q, 210)
    q.signal(true, 2700, 2700); reconcile(q, 2710)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, q.signal(true, 5201, 5201))
  }

  @Test fun callbackFenceCancelsUnsentTrueAndPermanentlyLatchesErrors() {
    val f = VisibilityCallbackFence()
    val first = f.observe(true, 100)
    assertTrue(f.matchesTrue(first))
    f.observe(false, 101)
    assertFalse(f.matchesTrue(first))
    val newer = f.observe(true, 102)
    f.invalidate(90) // An older error received before its main-loop post still cancels everything.
    assertFalse(f.matchesTrue(newer))
    assertEquals(0L, f.observe(true, 103))
  }

  @Test fun callbackFenceQuietUsesLatestReceiptEvenForDuplicateFalse() {
    val f = VisibilityCallbackFence()
    val token = f.observe(false, 100)
    assertEquals(token, f.observe(false, 200))
    assertFalse(f.matchesFalseQuiet(token, 359))
    assertTrue(f.matchesFalseQuiet(token, 360))
  }

  @Test fun slowStartupRemainsEligibleForFreshExactTargetMetadata() {
    val late = policy()
    assertEquals(VisibilityMenuPolicy.Action.TARGET,
      late.metadata(32, 90000, 90000, pkg, activity))
    assertTrue(late.qualified)
    late.signal(false, 90001, 90001)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, late.signal(true, 90002, 90002))
    val allowed = policy()
    assertEquals(VisibilityMenuPolicy.Action.TARGET,
      allowed.metadata(32, 5099, 5099, pkg, activity))
    allowed.signal(false, 9000, 9000)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, allowed.signal(true, 9001, 9001))
  }
}
