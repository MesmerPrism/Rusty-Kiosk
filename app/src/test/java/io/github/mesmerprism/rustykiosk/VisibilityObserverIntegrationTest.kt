package io.github.mesmerprism.rustykiosk

import io.github.mesmerprism.rustyquest.navigatorvisibility.NavigatorVisibilityObserver
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VisibilityObserverIntegrationTest {
  private class Source : NavigatorVisibilityObserver.Backend {
    lateinit var sink: NavigatorVisibilityObserver.Backend.Callback
    var registrations = 0
    var retirements = 0
    override fun attach(callback: NavigatorVisibilityObserver.Backend.Callback) { sink = callback }
    override fun register(): Boolean { registrations++; return true }
    override fun unregister(): Boolean { retirements++; return true }
    fun emit(value: Boolean) { sink.sample(value, -91L) }
  }

  @Test fun exactObserverSequencesAdmitEqualUptimeEdgesWithoutInventingAnInitialOpening() {
    val source = Source()
    val policy = VisibilityMenuPolicy("com.example.target", "Target", 100L)
    policy.metadata(32, 101L, 101L, "com.example.target", "Target")
    val actions = mutableListOf<VisibilityMenuPolicy.Action>()
    val kinds = mutableListOf<NavigatorVisibilityObserver.Kind>()
    val observer = NavigatorVisibilityObserver(source, { 102L },
      object : NavigatorVisibilityObserver.Listener {
        override fun observed(sample: NavigatorVisibilityObserver.Observation) {
          kinds += sample.kind
          actions += policy.signal(sample.visible, sample.receivedUptimeMs, 102L, sample.sequence)
        }
        override fun fault(generation: Long, category: String) { fail(category) }
      })
    observer.start()
    source.emit(true); source.emit(false); source.emit(true); source.emit(true)
    assertEquals(NavigatorVisibilityObserver.Kind.INITIAL_TRUE, kinds.first())
    assertEquals(listOf(VisibilityMenuPolicy.Action.IGNORE, VisibilityMenuPolicy.Action.CLOSED,
      VisibilityMenuPolicy.Action.CLOSE, VisibilityMenuPolicy.Action.IGNORE), actions)
    observer.close(); source.emit(false)
    assertEquals(4, actions.size)
    assertEquals(1, source.registrations); assertEquals(1, source.retirements)
  }

  @Test fun callbackFenceRejectsATrueAlreadySupersededBeforeTheUiQueueRuns() {
    val fence = VisibilityCallbackFence()
    fence.observe(false, 100L)
    val opening = fence.observe(true, 100L)
    fence.observe(false, 100L)
    assertFalse(fence.matchesTrue(opening))
    assertFalse(fence.matchesFalseQuiet(opening, 500L))
  }

  @Test fun unsupportedPersistedSelectionDoesNotBecomeAccessibilityRecovery() {
    assertEquals(GuardMode.ACCESSIBILITY, GuardMode.fromId(null))
    assertEquals(GuardMode.UNAVAILABLE, GuardMode.fromId("shell"))
    assertEquals(GuardMode.UNAVAILABLE, GuardMode.fromId("corrupt"))
    assertEquals(GuardMode.VISIBILITY, GuardMode.fromId("visibility"))
  }

  @Test fun slowOrFailedReportingDoesNotDelayFalseQuietOrThirdOpeningEscape() {
    val policy = VisibilityMenuPolicy("target", "Activity", 100L)
    policy.metadata(32, 110L, 110L, "target", "Activity")
    val ledger = VisibilityTicketLedger(7L)
    ledger.prepareDurable(List(12) { "ticket-$it" })
    policy.signal(false, 120L, 120L)
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, policy.signal(true, 200L, 200L))
    val first = ledger.claim(7L, true)!!
    ledger.returnedKnown(first); policy.originalReturned()
    // The first report is deliberately still blocked on its independent worker.
    policy.signal(false, 210L, 210L)
    assertTrue(policy.settle(policy.serial, 370L))
    assertEquals(VisibilityMenuPolicy.Action.CLOSE, policy.signal(true, 400L, 400L))
    val second = ledger.claim(7L, true)!!
    ledger.returnedKnown(second); policy.originalReturned()
    ledger.reportFailed(second) // A reporting failure cannot invent unknown transport.
    policy.signal(false, 410L, 410L)
    assertTrue(policy.settle(policy.serial, 570L))
    assertEquals(VisibilityMenuPolicy.Action.ESCAPE, policy.signal(true, 600L, 600L))
    ledger.stop()
    assertNull(ledger.original); assertFalse(ledger.held)
    ledger.reportDurable(first, "late-replacement")
    assertEquals(0, ledger.size)
  }

  @Test(timeout = 15000) fun delayedOriginalRegistrationRetiresOnceAfterExplicitDisarm() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val retired = AtomicInteger()
    val starts = AtomicInteger()
    val backend = object : NavigatorVisibilityObserver.Backend {
      override fun attach(callback: NavigatorVisibilityObserver.Backend.Callback) = Unit
      override fun register(): Boolean {
        starts.incrementAndGet(); entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)); return true
      }
      override fun unregister(): Boolean { retired.incrementAndGet(); return true }
    }
    val observer = NavigatorVisibilityObserver(backend, { 100000L },
      object : NavigatorVisibilityObserver.Listener {
        override fun observed(sample: NavigatorVisibilityObserver.Observation) { fail("disarmed callback") }
        override fun fault(generation: Long, category: String) { fail(category) }
      })
    val original = Thread { observer.start() }
    original.start()
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS))
      // A slow app can supply fresh metadata long after the old estimated startup budget.
      val policy = VisibilityMenuPolicy("target", "Activity", 100L)
      assertEquals(VisibilityMenuPolicy.Action.TARGET,
        policy.metadata(32, 100000L, 100000L, "target", "Activity"))
      assertEquals(NavigatorVisibilityObserver.Phase.REGISTERING, observer.status().phase)
      assertEquals("", observer.status().fault)
      policy.disarm("explicit-normal-launch")
      observer.close() // Does not replay or abandon the in-flight original.
      assertEquals(0, retired.get())
    } finally { release.countDown(); original.join(10000L) }
    assertFalse(original.isAlive)
    assertEquals(1, starts.get()); assertEquals(1, retired.get())
    assertEquals(NavigatorVisibilityObserver.Phase.CLOSED, observer.status().phase)
  }

  @Test fun exactControllerCleanupCanReconcileAnUncertainRegistration() {
    var closes = 0
    val backend = object : NavigatorVisibilityObserver.Backend {
      override fun attach(callback: NavigatorVisibilityObserver.Backend.Callback) = Unit
      override fun register(): Boolean = error("registration transport threw")
      override fun unregister(): Boolean { closes++; return true }
    }
    val observer = NavigatorVisibilityObserver(backend, { 100L },
      object : NavigatorVisibilityObserver.Listener {
        override fun observed(sample: NavigatorVisibilityObserver.Observation) { fail("held callback") }
        override fun fault(generation: Long, category: String) = Unit
      })
    assertEquals(NavigatorVisibilityObserver.Phase.HELD, observer.start().phase)
    observer.close(); observer.close()
    assertEquals(1, closes)
    assertEquals(NavigatorVisibilityObserver.Phase.CLOSED, observer.status().phase)
  }
}
