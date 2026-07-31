package io.github.mesmerprism.rustykiosk.setuphelper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings

internal enum class SetupOperation(val wireName: String) {
  STATUS("status"),
  REQUEST_WIFI_ADB("request_wifi_adb"),
  DISABLE_WIFI_ADB("disable_wifi_adb"),
  ENABLE_ACCESSIBILITY("enable_accessibility"),
  DISABLE_ACCESSIBILITY("disable_accessibility"),
  ENABLE_WIFI_AFTER_BOOT("enable_wifi_after_boot"),
  DISABLE_WIFI_AFTER_BOOT("disable_wifi_after_boot"),
  ;

  companion object {
    fun fromWireName(value: String?): SetupOperation? = entries.firstOrNull { it.wireName == value }
  }
}

internal object SetupContract {
  val ACTION_CONTROL: String = BuildConfig.CONTROL_ACTION
  const val EXTRA_REQUEST_ID = "request_id"
  const val EXTRA_OPERATION = "operation"
  const val EXTRA_SUCCESS = "success"
  const val EXTRA_HELPER_READY = "helper_ready"
  const val EXTRA_REQUEST_AFTER_BOOT = "request_after_boot"
  const val EXTRA_MESSAGE = "message"
  val ACCESSIBILITY_COMPONENT: String =
    "${BuildConfig.KIOSK_PACKAGE}/io.github.mesmerprism.rustykiosk.KioskAccessibilityService"
  const val WIFI_ADB_SETTING = "adb_wifi_enabled"
  const val WIFI_ALLOWED_CONNECTION_TIME_SETTING = "adb_allowed_connection_time"
}

internal object AccessibilityServiceList {
  fun parse(value: String?): List<String> =
    value.orEmpty().split(':').map(String::trim).filter(String::isNotEmpty).distinct()

  fun enable(value: String?, component: String): String =
    (parse(value) + component).distinct().joinToString(":")

  fun disable(value: String?, component: String): String? =
    parse(value).filterNot { it == component }.joinToString(":").ifEmpty { null }

  fun contains(value: String?, component: String): Boolean = component in parse(value)
}

internal data class SetupResult(
  val operation: SetupOperation,
  val success: Boolean,
  val helperReady: Boolean,
  val requestAfterBoot: Boolean,
  val message: String,
)

internal class SetupExecutor(private val context: Context) {
  private val resolver = context.contentResolver
  private val preferences =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun execute(operation: SetupOperation): SetupResult {
    val ready = hasWriteSecureSettings()
    if (operation != SetupOperation.STATUS && !ready) {
      return result(
        operation,
        false,
        "Setup authority is not provisioned. Connect USB-C once and run the Rusty Kiosk provisioning script.",
      )
    }

    return runCatching {
        when (operation) {
          SetupOperation.STATUS ->
            result(
              operation,
              true,
              if (ready) "Dedicated setup helper is installed and provisioned." else
                "Setup helper is installed but still needs one USB-C provisioning step.",
            )
          SetupOperation.REQUEST_WIFI_ADB -> requestWifiAdb(operation)
          SetupOperation.DISABLE_WIFI_ADB -> disableWifiAdb(operation)
          SetupOperation.ENABLE_ACCESSIBILITY -> enableAccessibility(operation)
          SetupOperation.DISABLE_ACCESSIBILITY -> disableAccessibility(operation)
          SetupOperation.ENABLE_WIFI_AFTER_BOOT -> setRequestAfterBoot(operation, true)
          SetupOperation.DISABLE_WIFI_AFTER_BOOT -> setRequestAfterBoot(operation, false)
        }
      }
      .getOrElse { throwable ->
        result(
          operation,
          false,
          throwable.message ?: "The fixed setup operation failed.",
        )
      }
  }

