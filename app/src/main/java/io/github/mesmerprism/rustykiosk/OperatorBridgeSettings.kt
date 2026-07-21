package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

internal data class OperatorBridgeSnapshot(
  val enabled: Boolean,
  val running: Boolean,
  val endpoint: String?,
  val pairingCode: String,
  val installerAllowed: Boolean,
  val lastError: String?,
)

internal class OperatorBridgeSettings(context: Context) {
  private val appContext = context.applicationContext
  private val preferences =
    appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun snapshot(): OperatorBridgeSnapshot =
    OperatorBridgeSnapshot(
      enabled = preferences.getBoolean(KEY_ENABLED, false),
      running = preferences.getBoolean(KEY_RUNNING, false),
      endpoint = preferredIpv4()?.let { "http://$it:$PORT" },
      pairingCode = pairingCode(),
      installerAllowed = appContext.packageManager.canRequestPackageInstalls(),
      lastError = preferences.getString(KEY_LAST_ERROR, null),
    )

  fun setEnabled(enabled: Boolean) {
    recordEnabled(enabled)
    if (enabled) {
      ContextCompat.startForegroundService(
        appContext,
        Intent(appContext, OperatorBridgeService::class.java).setAction(OperatorBridgeService.ACTION_START),
      )
    } else {
      appContext.startService(
        Intent(appContext, OperatorBridgeService::class.java).setAction(OperatorBridgeService.ACTION_STOP)
      )
    }
  }

  fun recordEnabled(enabled: Boolean) {
    preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()
  }

  fun ensureStartedIfEnabled() {
    if (preferences.getBoolean(KEY_ENABLED, false)) setEnabled(true)
  }

  fun rotatePairingCode(): String {
    val code = generatePairingCode()
    preferences.edit().putString(KEY_PAIRING_CODE, code).commit()
    setEnabled(false)
    return code
  }

  fun pairingCode(): String =
    preferences.getString(KEY_PAIRING_CODE, null)
      ?: generatePairingCode().also { generated ->
        preferences.edit().putString(KEY_PAIRING_CODE, generated).commit()
      }

  fun recordRunning(running: Boolean, error: String? = null) {
    preferences.edit()
      .putBoolean(KEY_RUNNING, running)
      .apply {
        if (error == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, error.take(240))
      }
      .commit()
  }

  private fun generatePairingCode(): String {
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    val symbols = CharArray(26)
    var buffer = 0
    var bits = 0
    var output = 0
    bytes.forEach { byte ->
      buffer = (buffer shl 8) or (byte.toInt() and 0xff)
      bits += 8
      while (bits >= 5 && output < symbols.size) {
        bits -= 5
        symbols[output++] = CROCKFORD[(buffer shr bits) and 31]
      }
    }
    if (bits > 0 && output < symbols.size) {
      symbols[output++] = CROCKFORD[(buffer shl (5 - bits)) and 31]
    }
    return symbols.concatToString(0, output).chunked(4).joinToString("-")
  }

  private fun preferredIpv4(): String? =
    runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
          .asSequence()
          .filter { it.isUp && !it.isLoopback }
          .flatMap { Collections.list(it.inetAddresses).asSequence() }
          .filterIsInstance<Inet4Address>()
          .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
          ?.hostAddress
      }
      .getOrNull()

  companion object {
    const val PORT = 39873
    private const val PREFERENCES = "rusty_kiosk_operator_bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RUNNING = "running"
    private const val KEY_PAIRING_CODE = "pairing_code"
    private const val KEY_LAST_ERROR = "last_error"
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
  }
}
