package io.github.mesmerprism.rustykiosk

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Actual coordinator/store/return policy; deterministic app-main ordering, no Android UI effects. */
class ShellReturnPolicyTest {
  private val target = LaunchTarget("fixture.target", "fixture.target.Main", "android.intent.action.MAIN", setOf("android.intent.category.LAUNCHER"))
  private val helper = ShellCompanionIdentity("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", 123)
  private inner class Owner {
    val prefs = Preferences()
    val coordinator = ShellCompanionCoordinator { UUID.randomUUID().toString() }
    var nextGeneration = 42L
    val store = GuardStateStore(prefs.value, { nextGeneration++ }, onDisarm = { coordinator.disarm() },
      onShellArmed = { coordinator.armTarget(it.shellTarget()) })
    val main = ArrayDeque<() -> Unit>()
    var requests = 0
    var dispatchedGeneration: Long? = null
    init {
      coordinator.register(helper, true, 0)
      store.arm(target, "Target", mode = GuardMode.SHELL)
      coordinator.targetState(helper, 42, true, 0)
      coordinator.nativeEvent(helper, 42, 1, 100, 100, true, 100)
      coordinator.nativeEvent(helper, 42, 2, 101, 101, false, 101)
      coordinator.nativeEvent(helper, 42, 3, 1400, 1400, true, 1400)
      coordinator.nativeEvent(helper, 42, 4, 1401, 1401, false, 1401)
    }
    fun enqueueThird() { main.add {
      check(coordinator.synchronizeTarget(store.loadArmed()?.shellTarget()))
      val before = coordinator.armedTarget
      val reply = coordinator.nativeEvent(helper, 42, 5, 2700, 2700, true, 2700)
      if (reply.action == "escape") ShellReturnPolicy.dispatch(store, before!!) {
        requests++; dispatchedGeneration = it
      }
    } }
    fun third() { enqueueThird(); main.removeFirst().invoke() }
  }
  private fun GuardConfig.shellTarget() = ShellGuardTarget(generation, target.packageName, target.activityName, mode)

  @Test fun queuedOldThirdAfterNormalHasNoDispatchOrAdditionalDisarm() {
    val owner = Owner(); owner.enqueueThird(); owner.store.disarm("normal-launch")
    owner.main.removeFirst().invoke()
    assertEquals(0, owner.requests)
    assertEquals("normal-launch", owner.prefs.live["last_disarm_reason"])
  }

  @Test fun queuedOldThirdCannotRevokeAnyReplacementMode() {
    for (mode in listOf(GuardMode.ACCESSIBILITY, GuardMode.SHELL, GuardMode.VISIBILITY)) {
      val owner = Owner(); owner.enqueueThird()
      val replacement = owner.store.arm(target, "Replacement", mode = mode)
      owner.main.removeFirst().invoke()
      assertEquals(0, owner.requests); assertEquals(replacement, owner.store.loadArmed())
    }
  }

  @Test fun genuineThirdDispatchesExactlyOneFixedReturnAndDuplicateDoesNothing() {
    val owner = Owner(); owner.third()
    assertEquals(1, owner.requests); assertEquals(42L, owner.dispatchedGeneration)
    assertNull(owner.store.loadArmed())
    assertEquals(ShellReturnPolicy.ESCAPED, owner.prefs.live["last_disarm_reason"])
    owner.third(); assertEquals(1, owner.requests)
    val lifecycle = ShellReturnLifecycle(owner.store)
    assertTrue(lifecycle.arrive(42)); assertTrue(lifecycle.resumeAllowed())
    owner.store.disarm("rusty-kiosk-resumed") // The unchanged ordinary Activity resume consumes arrival.
    assertFalse(ShellReturnLifecycle(owner.store).arrive(42))
  }

  @Test fun interveningNormalOrModeChangeRejectsOldDeliveryWithoutDisarm() {
    for (reason in listOf("normal-launch", "kiosk-mode-changed", "explicit-exit")) {
      val owner = Owner(); owner.third(); owner.store.disarm(reason)
      val lifecycle = ShellReturnLifecycle(owner.store)
      assertFalse(lifecycle.arrive(42)); assertFalse(lifecycle.resumeAllowed())
      assertEquals(reason, owner.prefs.live["last_disarm_reason"])
    }
  }

  @Test fun newGenerationBetweenDispatchAndArrivalSurvivesRejectedIntent() {
    val owner = Owner(); owner.third()
    val replacement = owner.store.arm(target, "Replacement", mode = GuardMode.VISIBILITY)
    val lifecycle = ShellReturnLifecycle(owner.store)
    assertFalse(lifecycle.arrive(42)); assertFalse(lifecycle.resumeAllowed())
    assertEquals(replacement, owner.store.loadArmed())
  }

  @Test fun supersessionBetweenArrivalAndResumeCannotDisarmNewState() {
    for (replacement in listOf(false, true)) {
      val owner = Owner(); owner.third()
      val lifecycle = ShellReturnLifecycle(owner.store); assertTrue(lifecycle.arrive(42))
      val next = if (replacement) owner.store.arm(target, "Replacement", mode = GuardMode.ACCESSIBILITY)
        else { owner.store.disarm("normal-launch"); null }
      assertFalse(lifecycle.resumeAllowed()); assertTrue(lifecycle.rejected)
      assertEquals(next, owner.store.loadArmed())
      if (!replacement) assertEquals("normal-launch", owner.prefs.live["last_disarm_reason"])
    }
  }

