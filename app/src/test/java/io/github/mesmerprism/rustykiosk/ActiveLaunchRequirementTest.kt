package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveLaunchRequirementTest {
  @Test
  fun threeStateRequirementIsStrictAndConflictsFailClosed() {
    assertEquals(AppLaunchRequirement.ANY, AppLaunchRequirement.parseStrict("any"))
    assertEquals(AppLaunchRequirement.WIFI_ON, AppLaunchRequirement.parseStrict("wifi-on"))
    assertEquals(AppLaunchRequirement.WIFI_OFF, AppLaunchRequirement.parseStrict("wifi-off"))
    assertThrows(IllegalArgumentException::class.java) {
      AppLaunchRequirement.parseStrict("wifi-adb-on")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AppLaunchRequirement.fromHandlers(
        setOf(ActiveRequirementHandlerId.WIFI_ON, ActiveRequirementHandlerId.WIFI_OFF)
      )
    }
  }

  @Test
  fun ordinaryTagsCannotCreateAlterOrDeleteDedicatedRequirement() {
    val entry = entry()
    val initial = TagFileCodec.parseDocument(
      """{"schema":"rusty.kiosk.app_tags.v2","apps":[{"name":"Example","package":"com.example.app","tags":["wifi-on"],"requirements":["wifi-off"]}]}"""
    )
    val edited = TagDocumentEditor.setTags(initial, entry, setOf("other"))
    assertEquals(AppLaunchRequirement.WIFI_OFF, edited.requirementFor(entry))
    val removed = TagDocumentEditor.setTags(edited, entry, emptySet())
    assertEquals(AppLaunchRequirement.WIFI_OFF, removed.requirementFor(entry))

    val legacy = TagFileCodec.parseDocument(
      """{"schema":"rusty.kiosk.app_tags.v1","apps":[{"name":"Example","package":"com.example.app","tags":["wifi-off"]}]}"""
    )
    assertEquals(AppLaunchRequirement.ANY, legacy.requirementFor(entry))

    val nameOnly = TagFileCodec.parseDocument(
      """{"schema":"rusty.kiosk.app_tags.v2","apps":[{"name":"Example","tags":["old"],"requirements":["wifi-on"]}]}"""
    )
    val packageBound = TagDocumentEditor.setTags(nameOnly, entry, setOf("new"))
    assertEquals(AppLaunchRequirement.WIFI_ON, packageBound.requirementFor(entry))
    assertEquals("com.example.app", packageBound.apps.single().record.packageName)
  }

  @Test
  fun settingRequirementUpgradesLegacyWithoutChangingTags() {
    val entry = entry().copy(tags = setOf("demo"))
    val legacy = TagFileCodec.parseDocument(
      """{"schema":"rusty.kiosk.app_tags.v1","apps":[{"name":"Example","package":"com.example.app","tags":["demo"]}]}"""
    )
    val upgraded = TagDocumentEditor.setLaunchRequirement(
      legacy,
      entry,
      AppLaunchRequirement.WIFI_ON,
    )
    val reparsed = TagFileCodec.parseDocument(TagFileCodec.encode(upgraded))
    assertEquals(TagFileCodec.SCHEMA_V2, reparsed.schema)
    assertEquals(AppLaunchRequirement.WIFI_ON, reparsed.requirementFor(entry))
    assertEquals(setOf("demo"), reparsed.records.single().tags)
  }

  @Test
  fun wifiHandlersReadOrdinaryWifiAndDistinguishUnavailableFromError() {
    val on = WifiActiveRequirementHandler(
      ActiveRequirementHandlerId.WIFI_ON,
      WifiEnabledObserver { true },
    ).evaluate()
    val off = WifiActiveRequirementHandler(
      ActiveRequirementHandlerId.WIFI_OFF,
      WifiEnabledObserver { true },
    ).evaluate()
    assertEquals(ActiveRequirementEvaluationState.SATISFIED, on.state)
    assertEquals(ActiveRequirementEvaluationState.UNSATISFIED, off.state)
    assertEquals("android.wifi_manager.is_wifi_enabled", on.provenance)

    val unavailable = WifiActiveRequirementHandler(
      ActiveRequirementHandlerId.WIFI_ON,
      WifiEnabledObserver { throw SecurityException("denied") },
    ).evaluate()
    val error = WifiActiveRequirementHandler(
      ActiveRequirementHandlerId.WIFI_ON,
      WifiEnabledObserver { error("damaged") },
    ).evaluate()
    assertEquals(ActiveRequirementEvaluationState.UNAVAILABLE, unavailable.state)
    assertEquals(ActiveRequirementEvaluationState.ERROR, error.state)
  }

  @Test
  fun samePreflightHandlesNormalAndKioskWithoutCreatingPendingWhenSatisfied() {
    listOf(LaunchKind.NORMAL, LaunchKind.KIOSK).forEach { kind ->
      val fixture = Fixture(kind)
      fixture.state = ActiveRequirementEvaluationState.SATISFIED
      assertTrue(fixture.coordinator.request(fixture.candidate) is RequirementLaunchDecision.LaunchNow)
      assertNull(fixture.store.load())
    }
  }

  @Test
  fun unmetReturnDebouncesAndSatisfiedReturnConsumesExactlyOnce() {
    val fixture = Fixture(LaunchKind.KIOSK)
    val first = fixture.coordinator.request(fixture.candidate) as RequirementLaunchDecision.RemediationRequired
    val waiting = fixture.coordinator.resume(fixture.candidate) as RequirementLaunchDecision.Waiting
    assertEquals(first.pending.pendingId, waiting.pending.pendingId)
    assertEquals(first.pending, fixture.store.load())

    fixture.state = ActiveRequirementEvaluationState.SATISFIED
    assertTrue(fixture.coordinator.resume(fixture.candidate) is RequirementLaunchDecision.LaunchNow)
    assertNull(fixture.store.load())
    assertTrue(fixture.coordinator.resume(fixture.candidate) is RequirementLaunchDecision.NoPending)
  }

  @Test
  fun repeatedClickKeepsExactPendingBindingWithoutReopeningOrExtendingLifetime() {
    val fixture = Fixture(LaunchKind.KIOSK)
    val first = fixture.coordinator.request(fixture.candidate) as RequirementLaunchDecision.RemediationRequired
    fixture.now = 500L
    val repeated = fixture.coordinator.request(fixture.candidate) as RequirementLaunchDecision.Waiting
    assertEquals(first.pending, repeated.pending)
    assertEquals(1_100L, repeated.pending.expiresAtElapsedMs)

    val changed = fixture.candidate.copy(
      binding = fixture.binding.copy(packageName = "com.example.changed"),
    )
    val cleared = fixture.coordinator.request(changed) as RequirementLaunchDecision.Cleared
    assertEquals(PendingRequirementClearReason.STALE_BINDING, cleared.reason)
    assertNull(fixture.store.load())
  }

  @Test
  fun anyRequirementStillUsesFreshBoundCandidatePreflight() {
    val fixture = Fixture(LaunchKind.NORMAL, requirement = AppLaunchRequirement.ANY)
    val launched = fixture.coordinator.request(fixture.candidate) as RequirementLaunchDecision.LaunchNow
    assertEquals(fixture.binding, launched.candidate.binding)
    assertNull(fixture.store.load())
  }

  @Test
  fun cancellationExpiryProcessRestartAndDisappearanceFailClosed() {
    val cancelled = Fixture(LaunchKind.NORMAL)
    cancelled.coordinator.request(cancelled.candidate)
    assertEquals(
      PendingRequirementClearReason.CANCELLED,
      (cancelled.coordinator.cancel("pending-1") as RequirementLaunchDecision.Cleared).reason,
    )

    val expired = Fixture(LaunchKind.NORMAL)
    expired.coordinator.request(expired.candidate)
    expired.now = 1_100L
    assertEquals(
      PendingRequirementClearReason.EXPIRED,
      (expired.coordinator.resume(expired.candidate) as RequirementLaunchDecision.Cleared).reason,
    )

    val disappeared = Fixture(LaunchKind.NORMAL)
    disappeared.coordinator.request(disappeared.candidate)
    assertEquals(
      PendingRequirementClearReason.STALE_BINDING,
      (disappeared.coordinator.resume(null) as RequirementLaunchDecision.Cleared).reason,
    )

    // Production intentionally uses process memory: a process restart cancels, never replays.
    val restarted = Fixture(LaunchKind.NORMAL, MemoryStore())
    assertTrue(restarted.coordinator.resume(restarted.candidate) is RequirementLaunchDecision.NoPending)
  }

  @Test
  fun everyPointOfUseBindingSubstitutionClearsPending() {
    val mutations = listOf<(ActiveRequirementLaunchBinding) -> ActiveRequirementLaunchBinding>(
      { it.copy(catalogEntryKey = "package:other") },
      { it.copy(packageName = "com.example.other") },
      { it.copy(target = it.target.copy(activityName = "OtherActivity")) },
      { it.copy(installationIdentity = it.installationIdentity.copy(versionCode = 2L)) },
      { it.copy(installationIdentity = it.installationIdentity.copy(uid = 10002)) },
      { it.copy(launchKind = if (it.launchKind == LaunchKind.NORMAL) LaunchKind.KIOSK else LaunchKind.NORMAL) },
      { it.copy(tagDocumentDigest = "3".repeat(64)) },
      { it.copy(requirementDigest = "4".repeat(64)) },
    )
    mutations.forEach { mutate ->
      val fixture = Fixture(LaunchKind.NORMAL)
      fixture.coordinator.request(fixture.candidate)
      val stale = fixture.coordinator.resume(
        fixture.candidate.copy(binding = mutate(fixture.binding))
      ) as RequirementLaunchDecision.Cleared
      assertEquals(PendingRequirementClearReason.STALE_BINDING, stale.reason)
    }
  }

  @Test
  fun optionIdAndRowDigestRemainBoundAcrossRemediation() {
    val fixture = Fixture(LaunchKind.NORMAL)
    val optionBinding =
      fixture.binding.copy(
        launchOptionId = "playlist.one",
        launchOptionDigest = "5".repeat(64),
      )
    val optionCandidate = fixture.candidate.copy(binding = optionBinding)
    assertTrue(fixture.coordinator.request(optionCandidate) is RequirementLaunchDecision.RemediationRequired)

    val changedRow = optionCandidate.copy(
      binding = optionBinding.copy(launchOptionDigest = "6".repeat(64))
    )
    val stale = fixture.coordinator.resume(changedRow) as RequirementLaunchDecision.Cleared
    assertEquals(PendingRequirementClearReason.STALE_BINDING, stale.reason)
  }

  private class Fixture(
    kind: LaunchKind,
    val store: MemoryStore = MemoryStore(),
    requirement: AppLaunchRequirement = AppLaunchRequirement.WIFI_ON,
  ) {
    var now = 100L
    var state = ActiveRequirementEvaluationState.UNSATISFIED
    val binding = ActiveRequirementLaunchBinding(
      catalogEntryKey = "package:com.example.app",
      packageName = "com.example.app",
      target = entry().target!!,
      installationIdentity = PackageInstallationIdentity("a".repeat(64), 100L, 1L, 10001),
      launchKind = kind,
      launchOptionId = null,
      launchOptionDigest = null,
      tagDocumentDigest = "1".repeat(64),
      requirementDigest = "2".repeat(64),
    )
    val candidate = ActiveRequirementLaunchCandidate(binding, requirement)
    val coordinator = ActiveRequirementLaunchCoordinator(
      store,
      ActiveRequirementHandlerRegistry(
        listOf(ActiveRequirementHandlerId.WIFI_ON to ActiveRequirementHandler {
          ActiveRequirementEvaluation(
            ActiveRequirementHandlerId.WIFI_ON,
            state,
            "test",
            state.wireName,
          )
        })
      ),
      { now },
      { "pending-1" },
      1_000L,
    )
  }

  private class MemoryStore : PendingRequirementLaunchStore {
    private var pending: PendingRequirementLaunch? = null
    override fun load(): PendingRequirementLaunch? = pending
    override fun replace(pending: PendingRequirementLaunch) { this.pending = pending }
    override fun clear() { pending = null }
  }

  companion object {
    private fun entry(): CatalogEntry = CatalogEntry(
      key = "package:com.example.app",
      label = "Example",
      packageName = "com.example.app",
      target = LaunchTarget(
        "com.example.app",
        "com.example.app.MainActivity",
        "android.intent.action.MAIN",
        setOf("android.intent.category.LAUNCHER"),
      ),
      installed = true,
      tags = emptySet(),
      source = "test",
    )
  }
}
