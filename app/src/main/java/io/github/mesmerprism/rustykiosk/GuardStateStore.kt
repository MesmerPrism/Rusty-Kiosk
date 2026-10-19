package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.security.SecureRandom

internal object GuardGenerationPolicy {
  private const val MAX_ATTEMPTS = 32

  fun next(previousGeneration: Long, candidate: () -> Long): Long {
    repeat(MAX_ATTEMPTS) {
      val generation = candidate()
      if (generation != 0L && generation != previousGeneration) return generation
    }
    error("Could not allocate a distinct foreground guard generation.")
  }
}

internal class GuardStateStore(
  private val preferences: SharedPreferences,
  private val generationSource: () -> Long = SecureRandom()::nextLong,
  private val onDisarm: (String) -> Unit = {},
  private val onVisibilityArmed: (GuardConfig) -> Unit = {},
  private val canArmVisibility: () -> Boolean = { true },
  private val onShellArmed: (GuardConfig) -> Unit = {},
  private val canArmShell: () -> Boolean = { true },
  private val canArmNewGuard: () -> Boolean = { true },
) {
  constructor(context: Context, generationSource: () -> Long = SecureRandom()::nextLong) : this(
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    generationSource,
    { reason ->
      ShellCompanionBridge.disarm(context.applicationContext, reason)
      VisibilityMenuBridge.disarm(context.applicationContext, reason)
    },
    { config -> VisibilityMenuBridge.onTargetArmed(context.applicationContext, config) },
    { VisibilityMenuBridge.isReady(context.applicationContext) },
    { config -> ShellCompanionBridge.onTargetArmed(context.applicationContext, config) },
    { ShellCompanionBridge.isReady(context.applicationContext) },
    { ShellCompanionBridge.canArmNewGuard(context.applicationContext) },
  )

  fun arm(
    target: LaunchTarget,
    targetLabel: String,
    foregroundSignalCapability: ForegroundSignalCapability? = null,
    mode: GuardMode = GuardMode.ACCESSIBILITY,
  ): GuardConfig {
    check(mode != GuardMode.UNAVAILABLE) { "Choose a supported kiosk mode explicitly." }
    check(canArmNewGuard()) { "A prior shell original remains owned; new guard admission waits." }
    check(mode != GuardMode.SHELL || canArmShell()) { "Shell companion or prior visibility retirement is unavailable." }
    check(mode != GuardMode.VISIBILITY || canArmVisibility()) {
      "Visibility menu closing is unavailable or a previous controller is still owned."
    }
    if (preferences.getBoolean(KEY_ARMED, false)) onDisarm("kiosk-target-replaced")
    val config =
      GuardConfig(
        generation =
          GuardGenerationPolicy.next(
            previousGeneration = preferences.getLong(KEY_GENERATION, 0L),
            candidate = generationSource,
          ),
        target = target,
        targetLabel = targetLabel,
        mode = mode,
        foregroundSignalProtocolVersion = foregroundSignalCapability?.protocolVersion,
        targetSigningIdentity = foregroundSignalCapability?.signingIdentity,
        targetPackageLastUpdateTime = foregroundSignalCapability?.packageLastUpdateTime,
        targetPackageVersionCode = foregroundSignalCapability?.packageVersionCode,
      )
    val saved =
      preferences.edit()
        .putBoolean(KEY_ARMED, true)
        .putLong(KEY_GENERATION, config.generation)
        .putString(KEY_MODE, mode.id)
        .putString(KEY_TARGET_PACKAGE, target.packageName)
        .putString(KEY_TARGET_ACTIVITY, target.activityName)
        .putString(KEY_TARGET_ACTION, target.action)
        .putStringSet(KEY_TARGET_CATEGORIES, target.categories)
        .putString(KEY_TARGET_LABEL, targetLabel)
        .putInt(
          KEY_FOREGROUND_SIGNAL_PROTOCOL,
          foregroundSignalCapability?.protocolVersion ?: NO_FOREGROUND_SIGNAL_PROTOCOL,
        )
        .putString(KEY_TARGET_SIGNING_IDENTITY, foregroundSignalCapability?.signingIdentity)
        .putLong(
          KEY_TARGET_PACKAGE_LAST_UPDATE_TIME,
          foregroundSignalCapability?.packageLastUpdateTime ?: NO_PACKAGE_IDENTITY,
        )
        .putLong(
          KEY_TARGET_PACKAGE_VERSION_CODE,
          foregroundSignalCapability?.packageVersionCode ?: NO_PACKAGE_IDENTITY,
        )
        .putString(KEY_LAST_DISARM_REASON, "")
        .commit()
    check(saved) { "Could not persist foreground guard state." }
    if (mode == GuardMode.VISIBILITY) {
      try { onVisibilityArmed(config) }
      catch (failure: Throwable) { disarm("visibility-arm-failed"); throw failure }
    }
    if (mode == GuardMode.SHELL) {
      try { onShellArmed(config) }
      catch (failure: Throwable) { disarm("shell-arm-failed"); throw failure }
    }
    return config
  }

  fun disarm(reason: String) {
    onDisarm(reason)
    check(preferences.edit()
      .putBoolean(KEY_ARMED, false)
      .putString(KEY_LAST_DISARM_REASON, reason.take(120))
      .commit()) { "Could not persist foreground guard disarm." }
  }

  fun selectedMode(): GuardMode = GuardMode.fromId(preferences.getString(KEY_SELECTED_MODE, null))

  fun setSelectedMode(mode: GuardMode) {
    check(mode != GuardMode.UNAVAILABLE)
    if (mode != selectedMode()) disarm("kiosk-mode-changed")
    check(preferences.edit().putString(KEY_SELECTED_MODE, mode.id).commit()) {
      "Could not persist kiosk mode."
    }
  }

  fun loadArmed(): GuardConfig? {
    if (!preferences.getBoolean(KEY_ARMED, false)) return null
    val packageName = preferences.getString(KEY_TARGET_PACKAGE, null) ?: return null
    val activityName = preferences.getString(KEY_TARGET_ACTIVITY, null) ?: return null
    val action = preferences.getString(KEY_TARGET_ACTION, null) ?: Intent.ACTION_MAIN
    val categories = preferences.getStringSet(KEY_TARGET_CATEGORIES, emptySet())?.toSet().orEmpty()
    val label = preferences.getString(KEY_TARGET_LABEL, null) ?: packageName
    if (categories.isEmpty()) return null
    val generation = preferences.getLong(KEY_GENERATION, 0L)
    if (generation == 0L) return null
    val foregroundSignalProtocolVersion =
      preferences.getInt(
        KEY_FOREGROUND_SIGNAL_PROTOCOL,
        NO_FOREGROUND_SIGNAL_PROTOCOL,
      ).takeIf { it > NO_FOREGROUND_SIGNAL_PROTOCOL }
    val targetSigningIdentity =
      preferences.getString(KEY_TARGET_SIGNING_IDENTITY, null)?.takeIf(String::isNotBlank)
    val targetPackageLastUpdateTime =
      preferences
        .getLong(KEY_TARGET_PACKAGE_LAST_UPDATE_TIME, NO_PACKAGE_IDENTITY)
        .takeIf { it > NO_PACKAGE_IDENTITY }
    val targetPackageVersionCode =
      preferences
        .getLong(KEY_TARGET_PACKAGE_VERSION_CODE, NO_PACKAGE_IDENTITY)
        .takeIf { it >= 0L }
    val foregroundSignalCapabilityPresent =
      foregroundSignalProtocolVersion != null &&
        targetSigningIdentity != null &&
        targetPackageLastUpdateTime != null &&
        targetPackageVersionCode != null
    return GuardConfig(
      generation = generation,
      target = LaunchTarget(packageName, activityName, action, categories),
      targetLabel = label,
      mode = GuardMode.fromId(preferences.getString(KEY_MODE, null)),
      foregroundSignalProtocolVersion =
        foregroundSignalProtocolVersion.takeIf { foregroundSignalCapabilityPresent },
      targetSigningIdentity =
        targetSigningIdentity.takeIf { foregroundSignalCapabilityPresent },
      targetPackageLastUpdateTime =
        targetPackageLastUpdateTime.takeIf { foregroundSignalCapabilityPresent },
      targetPackageVersionCode =
        targetPackageVersionCode.takeIf { foregroundSignalCapabilityPresent },
    )
  }

  companion object {
    private const val PREFERENCES = "rusty_kiosk_guard_state"
    private const val KEY_ARMED = "armed"
    private const val KEY_MODE = "mode"
    private const val KEY_SELECTED_MODE = "selected_mode"
    private const val KEY_GENERATION = "generation"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_TARGET_ACTIVITY = "target_activity"
    private const val KEY_TARGET_ACTION = "target_action"
    private const val KEY_TARGET_CATEGORIES = "target_categories"
    private const val KEY_TARGET_LABEL = "target_label"
    private const val KEY_FOREGROUND_SIGNAL_PROTOCOL = "foreground_signal_protocol"
    private const val KEY_TARGET_SIGNING_IDENTITY = "target_signing_identity"
    private const val KEY_TARGET_PACKAGE_LAST_UPDATE_TIME = "target_package_last_update_time"
    private const val KEY_TARGET_PACKAGE_VERSION_CODE = "target_package_version_code"
    private const val KEY_LAST_DISARM_REASON = "last_disarm_reason"
    private const val NO_FOREGROUND_SIGNAL_PROTOCOL = 0
    private const val NO_PACKAGE_IDENTITY = -1L
  }
}
