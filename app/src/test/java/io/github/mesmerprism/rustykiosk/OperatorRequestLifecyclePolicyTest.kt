package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class OperatorRequestLifecyclePolicyTest {
  @Test
  fun onlyExactQueuedRequestCanBeCancelled() {
    assertTrue(OperatorRequestLifecyclePolicy.canCancel(OperatorRequestState.PENDING, true))
    assertFalse(OperatorRequestLifecyclePolicy.canCancel(OperatorRequestState.PENDING, false))
    listOf(
      OperatorRequestState.PENDING_WEARER_ACTION,
      OperatorRequestState.CONFIRMED,
      OperatorRequestState.REJECTED,
      OperatorRequestState.EXPIRED,
      OperatorRequestState.CANCELLED,
      OperatorRequestState.UNKNOWN,
    ).forEach { state -> assertFalse(OperatorRequestLifecyclePolicy.canCancel(state, true)) }
  }

  @Test
  fun expiryIsBoundedAndClockRollbackFailsClosed() {
    assertFalse(OperatorRequestLifecyclePolicy.isExpired(999L, 1_000L))
    assertTrue(OperatorRequestLifecyclePolicy.isExpired(1_000L, 1_000L))
    assertTrue(OperatorRequestLifecyclePolicy.isExpired(-1L, 1_000L))
    assertTrue(OperatorRequestLifecyclePolicy.isExpired(100L, 0L))
    assertTrue(OperatorRequestLifecyclePolicy.canClaim(999L, 100L, 1_000L))
    assertFalse(OperatorRequestLifecyclePolicy.canClaim(99L, 100L, 1_000L))
    assertFalse(OperatorRequestLifecyclePolicy.canClaim(1_000L, 100L, 1_000L))
    assertFalse(OperatorRequestLifecyclePolicy.canClaim(500L, 600L, 1_000L))
  }

  @Test
  fun onlyExactActiveEpochAndCommandCanCreateFirstTerminalReceipt() {
    val request = RustyKioskCliRequest("request_123", RustyKioskCliCommand.STATUS, null)
    assertTrue(
      OperatorRequestLifecyclePolicy.canRecord(
        request.requestId,
        request.command.wireName,
        "epoch-a",
        100L,
        1_000L,
        999L,
        request,
        "epoch-a",
        terminalExists = false,
      )
    )
    // Expiry clears active identity, an existing confirmed tombstone rejects a duplicate, and
    // crossed command/provider epochs cannot overwrite either terminal result.
    assertFalse(OperatorRequestLifecyclePolicy.canRecord(null, null, null, 100L, 1_000L, 999L, request, "epoch-a", false))
    assertFalse(OperatorRequestLifecyclePolicy.canRecord(request.requestId, request.command.wireName, "epoch-a", 100L, 1_000L, 999L, request, "epoch-a", true))
    assertFalse(OperatorRequestLifecyclePolicy.canRecord(request.requestId, "reload", "epoch-a", 100L, 1_000L, 999L, request, "epoch-a", false))
    assertFalse(OperatorRequestLifecyclePolicy.canRecord(request.requestId, request.command.wireName, "epoch-old", 100L, 1_000L, 999L, request, "epoch-a", false))
    assertFalse(OperatorRequestLifecyclePolicy.canRecord(request.requestId, request.command.wireName, "epoch-a", 100L, 1_000L, 1_000L, request, "epoch-a", false))
  }

  @Test
  fun processWideLockSerializesConcurrentEnqueueAndTerminalRacesAcrossOwners() {
    repeat(30) {
      var queued: String? = null
      var applied: String? = null
      var terminal: String? = null
      val ready = CountDownLatch(2)
      val start = CountDownLatch(1)
      val enqueueWins = AtomicInteger()
      val enqueueThreads = listOf("request_a", "request_b").map { id ->
        Thread {
          ready.countDown()
          start.await()
          synchronized(OperatorRequestProcessLock.monitor) {
            if (queued == null && applied == null) {
              queued = id
              enqueueWins.incrementAndGet()
            }
          }
        }.also(Thread::start)
      }
      ready.await()
      start.countDown()
      enqueueThreads.forEach(Thread::join)
      assertEquals(1, enqueueWins.get())

      val request = checkNotNull(queued)
      val transitionReady = CountDownLatch(2)
      val transitionStart = CountDownLatch(1)
      val transitions = listOf(
        Thread {
          transitionReady.countDown(); transitionStart.await()
          synchronized(OperatorRequestProcessLock.monitor) {
            if (queued == request && terminal == null) {
              queued = null
              terminal = "cancelled"
            }
          }
        },
        Thread {
          transitionReady.countDown(); transitionStart.await()
          synchronized(OperatorRequestProcessLock.monitor) {
            if (queued == request && terminal == null) {
              queued = null
              applied = request
            }
          }
        },
      ).onEach(Thread::start)
      transitionReady.await()
      transitionStart.countDown()
      transitions.forEach(Thread::join)
      assertTrue((terminal == "cancelled") xor (applied == request))

      if (applied == request) {
        val terminalReady = CountDownLatch(2)
        val terminalStart = CountDownLatch(1)
        val terminalThreads = listOf("confirmed", "expired").map { outcome ->
          Thread {
            terminalReady.countDown(); terminalStart.await()
            synchronized(OperatorRequestProcessLock.monitor) {
              if (applied == request && terminal == null) {
                applied = null
                terminal = outcome
              }
            }
          }.also(Thread::start)
        }
        terminalReady.await()
        terminalStart.countDown()
        terminalThreads.forEach(Thread::join)
        assertTrue(terminal == "confirmed" || terminal == "expired")
      }
    }
  }
}
