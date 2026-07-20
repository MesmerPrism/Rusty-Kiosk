package io.github.mesmerprism.rustykiosk

import android.content.Intent

internal object GuardContract {
  const val ACTION_RETURN_TO_KIOSK =
    "io.github.mesmerprism.rustykiosk.action.FOREGROUND_GUARD_RETURN"
  const val HOME_TRIGGER_COUNT = 3
  const val HOME_TRIGGER_WINDOW_MS = 5_000L
  const val HOME_INVOCATION_DEBOUNCE_MS = 1_200L
  const val HOME_RECOVERY_SETTLE_MS = 500L
  const val RECOVERY_GRACE_MS = 0L

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
) {
  init {
    require(target.packageName.isNotBlank())
    require(target.activityName.isNotBlank())
    require(target.action.isNotBlank())
    require(target.categories.isNotEmpty())
    require(targetLabel.isNotBlank())
  }
}

internal fun GuardConfig.toTargetIntent(): Intent =
  Intent(target.action)
    .setClassName(target.packageName, target.activityName)
    .also { intent -> target.categories.forEach(intent::addCategory) }
    .addFlags(
      Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    )
