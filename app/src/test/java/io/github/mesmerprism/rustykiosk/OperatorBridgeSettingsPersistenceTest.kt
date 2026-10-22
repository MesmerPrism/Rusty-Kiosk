package io.github.mesmerprism.rustykiosk

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class OperatorBridgeSettingsPersistenceTest {
  @Test
  fun rotationCommitsOneCoherentDisabledRevokedIdentityAndThenStops() {
    val f = Fixture()
    val before = f.settings.directV2Profile()
    val code = f.settings.rotatePairingCode()
    val after = f.settings.directV2Profile()
    assertEquals(1, f.prefs.commits)
    assertNotEquals(CODE, code)
    assertNotEquals(before.pairingId, after.pairingId)
    assertNotEquals(before.ownerEpoch, after.ownerEpoch)
    assertFalse(after.summaryGrant.granted)
    assertFalse(after.detailGrant.granted)
    assertFalse(f.prefs.getBoolean("enabled", true))
    assertEquals(2L, f.settings.generation())
    assertEquals(listOf("retire:1", "stop:2"), f.effects.calls)
    assertNull(f.fence.failure)
  }

  @Test
  fun failedRotationCommitBeforeOrAfterMemoryApplicationCannotLeaveOldAccessUsable() {
    for (applyMemory in listOf(false, true)) {
      val f = Fixture()
      f.prefs.failCommit = 1
      f.prefs.applyBeforeFailure = applyMemory
      assertTrue(runCatching { f.settings.rotatePairingCode() }.isFailure)
      assertNotNull(f.fence.failure)
      assertFalse(f.settings.acceptsGeneration(1))
      assertFalse(f.settings.snapshot().transitionConverged)
      assertEquals(listOf("retire:1"), f.effects.calls)
      assertEquals(1, f.prefs.commits)
    }
  }

  @Test
  fun retirementOrStopDispatchFailureIsFencedAndNeverClaimsConvergence() {
    for (point in listOf("retire", "stop")) {
      val f = Fixture()
      f.effects.fail = point
      assertTrue(runCatching { f.settings.rotatePairingCode() }.isFailure)
      assertNotNull(f.fence.failure)
      assertFalse(f.settings.acceptsGeneration(f.settings.generation()))
      assertFalse(f.settings.snapshot().transitionConverged)
      assertEquals(if (point == "retire") 0 else 1, f.prefs.commits)
    }
  }

  @Test
  fun explicitEnableAfterFailedRotationCreatesFreshRevokedProfileAndGeneration() {
    val f = Fixture()
    val prior = f.settings.directV2Profile()
    f.prefs.failCommit = 1
    assertTrue(runCatching { f.settings.rotatePairingCode() }.isFailure)
    f.prefs.failCommit = null
    f.settings.setEnabled(true)
    assertNull(f.fence.failure)
    val current = f.settings.directV2Profile()
    assertFalse(current.summaryGrant.granted)
    assertFalse(current.detailGrant.granted)
    assertNotEquals(prior.pairingId, current.pairingId)
    assertNotEquals(prior.ownerEpoch, current.ownerEpoch)
    assertTrue(f.settings.generation() > 1)
    assertFalse(f.settings.acceptsGeneration(1))
    assertTrue(f.settings.acceptsGeneration(f.settings.generation()))
    assertTrue(f.effects.calls.last().startsWith("start:"))
  }

  @Test
  fun failedConsentRevokeCannotClaimSuccessFromAndroidMemoryReadback() {
    for (applyMemory in listOf(false, true)) {
      val f = Fixture()
      f.prefs.failCommit = 1
      f.prefs.applyBeforeFailure = applyMemory
      val operation = runCatching { f.settings.setV2CatalogSummaryGranted(false) }
      assertTrue(operation.isFailure)
      assertNotNull(f.fence.failure)
      assertFalse(f.settings.acceptsGeneration(1))
      assertFalse(f.settings.snapshot().transitionConverged)
      assertTrue(f.effects.calls.isEmpty())
    }
  }

  @Test
  fun failedRecoveryCommitOrStartCannotRemoveTheFenceOrRestoreConsent() {
    for (point in listOf("commit", "start")) {
      val f = Fixture()
      f.prefs.failCommit = 1
      assertTrue(runCatching { f.settings.rotatePairingCode() }.isFailure)
      f.prefs.failCommit = if (point == "commit") 2 else null
      f.effects.fail = if (point == "start") "start" else null
      assertTrue(runCatching { f.settings.setEnabled(true) }.isFailure)
      assertNotNull(f.fence.failure)
      assertFalse(f.settings.acceptsGeneration(f.settings.generation()))
      assertFalse(f.settings.snapshot().transitionConverged)
      f.prefs.failCommit = null
      f.effects.fail = null
      f.settings.setEnabled(true)
      assertNull(f.fence.failure)
      assertFalse(f.settings.directV2Profile().summaryGrant.granted)
    }
  }

  @Test
  fun delayedOldEnableAndStoredDetailCannotResurrectCatalogueConsent() {
    val f = Fixture()
    f.settings.rotatePairingCode()
    assertFalse(f.settings.setEnabledIfGeneration(true, 1))
    assertFalse(f.settings.directV2Profile().summaryGrant.granted)
    val oldState = DirectOperatorV2SettingsState("PairingIdentity_0000000001", "KeyEpoch_0000000000000001",
      DirectOperatorV2Base64.encode(ByteArray(32)), true, "SummaryGrant_000000000001", true, "DetailGrant_0000000000001")
    f.prefs.values["direct_v2_state"] = oldState.toJson().toString()
    f.settings.setEnabled(true)
    val profile = f.settings.directV2Profile()
    assertFalse(profile.summaryGrant.granted)
    assertFalse(profile.detailGrant.granted)
  }

  private class Fixture {
    val fence = OperatorBridgePersistenceFence()
    val effects = Effects()
    val prefs = MemoryPreferences().apply {
      values.putAll(mapOf("enabled" to true, "running" to true, "running_generation" to 1L,
        "bridge_generation" to 1L, "pairing_code" to CODE,
        "catalog_owner_epoch" to "OwnerEpoch_0000000000000001",
        "direct_v2_state" to DirectOperatorV2SettingsState("PairingIdentity_0000000001",
          "KeyEpoch_0000000000000001", DirectOperatorV2Base64.encode(ByteArray(32)), true,
          "SummaryGrant_000000000001", false, "DetailGrant_0000000000001").toJson().toString()))
    }
    val settings = OperatorBridgeSettings(prefs, effects, fence)
  }

  private class Effects : OperatorBridgeSettingsEffects {
    val calls = mutableListOf<String>()
    var fail: String? = null
    override fun retireUsbGeneration(generation: Long) {
      calls += "retire:$generation"
      if (fail == "retire") error("Controlled USB retirement failure")
    }
    override fun start(generation: Long) {
      calls += "start:$generation"
      if (fail == "start") error("Controlled START dispatch failure")
    }
    override fun stop(generation: Long) {
      calls += "stop:$generation"
      if (fail == "stop") error("Controlled STOP dispatch failure")
    }
  }

  private class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var commits = 0
    var failCommit: Int? = null
    var applyBeforeFailure = false
    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
      private val pending = mutableMapOf<String, Any?>()
      private var clear = false
      override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
      override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = values }
      override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
      override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
      override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
      override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
      override fun remove(key: String?): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = null }
      override fun clear(): SharedPreferences.Editor = apply { clear = true }
      override fun commit(): Boolean {
        commits++
        val success = commits != failCommit
        if (success || applyBeforeFailure) {
          if (clear) values.clear()
          pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
        return success
      }
      override fun apply() { commit() }
    }
  }

  companion object { private const val CODE = "0123-4567-89AB-CDEF-GHJK-MNPQ-RS" }
}