  @Test fun wrongMissingOrDuplicateArrivalNeverConsumesAnotherGeneration() {
    val owner = Owner(); owner.third()
    for (value in listOf(null, 0L, 41L, 43L)) assertFalse(ShellReturnLifecycle(owner.store).arrive(value))
    assertTrue(ShellReturnLifecycle(owner.store).arrive(42))
    assertFalse(ShellReturnLifecycle(owner.store).arrive(42))
  }

  @Test fun dispatchExceptionRemainsAnErrorAndCannotAuthorizeLaterDelivery() {
    val owner = Owner()
    assertTrue(runCatching { ShellReturnPolicy.dispatch(owner.store, owner.coordinator.armedTarget!!) { error("local start rejected") } }.isFailure)
    assertEquals(0, owner.requests)
    assertFalse(ShellReturnLifecycle(owner.store).arrive(42))
    assertEquals("shell-return-dispatch-failed", owner.prefs.live["last_disarm_reason"])
  }

  @Test fun failedDisarmPersistenceNeverCallsStartAndFailedArrivalNeverResumes() {
    val owner = Owner(); owner.prefs.commits = false
    assertTrue(runCatching { ShellReturnPolicy.dispatch(owner.store, owner.coordinator.armedTarget!!) { owner.requests++ } }.isFailure)
    assertEquals(0, owner.requests)
    for (throws in listOf(false, true)) {
      val arrived = Owner(); arrived.third(); arrived.prefs.commits = false; arrived.prefs.throwCommit = throws
      val lifecycle = ShellReturnLifecycle(arrived.store)
      assertFalse(lifecycle.arrive(42)); assertFalse(lifecycle.resumeAllowed())
    }
  }

  @Test fun malformedExistingStateFailsClosedAndOrdinaryResumeKeepsItsOwnDisarm() {
    val owner = Owner(); owner.third(); owner.prefs.live["mode"] = true
    assertFalse(ShellReturnLifecycle(owner.store).arrive(42))
    val ordinary = Owner(); val lifecycle = ShellReturnLifecycle(ordinary.store)
    assertTrue(lifecycle.resumeAllowed()); ordinary.store.disarm("rusty-kiosk-resumed")
    assertNull(ordinary.store.loadArmed())
    assertEquals("rusty-kiosk-resumed", ordinary.prefs.live["last_disarm_reason"])
  }

  @Test fun returnAdmissionRequiresExactShellTargetAndCannotReplayDispatch() {
    val owner = Owner(); val expected = owner.coordinator.armedTarget!!
    for (wrong in listOf(expected.copy(mode = GuardMode.ACCESSIBILITY), expected.copy(generation = 43),
      expected.copy(packageName = "fixture.other"), expected.copy(activityName = "fixture.target.Other"))) {
      assertFalse(ShellReturnPolicy.dispatch(owner.store, wrong) { owner.requests++ })
      assertEquals(42L, owner.store.loadArmed()!!.generation)
    }
    assertTrue(ShellReturnPolicy.dispatch(owner.store, expected) { owner.requests++ })
    assertFalse(ShellReturnPolicy.dispatch(owner.store, expected) { owner.requests++ })
    assertEquals(1, owner.requests)
  }

  @Test fun genuineOrdinaryOrGenericIntentReplacesPendingShellClassification() {
    for (mode in listOf(GuardMode.ACCESSIBILITY, GuardMode.VISIBILITY, GuardMode.SHELL)) {
      val owner = Owner(); owner.third()
      val lifecycle = ShellReturnLifecycle(owner.store); assertTrue(lifecycle.arrive(42))
      owner.store.arm(target, "Replacement", mode = mode)
      assertTrue(lifecycle.pending)
      lifecycle.ordinaryIntent() // Actual non-Shell onNewIntent route, including generic returns.
      assertFalse(lifecycle.pending); assertFalse(lifecycle.rejected); assertTrue(lifecycle.resumeAllowed())
      owner.store.disarm("rusty-kiosk-resumed") // Genuine ordinary resume keeps its original authority.
      assertNull(owner.store.loadArmed())
    }
  }

  @Test fun pendingShellIdentitySurvivesUntilResumeAndRecreationCannotBecomeOrdinary() {
    val owner = Owner(); owner.third()
    val lifecycle = ShellReturnLifecycle(owner.store); assertTrue(lifecycle.arrive(42))
    assertTrue(lifecycle.pending)
    val recreated = ShellReturnLifecycle(owner.store)
    assertFalse(recreated.arrive(42)) // Retained Shell intent sees ARRIVED, not a new unused escape.
    assertFalse(recreated.resumeAllowed())
    assertTrue(lifecycle.pending); assertTrue(lifecycle.resumeAllowed()); assertFalse(lifecycle.pending)
    owner.store.disarm("rusty-kiosk-resumed")
    assertTrue(lifecycle.resumeAllowed()) // Later ordinary wearer resumes remain ordinary.
  }

  private class Preferences {
    val live = mutableMapOf<String, Any?>()
    var commits = true
    var throwCommit = false
    private val editor: SharedPreferences.Editor
    val value: SharedPreferences
    init {
      editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when {
          method.name.startsWith("put") -> { live[args!![0] as String] = args[1]; proxy }
          method.name == "commit" -> { if (throwCommit) error("storage unavailable"); commits }
          else -> null
        }
      } as SharedPreferences.Editor
      value = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
          "edit" -> editor
          "contains" -> live.containsKey(args!![0])
          "getAll" -> live.toMap()
          "getString", "getStringSet", "getLong", "getInt", "getBoolean" -> if (live.containsKey(args!![0])) live[args[0]] else args[1]
          else -> null
        }
      } as SharedPreferences
    }
  }
}
