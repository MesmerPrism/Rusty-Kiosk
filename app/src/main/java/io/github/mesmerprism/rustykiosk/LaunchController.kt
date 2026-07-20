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
)

internal class LaunchController(context: Context) {
  private val appContext = context.applicationContext
  private val guardStore = GuardStateStore(appContext)

  fun launch(entry: CatalogEntry, kind: LaunchKind, guardEnabled: Boolean): LaunchResult {
    val target = entry.target
      ?: return LaunchResult(false, "${entry.label} does not have a public launch activity.")
    if (!entry.installed) return LaunchResult(false, "${entry.label} is not installed.")

    if (kind == LaunchKind.KIOSK && !guardEnabled) {
      return LaunchResult(false, "Enable the Rusty Kiosk Accessibility service first.")
    }

    if (kind == LaunchKind.NORMAL) {
      guardStore.disarm("normal-launch")
    } else {
      guardStore.arm(target, entry.label)
    }

    return runCatching {
        appContext.startActivity(
          target.toIntent(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
          )
        )
        LaunchResult(
          true,
          if (kind == LaunchKind.KIOSK) {
            "Kiosk launched ${entry.label}. Triple-Home returns to Rusty Kiosk."
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

  fun disarm(reason: String) = guardStore.disarm(reason)

  private fun LaunchTarget.toIntent(flags: Int): Intent =
    Intent(action)
      .setClassName(packageName, activityName)
      .also { intent -> categories.forEach(intent::addCategory) }
      .addFlags(flags)
}
