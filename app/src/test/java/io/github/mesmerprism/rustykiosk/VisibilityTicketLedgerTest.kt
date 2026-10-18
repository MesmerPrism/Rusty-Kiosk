package io.github.mesmerprism.rustykiosk

import org.junit.Assert.*
import org.junit.Test

class VisibilityTicketLedgerTest {
  private fun ledger(): VisibilityTicketLedger = VisibilityTicketLedger(-7L).also {
    it.prepareDurable(List(12) { index -> "prepared-$index" })
  }

  @Test fun preparedIntentDoesNotExecuteForStaleGenerationOrFailedGate() {
    val l = ledger()
    assertNull(l.claim(7L, true))
    assertNull(l.claim(-7L, false))
    assertEquals(12, l.size)
    assertNotNull(l.claim(-7L, true))
    assertNull(l.claim(-7L, true))
  }

  @Test fun onlyExactDurablyReturnedOriginalReplenishesAndThereIsNoLifetimeTwelveCallCap() {
    val l = ledger()
    repeat(200) { index ->
      val id = l.claim(-7L, true)!!
      assertEquals(11, l.size)
      l.returnedKnown(id)
      l.reportDurable(id, "replacement-$index")
      assertNull(l.original)
      assertEquals(12, l.size)
    }
  }

  @Test fun stopPreservesOriginalAndUnknownCannotBeReplayedOrReplenished() {
    val l = ledger()
    val id = l.claim(-7L, true)!!
    l.stop()
    assertEquals(id, l.original)
    assertNull(l.claim(-7L, true))
    l.hold(id)
    try { l.returnedKnown(id); fail("Unknown original was cleared") }
    catch (_: IllegalStateException) { assertEquals(id, l.original) }
    assertTrue(l.held)
  }

  @Test fun knownLateReturnAfterDisarmRetiresOriginalWithoutPreparingMoreActions() {
    val l = ledger()
    val id = l.claim(-7L, true)!!
    l.stop()
    l.returnedKnown(id)
    l.reportDurable(id, "new")
    assertNull(l.original)
    assertEquals(0, l.size)
    assertNull(l.claim(-7L, true))
  }

  @Test fun slowReportsCannotHoldKnownTransportOrSpendUndurableReplacements() {
    val l = ledger()
    val reports = mutableListOf<String>()
    repeat(12) {
      val id = l.claim(-7L, true)!!
      l.returnedKnown(id) // Disk worker has not returned any report.
      reports += id
      assertNull(l.original)
      assertFalse(l.held)
    }
    assertEquals(12, l.pendingReports)
    assertNull(l.claim(-7L, true)) // Only the genuinely prepared pool was spent.
    l.reportDurable(reports.first(), "durable-replacement")
    assertEquals("durable-replacement", l.claim(-7L, true))
  }

  @Test fun failedReportsAndLateDurabilityCannotInventUnknownCustodyOrRearmAfterStop() {
    val l = ledger()
    val failed = l.claim(-7L, true)!!
    l.returnedKnown(failed)
    l.reportFailed(failed)
    assertFalse(l.held)
    val late = l.claim(-7L, true)!!
    l.returnedKnown(late)
    l.stop()
    assertNull(l.original)
    l.reportDurable(late, "late-durable")
    assertEquals(0, l.size)
    assertNull(l.claim(-7L, true))
  }

  @Test fun canceledUnsentTicketIsNotReusedAndDoesNotEraseAnotherOriginal() {
    val l = ledger()
    val first = l.claim(-7L, true)!!
    l.cancelUnsent(first)
    val second = l.claim(-7L, true)!!
    assertNotEquals(first, second)
    try { l.cancelUnsent(first); fail("Wrong original erased") }
    catch (_: IllegalStateException) { assertEquals(second, l.original) }
  }
}
