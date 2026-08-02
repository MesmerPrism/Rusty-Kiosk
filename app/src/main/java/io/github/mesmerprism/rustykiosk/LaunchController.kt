package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.content.Intent

internal enum class LaunchKind {
  NORMAL,
  KIOSK,
}

internal data class LaunchResult(
  val accepted: Boolean,
  val message: String,
  val completed: Boolean = true,
)

internal object LaunchTaskPolicy {
  fun initialFlags(kind: LaunchKind): Int =
    when (kind) {
      LaunchKind.NORMAL ->
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
      LaunchKind.KIOSK ->
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}

internal class LaunchController(context: Context) {
  private val appContext = context.applicationContext
  private val guardStore = GuardStateStore(appContext)
  private val foregroundSignalCapability = ForegroundSignalCapabilityDetector(appContext)

  fun launch(entry: CatalogEntry, kind: LaunchKind, guardEnabled: Boolean): LaunchResult {
    val target = entry.target
      ?: return LaunchResult(false, "${entry.label} does not have a public launch activity.")
    if (!entry.installed) return LaunchResult(false, "${entry.label} is not installed.")

    if (kind == LaunchKind.KIOSK && !guardEnabled) {
      return LaunchResult(false, "Enable the Rusty Kiosk Accessibility service first.")
    }
    val foregroundSignalCapability =
      if (kind == LaunchKind.KIOSK) {
        foregroundSignalCapability.supported(target.packageName)
      } else {
        null
      }

    if (kind == LaunchKind.NORMAL) {
      guardStore.disarm("normal-launch")
    } else {
      guardStore.arm(
        target,
        entry.label,
        foregroundSignalCapability,
      )
    }

    return runCatching {
        appContext.startActivity(target.toIntent(LaunchTaskPolicy.initialFlags(kind)))
        LaunchResult(
          true,
          if (kind == LaunchKind.KIOSK) {
            val signalMode =
              if (
                foregroundSignalCapability != null
              ) {
                " Direct foreground signaling is preferred with Accessibility fallback."
              } else {
                " Accessibility foreground events provide the fallback-only signal."
              }
            "Fresh kiosk launched ${entry.label}. Triple-Home returns to Rusty Kiosk.$signalMode"
          } else {
            "Launched ${entry.label} normally."
          },
        )
      }
      .getOrElse { throwable ->
        guardStore.disarm("launch-failed")
        LaunchResult(false, "Launch failed: ${throwable.javaClass.simpleName}")
      }
  }

  fun launchOption(
    entry: CatalogEntry,
    binding: AppLaunchOptionsBinding,
    option: AppLaunchOption,
  ): LaunchResult {
    val plan = runCatching { AppLaunchOptionDispatchPolicy.create(entry, binding, option) }
      .getOrElse {
        return LaunchResult(false, "The app launch-option binding changed before dispatch.")
      }
    guardStore.disarm("app-launch-option")
    return runCatching {
        val intent = plan.target.toIntent(LaunchTaskPolicy.initialFlags(LaunchKind.NORMAL))
        intent.putExtra(AppLaunchOptionsContract.EXTRA_LAUNCH_OPTION_ID, plan.optionId)
        appContext.startActivity(intent)
        LaunchResult(true, "Dispatched ${entry.label}: ${option.displayLabel}.")
      }
      .getOrElse { throwable ->
        guardStore.disarm("app-launch-option-failed")
        LaunchResult(false, "Launch failed: ${throwable.javaClass.simpleName}")
      }
  }

  fun disarm(reason: String) = guardStore.disarm(reason)

  private fun LaunchTarget.toIntent(flags: Int): Intent =
    Intent(action)
      .setClassName(packageName, activityName)
      .also { intent -> categories.forEach(intent::addCategory) }
      .addFlags(flags)
}
