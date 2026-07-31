package io.github.mesmerprism.rustykiosk

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class SetupHelperOperation(val wireName: String) {
  STATUS("status"),
  REQUEST_WIFI_ADB("request_wifi_adb"),
  DISABLE_WIFI_ADB("disable_wifi_adb"),
  ENABLE_ACCESSIBILITY("enable_accessibility"),
  DISABLE_ACCESSIBILITY("disable_accessibility"),
  ENABLE_WIFI_AFTER_BOOT("enable_wifi_after_boot"),
  DISABLE_WIFI_AFTER_BOOT("disable_wifi_after_boot"),
  ;

  companion object {
    fun fromWireName(value: String?): SetupHelperOperation? = entries.firstOrNull { it.wireName == value }
  }
}

internal data class SetupHelperResult(
  val requestId: Long,
  val operation: SetupHelperOperation,
  val success: Boolean,
  val helperReady: Boolean,
  val requestAfterBoot: Boolean,
  val message: String,
)

internal object SetupHelperProtocol {
  val HELPER_PACKAGE: String = BuildConfig.SETUP_HELPER_PACKAGE
  val CONTROL_PERMISSION: String = BuildConfig.SETUP_CONTROL_PERMISSION
  val ACTION_CONTROL: String = BuildConfig.SETUP_CONTROL_ACTION
  val RECEIVER_CLASS: String =
    "io.github.mesmerprism.rustykiosk.setuphelper.SetupControlReceiver"
  const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
  const val EXTRA_REQUEST_ID = "request_id"
  const val EXTRA_OPERATION = "operation"
  const val EXTRA_SUCCESS = "success"
  const val EXTRA_HELPER_READY = "helper_ready"
  const val EXTRA_REQUEST_AFTER_BOOT = "request_after_boot"
  const val EXTRA_MESSAGE = "message"

  fun parse(
    requestId: Long,
    requestedOperation: SetupHelperOperation,
    extras: Bundle?,
  ): SetupHelperResult {
    require(extras != null) { "Setup helper returned no result." }
    return parseValues(
      requestId = requestId,
      requestedOperation = requestedOperation,
      returnedRequestId = extras.getLong(EXTRA_REQUEST_ID, -1L),
      returnedOperation = extras.getString(EXTRA_OPERATION),
      success = extras.getBoolean(EXTRA_SUCCESS, false),
      helperReady = extras.getBoolean(EXTRA_HELPER_READY, false),
      requestAfterBoot = extras.getBoolean(EXTRA_REQUEST_AFTER_BOOT, false),
      message = extras.getString(EXTRA_MESSAGE),
    )
  }

  fun parseValues(
    requestId: Long,
    requestedOperation: SetupHelperOperation,
    returnedRequestId: Long,
    returnedOperation: String?,
    success: Boolean,
    helperReady: Boolean,
    requestAfterBoot: Boolean,
    message: String?,
  ): SetupHelperResult {
    require(returnedRequestId == requestId) {
      "Setup helper returned a mismatched request id."
    }
    val operation = SetupHelperOperation.fromWireName(returnedOperation)
      ?: throw IllegalArgumentException("Setup helper returned an unknown operation.")
    require(operation == requestedOperation) { "Setup helper returned a mismatched operation." }
    return SetupHelperResult(
      requestId = requestId,
      operation = operation,
      success = success,
      helperReady = helperReady,
      requestAfterBoot = requestAfterBoot,
      message = message.orEmpty().ifBlank { "Setup helper returned no message." },
    )
  }
}

internal class SetupHelperControlClient(private val context: Context) {
  private val appContext = context.applicationContext
  private val handler = Handler(Looper.getMainLooper())
  private val store = SetupHelperResultStore(appContext)

