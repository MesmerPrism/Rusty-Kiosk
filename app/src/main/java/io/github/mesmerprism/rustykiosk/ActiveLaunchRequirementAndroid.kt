package io.github.mesmerprism.rustykiosk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings

internal fun interface WifiEnabledObserver {
  fun isWifiEnabled(): Boolean
}

internal class ActiveRequirementUnavailableException(message: String) : IllegalStateException(message)

internal class AndroidWifiEnabledObserver(context: Context) : WifiEnabledObserver {
  private val appContext = context.applicationContext

  override fun isWifiEnabled(): Boolean {
    val wifiManager = appContext.getSystemService(WifiManager::class.java)
      ?: throw ActiveRequirementUnavailableException("Android Wi-Fi state service is unavailable.")
    return wifiManager.isWifiEnabled
  }
}

internal class WifiActiveRequirementHandler(
  private val requirement: ActiveRequirementHandlerId,
  private val observer: WifiEnabledObserver,
) : ActiveRequirementHandler {
  override fun evaluate(): ActiveRequirementEvaluation =
    try {
      val enabled = observer.isWifiEnabled()
      val satisfied = when (requirement) {
        ActiveRequirementHandlerId.WIFI_ON -> enabled
        ActiveRequirementHandlerId.WIFI_OFF -> !enabled
      }
      ActiveRequirementEvaluation(
        handlerId = requirement,
        state = if (satisfied) ActiveRequirementEvaluationState.SATISFIED
          else ActiveRequirementEvaluationState.UNSATISFIED,
        provenance = WIFI_PROVENANCE,
        reason = when {
          requirement == ActiveRequirementHandlerId.WIFI_ON && satisfied ->
            "Android reports that ordinary Wi-Fi is on."
          requirement == ActiveRequirementHandlerId.WIFI_ON ->
            "This app requires ordinary Wi-Fi to be on."
          requirement == ActiveRequirementHandlerId.WIFI_OFF && satisfied ->
            "Android reports that ordinary Wi-Fi is off."
          else -> "This app requires ordinary Wi-Fi to be off."
        },
      )
    } catch (_: SecurityException) {
      ActiveRequirementEvaluation(
        requirement,
        ActiveRequirementEvaluationState.UNAVAILABLE,
        WIFI_PROVENANCE,
        "Android did not permit reading ordinary Wi-Fi state.",
      )
    } catch (throwable: ActiveRequirementUnavailableException) {
      ActiveRequirementEvaluation(
        requirement,
        ActiveRequirementEvaluationState.UNAVAILABLE,
        WIFI_PROVENANCE,
        throwable.message ?: "Android Wi-Fi state is unavailable.",
      )
    } catch (throwable: Throwable) {
      ActiveRequirementEvaluation(
        requirement,
        ActiveRequirementEvaluationState.ERROR,
        WIFI_PROVENANCE,
        "Wi-Fi state evaluation failed: ${throwable.javaClass.simpleName}",
      )
    }

  private companion object {
    const val WIFI_PROVENANCE = "android.wifi_manager.is_wifi_enabled"
  }
}

internal object ProductionActiveRequirementHandlers {
  fun create(context: Context): ActiveRequirementHandlerRegistry {
    val observer = AndroidWifiEnabledObserver(context)
    return ActiveRequirementHandlerRegistry(
      listOf(
        ActiveRequirementHandlerId.WIFI_ON to
          WifiActiveRequirementHandler(ActiveRequirementHandlerId.WIFI_ON, observer),
        ActiveRequirementHandlerId.WIFI_OFF to
          WifiActiveRequirementHandler(ActiveRequirementHandlerId.WIFI_OFF, observer),
      )
    )
  }
}

/** Opens one fixed Android-owned Wi-Fi settings surface and never mutates Wi-Fi itself. */
internal class AndroidWifiSettingsRemediator(private val activity: Activity) {
  fun open(): Boolean = runCatching {
    activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    true
  }.getOrDefault(false)
}