  private fun requestWifiAdb(operation: SetupOperation): SetupResult {
    check(Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1))
    check(Settings.Global.putInt(resolver, SetupContract.WIFI_ADB_SETTING, 1))
    check(Settings.Global.putLong(resolver, SetupContract.WIFI_ALLOWED_CONNECTION_TIME_SETTING, 0L))
    val requested = Settings.Global.getInt(resolver, SetupContract.WIFI_ADB_SETTING, 0) == 1
    return result(
      operation,
      requested,
      if (requested) {
        "Wi-Fi ADB requested. Approve Meta's visible system prompt if it appears."
      } else {
        "Wi-Fi ADB did not read back as enabled. No Accessibility setting was changed."
      },
    )
  }

  private fun disableWifiAdb(operation: SetupOperation): SetupResult {
    check(Settings.Global.putInt(resolver, SetupContract.WIFI_ADB_SETTING, 0))
    val disabled = Settings.Global.getInt(resolver, SetupContract.WIFI_ADB_SETTING, 0) != 1
    return result(
      operation,
      disabled,
      if (disabled) "Wi-Fi ADB disabled. Accessibility was not changed." else
        "Wi-Fi ADB did not read back as disabled.",
    )
  }

  private fun enableAccessibility(operation: SetupOperation): SetupResult {
    val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    val updated = AccessibilityServiceList.enable(current, SetupContract.ACCESSIBILITY_COMPONENT)
    check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated))
    check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
    val enabled = accessibilityEnabled()
    return result(
      operation,
      enabled,
      if (enabled) "Rusty Kiosk Accessibility enabled. Other enabled services were preserved." else
        "Accessibility did not read back as enabled.",
    )
  }

  private fun disableAccessibility(operation: SetupOperation): SetupResult {
    val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    val updated = AccessibilityServiceList.disable(current, SetupContract.ACCESSIBILITY_COMPONENT)
    check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated))
    check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (updated == null) 0 else 1))
    val disabled = !accessibilityEnabled()
    return result(
      operation,
      disabled,
      if (disabled) "Rusty Kiosk Accessibility disabled. Other enabled services were preserved." else
        "Accessibility did not read back as disabled.",
    )
  }

  private fun setRequestAfterBoot(operation: SetupOperation, enabled: Boolean): SetupResult {
    check(preferences.edit().putBoolean(KEY_REQUEST_AFTER_BOOT, enabled).commit())
    return result(
      operation,
      requestAfterBoot() == enabled,
      if (enabled) {
        "Restart request enabled. Meta may ask for visible Wi-Fi ADB approval after each boot."
      } else {
        "Restart request disabled. Wi-Fi ADB will not be requested automatically after boot."
      },
    )
  }

  private fun accessibilityEnabled(): Boolean {
    val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    val flag = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    return flag && AccessibilityServiceList.contains(services, SetupContract.ACCESSIBILITY_COMPONENT)
  }

  fun requestAfterBoot(): Boolean = preferences.getBoolean(KEY_REQUEST_AFTER_BOOT, false)

  private fun hasWriteSecureSettings(): Boolean =
    context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
      PackageManager.PERMISSION_GRANTED

  private fun result(operation: SetupOperation, success: Boolean, message: String) =
    SetupResult(
      operation = operation,
      success = success,
      helperReady = hasWriteSecureSettings(),
      requestAfterBoot = requestAfterBoot(),
      message = message,
    )

  private companion object {
    const val PREFERENCES = "rusty_kiosk_setup"
    const val KEY_REQUEST_AFTER_BOOT = "request_wifi_adb_after_boot"
  }
}

class SetupControlReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != SetupContract.ACTION_CONTROL) return
    val requestId = intent.getLongExtra(SetupContract.EXTRA_REQUEST_ID, -1L)
    val operation = SetupOperation.fromWireName(intent.getStringExtra(SetupContract.EXTRA_OPERATION))
    val result =
      if (requestId > 0L && operation != null) {
        SetupExecutor(context).execute(operation)
      } else {
        SetupResult(
          operation = SetupOperation.STATUS,
          success = false,
          helperReady = false,
          requestAfterBoot = false,
          message = "Rejected malformed setup request.",
        )
      }
    setResultCode(if (result.success) RESULT_OK else RESULT_FAILED)
    setResultExtras(
      Bundle().apply {
        putLong(SetupContract.EXTRA_REQUEST_ID, requestId)
        putString(SetupContract.EXTRA_OPERATION, result.operation.wireName)
        putBoolean(SetupContract.EXTRA_SUCCESS, result.success)
        putBoolean(SetupContract.EXTRA_HELPER_READY, result.helperReady)
        putBoolean(SetupContract.EXTRA_REQUEST_AFTER_BOOT, result.requestAfterBoot)
        putString(SetupContract.EXTRA_MESSAGE, result.message)
      }
    )
  }

  private companion object {
    const val RESULT_OK = -1
    const val RESULT_FAILED = 1
  }
}

class BootRequestReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val executor = SetupExecutor(context)
    if (executor.requestAfterBoot()) executor.execute(SetupOperation.REQUEST_WIFI_ADB)
  }
}