  fun dispatch(
    operation: SetupHelperOperation,
    onResult: (SetupHelperResult) -> Unit,
  ): Result<Long> =
    runCatching {
      check(isInstalled()) { "Rusty Kiosk Setup is not installed." }
      check(
        appContext.checkSelfPermission(SetupHelperProtocol.CONTROL_PERMISSION) ==
          PackageManager.PERMISSION_GRANTED
      ) { "Rusty Kiosk Setup is not signed with the same key as the main app." }

      val requestId = REQUEST_ID.incrementAndGet()
      val completed = AtomicBoolean(false)
      val resultReceiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
            if (!completed.compareAndSet(false, true)) return
            val result =
              runCatching {
                  SetupHelperProtocol.parse(requestId, operation, getResultExtras(false))
                }
                .getOrElse { throwable ->
                  SetupHelperResult(
                    requestId = requestId,
                    operation = operation,
                    success = false,
                    helperReady = false,
                    requestAfterBoot = store.snapshot().requestAfterBoot,
                    message = throwable.message ?: "Setup helper returned an invalid result.",
                  )
                }
            store.record(result)
            onResult(result)
          }
        }
      val request =
        Intent(SetupHelperProtocol.ACTION_CONTROL)
          .setComponent(
            ComponentName(SetupHelperProtocol.HELPER_PACKAGE, SetupHelperProtocol.RECEIVER_CLASS)
          )
          .putExtra(SetupHelperProtocol.EXTRA_REQUEST_ID, requestId)
          .putExtra(SetupHelperProtocol.EXTRA_OPERATION, operation.wireName)

      store.markPending(requestId, operation)
      appContext.sendOrderedBroadcast(request, null, resultReceiver, handler, 0, null, null)
      handler.postDelayed(
        {
          if (!completed.compareAndSet(false, true)) return@postDelayed
          val result =
            SetupHelperResult(
              requestId = requestId,
              operation = operation,
              success = false,
              helperReady = store.snapshot().helperReady,
              requestAfterBoot = store.snapshot().requestAfterBoot,
              message = "Rusty Kiosk Setup did not answer within ${RESULT_TIMEOUT_MS / 1_000} seconds.",
            )
          store.record(result)
          onResult(result)
        },
        RESULT_TIMEOUT_MS,
      )
      requestId
    }
      .onFailure { throwable ->
        store.markFailure(throwable.message ?: "The fixed setup request could not be started.")
      }

  fun isInstalled(): Boolean =
    runCatching { appContext.packageManager.getPackageInfo(SetupHelperProtocol.HELPER_PACKAGE, 0) }
      .isSuccess

  fun hasWriteSecureSettings(): Boolean =
    isInstalled() &&
      appContext.packageManager.checkPermission(
        SetupHelperProtocol.WRITE_SECURE_SETTINGS,
        SetupHelperProtocol.HELPER_PACKAGE,
      ) == PackageManager.PERMISSION_GRANTED

  fun hasControlPermission(): Boolean =
    appContext.checkSelfPermission(SetupHelperProtocol.CONTROL_PERMISSION) ==
      PackageManager.PERMISSION_GRANTED

  private companion object {
    val REQUEST_ID = AtomicLong(System.currentTimeMillis())
    const val RESULT_TIMEOUT_MS = 8_000L
  }
}

internal data class StoredSetupHelperResult(
  val pendingRequestId: Long?,
  val pendingOperation: SetupHelperOperation?,
  val helperReady: Boolean,
  val requestAfterBoot: Boolean,
  val message: String?,
)

internal class SetupHelperResultStore(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun markPending(requestId: Long, operation: SetupHelperOperation) {
    preferences.edit()
      .putLong(KEY_PENDING_REQUEST, requestId)
      .putString(KEY_PENDING_OPERATION, operation.wireName)
      .putString(KEY_MESSAGE, "Rusty Kiosk Setup is applying ${operation.wireName.replace('_', ' ')}…")
      .apply()
  }

  fun record(result: SetupHelperResult) {
    if (preferences.getLong(KEY_PENDING_REQUEST, -1L) != result.requestId) return
    preferences.edit()
      .remove(KEY_PENDING_REQUEST)
      .remove(KEY_PENDING_OPERATION)
      .putBoolean(KEY_HELPER_READY, result.helperReady)
      .putBoolean(KEY_REQUEST_AFTER_BOOT, result.requestAfterBoot)
      .putString(KEY_MESSAGE, result.message)
      .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
      .apply()
  }

  fun markFailure(message: String) {
    preferences.edit()
      .remove(KEY_PENDING_REQUEST)
      .remove(KEY_PENDING_OPERATION)
      .putString(KEY_MESSAGE, message)
      .apply()
  }

  fun snapshot(): StoredSetupHelperResult {
    val pendingId = preferences.getLong(KEY_PENDING_REQUEST, -1L).takeIf { it > 0L }
    return StoredSetupHelperResult(
      pendingRequestId = pendingId,
      pendingOperation =
        preferences.getString(KEY_PENDING_OPERATION, null)?.let(SetupHelperOperation::fromWireName),
      helperReady = preferences.getBoolean(KEY_HELPER_READY, false),
      requestAfterBoot = preferences.getBoolean(KEY_REQUEST_AFTER_BOOT, false),
      message = preferences.getString(KEY_MESSAGE, null),
    )
  }

  private companion object {
    const val PREFERENCES = "rusty_kiosk_setup_results"
    const val KEY_PENDING_REQUEST = "pending_request"
    const val KEY_PENDING_OPERATION = "pending_operation"
    const val KEY_HELPER_READY = "helper_ready"
    const val KEY_REQUEST_AFTER_BOOT = "request_after_boot"
    const val KEY_MESSAGE = "message"
    const val KEY_LAST_RESULT_AT = "last_result_at"
  }
}
