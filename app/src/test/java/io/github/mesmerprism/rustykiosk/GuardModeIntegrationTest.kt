package io.github.mesmerprism.rustykiosk

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class GuardModeIntegrationTest {
  private val target = LaunchTarget("com.example.target", "com.example.target.MainActivity",
    "android.intent.action.MAIN", setOf("android.intent.category.LAUNCHER"))

  @Test fun missingPersistedModeRemainsAccessibility() {
    val values = mutableMapOf<String, Any?>()
    val preferences = preferences(values)
    val store = GuardStateStore(preferences, { 1L })
    assertEquals(GuardMode.ACCESSIBILITY, store.selectedMode())
    store.arm(target, "Target")
    values.remove("mode") // Existing installations have no mode key.
    assertEquals(GuardMode.ACCESSIBILITY, GuardStateStore(preferences).loadArmed()!!.mode)
  }

  @Test fun visibilityModePersistsAndArmHookSeesCommittedGeneration() {
    val preferences = preferences(mutableMapOf())
    var generation = 7L
    val announced = mutableListOf<GuardConfig>()
    lateinit var store: GuardStateStore
    store = GuardStateStore(preferences, { generation++ }, onVisibilityArmed = {
      assertEquals(it, store.loadArmed()); announced += it
    })
    store.setSelectedMode(GuardMode.VISIBILITY)
    val visibilityConfig = store.arm(target, "Target", mode = store.selectedMode())
    assertEquals(GuardMode.VISIBILITY, GuardStateStore(preferences).selectedMode())
    assertEquals(visibilityConfig, GuardStateStore(preferences).loadArmed())
    val next = store.arm(target, "Target", mode = GuardMode.ACCESSIBILITY)
    assertNotEquals(visibilityConfig.generation, next.generation)
    assertEquals(listOf(visibilityConfig), announced)
  }

  @Test fun changingModeDisarmsRatherThanMovingAnArmedTarget() {
    var disarmed = 0
    val store = GuardStateStore(preferences(mutableMapOf()), { 2L }, onDisarm = { disarmed++ })
    store.arm(target, "Target")
    store.setSelectedMode(GuardMode.VISIBILITY)
    assertNull(store.loadArmed())
    assertEquals(1, disarmed)
  }

  @Test fun selectedReadinessUsesOnlyItsOwnAuthority() {
    assertTrue(UserControlState(accessibilityEnabled = true).guardReady)
    val visibility = UserControlState(kioskMode = GuardMode.VISIBILITY,
      visibilityReady = true, accessibilityEnabled = true)
    assertTrue(visibility.guardReady)
    assertFalse(visibility.copy(accessibilityEnabled = false).guardReady)
    assertFalse(visibility.copy(visibilityReady = false).guardReady)
    assertFalse(visibility.copy(kioskMode = GuardMode.UNAVAILABLE).guardReady)
  }

  @Test fun visibilityReadinessIsCheckedBeforePersistingANewGeneration() {
    var announced = false
    val store = GuardStateStore(preferences(mutableMapOf()), { 4L },
      onVisibilityArmed = { announced = true }, canArmVisibility = { false })
    try {
      store.arm(target, "Target", mode = GuardMode.VISIBILITY)
      fail("Unready visibility mode armed")
    } catch (_: IllegalStateException) {
      assertNull(store.loadArmed())
      assertFalse(announced)
    }
  }

  @Test fun rejectedVisibilityAnnouncementRollsBackPersistedArm() {
    var disarmed = false
    val store = GuardStateStore(preferences(mutableMapOf()), { 5L },
      onDisarm = { disarmed = true }, onVisibilityArmed = { error("revoked") })
    try {
      store.arm(target, "Target", mode = GuardMode.VISIBILITY)
      fail("Rejected visibility arm survived")
    } catch (_: IllegalStateException) {
      assertNull(store.loadArmed())
      assertTrue(disarmed)
    }
  }

  @Test fun cliModeValuesAreClosedAndRoundTripWithTheUiIds() {
    for (mode in listOf(GuardMode.ACCESSIBILITY, GuardMode.VISIBILITY)) {
      val request = RustyKioskCliProtocol.parse("mode_request", "set-kiosk-mode", mode.id).getOrThrow()
      assertEquals(mode, GuardMode.fromId(request.value))
    }
    for (value in listOf(null, "other", "SHELL", "shell; id")) {
      assertTrue(RustyKioskCliProtocol.parse("mode_request", "set-kiosk-mode", value).isFailure)
    }
  }

  private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
    lateinit var editor: SharedPreferences.Editor
    editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
      when {
        method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; editor }
        method.name == "remove" -> { values.remove(args!![0] as String); editor }
        method.name == "clear" -> { values.clear(); editor }
        method.name == "commit" -> true
        else -> null
      }
    } as SharedPreferences.Editor
    return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
      when (method.name) {
        "edit" -> editor
        "getAll" -> values.toMap()
        "contains" -> values.containsKey(args!![0])
        "getString", "getStringSet", "getLong", "getInt", "getBoolean", "getFloat" ->
          if (values.containsKey(args!![0])) values[args[0]] else args[1]
        else -> null
      }
    } as SharedPreferences
  }
}
