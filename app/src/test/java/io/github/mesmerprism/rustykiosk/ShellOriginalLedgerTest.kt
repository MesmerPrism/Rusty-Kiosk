package io.github.mesmerprism.rustykiosk

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShellOriginalLedgerTest {
  private val helper = ShellCompanionIdentity("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", 123)
  private val target = ShellGuardTarget(42L, "example.target", "example.target.Main")
  private data class Admitted(val coordinator: ShellCompanionCoordinator, val id: String)

  private fun admitted(ledger: ShellOriginalLedger): Admitted {
    val coordinator = ShellCompanionCoordinator(ledger) { UUID.randomUUID().toString() }
    coordinator.register(helper, true, 0)
    assertTrue(coordinator.armTarget(target))
    coordinator.targetState(helper, target.generation, true, 0)
    val candidate = coordinator.nativeEvent(helper, target.generation, 1, 100, 100, true, 100)
    assertEquals("execute", coordinator.claim(helper, target.generation, candidate.commandId!!, 180).action)
    return Admitted(coordinator, candidate.commandId)
  }

  @Test fun actualCoordinatorRestoresOriginalCustodyWithoutReplayingOrArmingAnyMode() {
    val storage = Storage()
    val original = admitted(StoredShellOriginalLedger(storage.preferences))
    val restarted = ShellCompanionCoordinator(StoredShellOriginalLedger(storage.restart().preferences)) { UUID.randomUUID().toString() }
    assertTrue(restarted.outstandingCustody)
    assertEquals(original.id, restarted.pendingCommandId)
    assertEquals(target, restarted.originalTarget)
    assertFalse(restarted.synchronizeTarget(target))
    for (mode in listOf(GuardMode.ACCESSIBILITY, GuardMode.SHELL, GuardMode.VISIBILITY))
      assertFalse(restarted.armTarget(target.copy(generation = 43L, mode = mode)))
    assertEquals("original_helper_mismatch", restarted.register(helper.copy(pid = 124), true, 200).status)
    assertEquals("registered", restarted.register(helper, true, 200).status)
    assertFalse(restarted.ready(200))
    assertEquals("callback_known", restarted.result(helper, 42, original.id, "callback", true, 180, 210, 210).status)
    assertFalse(restarted.outstandingCustody)
    assertTrue(restarted.armTarget(target.copy(generation = 43L)))
  }

  @Test fun exactHelperGenerationAndCommandAreRequiredForDurableRetirement() {
    val storage = Storage()
    val (coordinator, id) = admitted(StoredShellOriginalLedger(storage.preferences))
    for (wrong in listOf(helper.copy(pid = 124), helper.copy(session = UUID.randomUUID().toString()), helper.copy(boot = UUID.randomUUID().toString())))
      assertEquals("helper_mismatch", coordinator.result(wrong, 42, id, "callback", true, 180, 200, 200).status)
    assertEquals("original_mismatch", coordinator.result(helper, 43, id, "callback", true, 180, 200, 200).status)
    assertEquals("original_mismatch", coordinator.result(helper, 42, UUID.randomUUID().toString(), "callback", true, 180, 200, 200).status)
    assertTrue(coordinator.outstandingCustody)
    assertTrue(StoredShellOriginalLedger(storage.restart().preferences).read() is ShellOriginalRecord.Pending)
    assertEquals("callback_known", coordinator.result(helper, 42, id, "callback", true, 180, 200, 200).status)
    assertTrue(StoredShellOriginalLedger(storage.restart().preferences).read() is ShellOriginalRecord.Retired)
    assertEquals("no_original", coordinator.result(helper, 42, id, "callback", true, 180, 200, 200).status)
  }

  @Test fun claimPersistenceFailureNeverGrantsExecuteAndHoldsNewAdmission() {
    val storage = Storage().apply { commitsSucceed = false }
    val coordinator = ShellCompanionCoordinator(StoredShellOriginalLedger(storage.preferences)) { UUID.randomUUID().toString() }
    coordinator.register(helper, true, 0); coordinator.armTarget(target); coordinator.targetState(helper, 42, true, 0)
    val candidate = coordinator.nativeEvent(helper, 42, 1, 100, 100, true, 100)
    val denied = coordinator.claim(helper, 42, candidate.commandId!!, 180)
    assertEquals("original_claim_storage_held", denied.status)
    assertNotEquals("execute", denied.action)
    assertTrue(denied.disarm)
    assertTrue(coordinator.outstandingCustody)
    assertFalse(coordinator.armTarget(target.copy(generation = 43)))
  }

  @Test fun terminalPersistenceFailureRetainsTheOriginalAcrossProcessRestart() {
    val storage = Storage()
    val (coordinator, id) = admitted(StoredShellOriginalLedger(storage.preferences))
    storage.commitsSucceed = false
    assertEquals("original_retirement_storage_held", coordinator.result(helper, 42, id, "callback", true, 180, 200, 200).status)
    assertEquals(id, coordinator.pendingCommandId)
    assertTrue(coordinator.outstandingCustody)
    val restarted = ShellCompanionCoordinator(StoredShellOriginalLedger(storage.restart().preferences)) { UUID.randomUUID().toString() }
    assertTrue(restarted.outstandingCustody)
    assertEquals(id, restarted.pendingCommandId)
  }

  @Test fun tamperAfterAdmissionCannotRetireADifferentDurableOriginal() {
    val storage = Storage()
    val (coordinator, id) = admitted(StoredShellOriginalLedger(storage.preferences))
    val changed = JSONObject(storage.live[StoredShellOriginalLedger.KEY] as String)
    changed.getJSONObject("original").put("command_id", UUID.randomUUID().toString())
    storage.live[StoredShellOriginalLedger.KEY] = changed.toString()
    assertEquals("original_retirement_storage_held", coordinator.result(helper, 42, id, "callback", true, 180, 200, 200).status)
    assertTrue(coordinator.outstandingCustody)
  }

  @Test fun missingNullMalformedWrongTypeAndUnknownFieldsAreDistinctFromUnused() {
    assertEquals(ShellOriginalRecord.Empty, StoredShellOriginalLedger(Storage().preferences).read())
    val goodStorage = Storage()
    admitted(StoredShellOriginalLedger(goodStorage.preferences))
    val good = goodStorage.live[StoredShellOriginalLedger.KEY] as String
    val invalid = mutableListOf<Any?>(null, true, "{", "null", "[]", "{}")
    invalid += JSONObject(good).put("extra", true).toString()
    invalid += JSONObject(good).put("state", "unknown").toString()
    invalid += JSONObject(good).apply { getJSONObject("original").put("pid", -1) }.toString()
    invalid += JSONObject(good).apply { getJSONObject("original").put("generation", "42") }.toString()
    invalid += JSONObject(good).apply { getJSONObject("original").put("mode", "accessibility") }.toString()
    for (value in invalid) {
      val storage = Storage().apply { live[StoredShellOriginalLedger.KEY] = value }
      val ledger = StoredShellOriginalLedger(storage.preferences)
      assertEquals(ShellOriginalRecord.Damaged, ledger.read())
      val coordinator = ShellCompanionCoordinator(ledger) { UUID.randomUUID().toString() }
      coordinator.register(helper, true, 0)
      assertFalse(coordinator.ready(0))
      assertTrue(coordinator.outstandingCustody)
      assertFalse(coordinator.armTarget(target))
    }
  }

  @Test fun knownNoExecuteRetiresOnlyTheExactClaimAndCannotClaimAnApiCallback() {
    val storage = Storage()
    val (coordinator, id) = admitted(StoredShellOriginalLedger(storage.preferences))
    assertEquals("invalid_no_execute", coordinator.result(helper, 42, id, "not-executed", true, null, null, 200, 200).status)
    assertTrue(coordinator.outstandingCustody)
    assertEquals("known_not_executed", coordinator.result(helper, 42, id, "not-executed", false, null, null, 200, 200).status)
    val record = StoredShellOriginalLedger(storage.restart().preferences).read() as ShellOriginalRecord.Retired
    assertEquals("not-executed", record.terminal.outcome)
    assertNull(record.terminal.dispatchMs)
    assertNull(record.terminal.callbackMs)
    assertFalse(coordinator.outstandingCustody)
  }

  @Test fun unknownOriginalRemainsDurablyPendingUntilItsKnownLateCallback() {
    val storage = Storage()
    val (coordinator, id) = admitted(StoredShellOriginalLedger(storage.preferences))
    assertEquals("original_unknown_held", coordinator.result(helper, 42, id, "unknown", false, 180, null, 200).status)
    assertTrue(StoredShellOriginalLedger(storage.restart().preferences).read() is ShellOriginalRecord.Pending)
    coordinator.disarm()
    assertTrue(coordinator.outstandingCustody)
    assertEquals("callback_known", coordinator.result(helper, 42, id, "callback", true, 180, 250, 250).status)
    assertFalse(coordinator.outstandingCustody)
  }

  /** SharedPreferences commit may change RAM despite disk failure; restart reads only durable bytes. */
  private class Storage(private val durable: MutableMap<String, Any?> = mutableMapOf()) {
    val live = durable.toMutableMap()
    var commitsSucceed = true
    fun restart() = Storage(durable.toMutableMap())
    private fun editor(): SharedPreferences.Editor {
      val writes = mutableMapOf<String, Any?>()
      lateinit var editor: SharedPreferences.Editor
      editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
        when (method.name) {
          "putString" -> { writes[args!![0] as String] = args[1]; editor }
          "commit" -> { live.putAll(writes); if (commitsSucceed) durable.putAll(writes); commitsSucceed }
          else -> error("Unexpected editor call ${method.name}")
        }
      } as SharedPreferences.Editor
      return editor
    }
    val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
      when (method.name) {
        "contains" -> live.containsKey(args!![0] as String)
        "getString" -> if (live.containsKey(args!![0])) live[args[0]] as String? else args[1] as String?
        "edit" -> editor()
        else -> error("Unexpected preferences call ${method.name}")
      }
    } as SharedPreferences
  }
}
