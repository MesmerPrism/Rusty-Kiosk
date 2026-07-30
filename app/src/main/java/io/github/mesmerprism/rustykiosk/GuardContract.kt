package io.github.mesmerprism.rustykiosk

import android.content.Intent

internal object GuardContract {
  const val ACTION_RETURN_TO_KIOSK =
    "io.github.mesmerprism.rustykiosk.action.FOREGROUND_GUARD_RETURN"
  const val HOME_TRIGGER_COUNT = 3
  const val HOME_TRIGGER_WINDOW_MS = 5_000L
  const val HOME_INVOCATION_DEBOUNCE_MS = 1_200L
  const val HOME_SIGNAL_REPEAT_QUIET_MS = 64L
  const val DIRECT_SIGNAL_REPEAT_QUIET_MS = 250L
  const val INITIAL_HANDOFF_QUIET_MS = 160L
  const val RECOVERY_GRACE_MS = 0L
  const val RECOVERY_CONFIRMATION_QUIET_MS = 160L
  const val RECOVERY_RETRY_MIN_INTERVAL_MS = 160L
  const val RECOVERY_MAX_ATTEMPTS = 3

  val metaShellPackages =
    setOf(
      "com.meta.xr.shell",
      "com.oculus.home",
      "com.oculus.horizon",
      "com.oculus.shellenv",
      "com.oculus.systemux",
      "com.oculus.vrshell",
      "com.oculus.panelapp.library",
    )

  private val metaHomeInvocationClasses =
    mapOf(
      "com.oculus.panelapp.library" to
        setOf("com.oculus.navigator.library.app.NavigatorLibraryActivity"),
      "com.oculus.vrshell" to
        setOf(
          "com.oculus.vrshell.FocusPlaceholderActivity",
          "com.oculus.vrshell.HomeActivity",
        ),
    )

  fun isMetaHomeInvocation(packageName: String, className: String): Boolean =
    className in metaHomeInvocationClasses[packageName].orEmpty()
}

internal data class GuardConfig(
  val generation: Long,
  val target: LaunchTarget,
  val targetLabel: String,
  val foregroundSignalProtocolVersion: Int? = null,
  val targetSigningIdentity: String? = null,
  val targetPackageLastUpdateTime: Long? = null,
  val targetPackageVersionCode: Long? = null,
) {
  init {
    require(generation != 0L)
    require(target.packageName.isNotBlank())
    require(target.activityName.isNotBlank())
    require(target.action.isNotBlank())
    require(target.categories.isNotEmpty())
    require(targetLabel.isNotBlank())
    require(
      (
        foregroundSignalProtocolVersion == null &&
          targetSigningIdentity == null &&
          targetPackageLastUpdateTime == null &&
          targetPackageVersionCode == null
      ) ||
        (
          foregroundSignalProtocolVersion ==
            io.github.mesmerprism.rustykiosk.foregroundsignal.ForegroundSignalContract
              .PROTOCOL_VERSION &&
            !targetSigningIdentity.isNullOrBlank() &&
            targetPackageLastUpdateTime != null &&
            targetPackageLastUpdateTime > 0L &&
            targetPackageVersionCode != null &&
            targetPackageVersionCode >= 0L
        )
    )
  }
}

internal object GuardLaunchTaskPolicy {
  fun recoveryFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or
      Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
      Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
      Intent.FLAG_ACTIVITY_NO_ANIMATION
}

internal fun GuardConfig.toTargetIntent(): Intent =
  Intent(target.action)
    .setClassName(target.packageName, target.activityName)
    .also { intent -> target.categories.forEach(intent::addCategory) }
    .addFlags(GuardLaunchTaskPolicy.recoveryFlags())
