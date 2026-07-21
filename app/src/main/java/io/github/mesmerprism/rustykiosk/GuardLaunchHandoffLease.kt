package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.os.SystemClock

internal data class GuardLaunchHandoffLeaseSnapshot(
  val targetPackage: String,
  val issuedAtElapsedMs: Long,
  val expiresAtElapsedMs: Long,
)

internal object GuardLaunchHandoffLeasePolicy {
  fun shouldIgnoreOwnForeground(
    lease: GuardLaunchHandoffLeaseSnapshot?,
    armedTargetPackage: String,
    nowElapsedMs: Long,
  ): Boolean =
    lease != null &&
      lease.targetPackage == armedTargetPackage &&
      nowElapsedMs >= lease.issuedAtElapsedMs &&
      nowElapsedMs <= lease.expiresAtElapsedMs
}

/**
 * Bridges the short Activity-to-target handoff for both visible and typed kiosk launches.
 *
 * Quest can deliver a trailing Rusty Kiosk window event after the target is armed. Ignoring only
 * matching self-events inside this target-scoped window prevents that stale event from disarming
 * the guard. A genuine return to Rusty Kiosk still disarms in the Activity lifecycle, and this
 * lease never suppresses Meta Home or other-package events.
 */
internal class GuardLaunchHandoffLease(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun arm(targetPackage: String) {
    val issuedAt = SystemClock.elapsedRealtime()
    preferences.edit()
      .putString(KEY_TARGET_PACKAGE, targetPackage)
      .putLong(KEY_ISSUED_AT, issuedAt)
      .putLong(KEY_EXPIRES_AT, issuedAt + LEASE_DURATION_MS)
      .commit()
  }

  fun shouldIgnoreOwnForeground(armedTargetPackage: String): Boolean {
    val shouldIgnore =
      GuardLaunchHandoffLeasePolicy.shouldIgnoreOwnForeground(
        lease = snapshot(),
        armedTargetPackage = armedTargetPackage,
        nowElapsedMs = SystemClock.elapsedRealtime(),
      )
    if (!shouldIgnore) clear()
    return shouldIgnore
  }

  fun clear() {
    preferences.edit().clear().commit()
  }

  private fun snapshot(): GuardLaunchHandoffLeaseSnapshot? {
    val targetPackage = preferences.getString(KEY_TARGET_PACKAGE, null) ?: return null
    return GuardLaunchHandoffLeaseSnapshot(
      targetPackage = targetPackage,
      issuedAtElapsedMs = preferences.getLong(KEY_ISSUED_AT, Long.MAX_VALUE),
      expiresAtElapsedMs = preferences.getLong(KEY_EXPIRES_AT, Long.MIN_VALUE),
    )
  }

  private companion object {
    const val PREFERENCES = "rusty_kiosk_guard_launch_handoff_lease"
    const val KEY_TARGET_PACKAGE = "target_package"
    const val KEY_ISSUED_AT = "issued_at_elapsed_ms"
    const val KEY_EXPIRES_AT = "expires_at_elapsed_ms"
    const val LEASE_DURATION_MS = 5_000L
  }
}
