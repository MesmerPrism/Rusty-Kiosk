package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.content.Intent

internal class GuardStateStore(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun arm(target: LaunchTarget, targetLabel: String): GuardConfig {
    val config =
      GuardConfig(
        generation = System.currentTimeMillis(),
        target = target,
        targetLabel = targetLabel,
      )
    val saved =
      preferences.edit()
        .putBoolean(KEY_ARMED, true)
        .putLong(KEY_GENERATION, config.generation)
        .putString(KEY_TARGET_PACKAGE, target.packageName)
        .putString(KEY_TARGET_ACTIVITY, target.activityName)
        .putString(KEY_TARGET_ACTION, target.action)
        .putStringSet(KEY_TARGET_CATEGORIES, target.categories)
        .putString(KEY_TARGET_LABEL, targetLabel)
        .putString(KEY_LAST_DISARM_REASON, "")
        .commit()
    check(saved) { "Could not persist foreground guard state." }
    return config
  }

  fun disarm(reason: String) {
    preferences.edit()
      .putBoolean(KEY_ARMED, false)
      .putString(KEY_LAST_DISARM_REASON, reason.take(120))
      .commit()
  }

  fun loadArmed(): GuardConfig? {
    if (!preferences.getBoolean(KEY_ARMED, false)) return null
    val packageName = preferences.getString(KEY_TARGET_PACKAGE, null) ?: return null
    val activityName = preferences.getString(KEY_TARGET_ACTIVITY, null) ?: return null
    val action = preferences.getString(KEY_TARGET_ACTION, null) ?: Intent.ACTION_MAIN
    val categories = preferences.getStringSet(KEY_TARGET_CATEGORIES, emptySet())?.toSet().orEmpty()
    val label = preferences.getString(KEY_TARGET_LABEL, null) ?: packageName
    if (categories.isEmpty()) return null
    return GuardConfig(
      generation = preferences.getLong(KEY_GENERATION, 0L),
      target = LaunchTarget(packageName, activityName, action, categories),
      targetLabel = label,
    )
  }

  companion object {
    private const val PREFERENCES = "rusty_kiosk_guard_state"
    private const val KEY_ARMED = "armed"
    private const val KEY_GENERATION = "generation"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_TARGET_ACTIVITY = "target_activity"
    private const val KEY_TARGET_ACTION = "target_action"
    private const val KEY_TARGET_CATEGORIES = "target_categories"
    private const val KEY_TARGET_LABEL = "target_label"
    private const val KEY_LAST_DISARM_REASON = "last_disarm_reason"
  }
}
