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
  val bridgeGeneration: Long,
)

internal enum class OperatorBridgeRequestedAction { START, STOP }

internal object OperatorBridgeActionPolicy {
  fun shouldApply(
    action: OperatorBridgeRequestedAction,
    expectedGeneration: Long,
    currentGeneration: Long,
    enabled: Boolean,
  ): Boolean = expectedGeneration > 0L && expectedGeneration == currentGeneration &&
    enabled == (action == OperatorBridgeRequestedAction.START)

  fun isEffectivelyRunning(
    enabled: Boolean,
    currentGeneration: Long,
    recordedRunning: Boolean,
    runningGeneration: Long,
  ): Boolean = enabled && recordedRunning && currentGeneration > 0L &&
    runningGeneration == currentGeneration
}

internal object OperatorBridgePairingCodePresentation {
  fun render(pairingCode: String, visible: Boolean): String =
    if (visible) pairingCode else pairingCode.map { if (it == '-') '-' else '•' }.joinToString("")
}

internal class OperatorBridgeSettings(context: Context) {
  private val appContext = context.applicationContext
  private val preferences =
    appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun snapshot(): OperatorBridgeSnapshot = synchronized(STATE_LOCK) {
    val enabled = preferences.getBoolean(KEY_ENABLED, false)
    val generation = generationLocked()
    OperatorBridgeSnapshot(
      enabled = enabled,
      running = OperatorBridgeActionPolicy.isEffectivelyRunning(
        enabled,
        generation,
        preferences.getBoolean(KEY_RUNNING, false),
        preferences.getLong(KEY_RUNNING_GENERATION, 0L),
      ),
      endpoint = preferredIpv4()?.let { "http://$it:$PORT" },
      pairingCode = pairingCode(),
      installerAllowed = appContext.packageManager.canRequestPackageInstalls(),
      lastError = preferences.getString(KEY_LAST_ERROR, null),
      bridgeGeneration = generation,
    )
  }

  /** Returns true only when this request changed a wearer-disabled bridge to enabled. */
  fun setEnabled(enabled: Boolean): Boolean {
    val transition = synchronized(STATE_LOCK) {
      val wasEnabled = preferences.getBoolean(KEY_ENABLED, false)
      val previousGeneration = generationLocked()
      val nextGeneration = if (enabled == wasEnabled) {
        previousGeneration
      } else {
        nextGeneration(previousGeneration)
      }
      if (!enabled && wasEnabled) {
        OperatorBridgeSessionStore(appContext).revokeGeneration(previousGeneration)
      }
      preferences.edit()
        .putBoolean(KEY_ENABLED, enabled)
        .putLong(KEY_GENERATION, nextGeneration)
        .commit()
      Pair(enabled && !wasEnabled, nextGeneration)
    }
    if (enabled) {
      ContextCompat.startForegroundService(
        appContext,
        bridgeIntent(OperatorBridgeService.ACTION_START, transition.second),
      )
    } else {
      appContext.startService(
        bridgeIntent(OperatorBridgeService.ACTION_STOP, transition.second),
      )
    }
    return transition.first
  }

  fun ensureStartedIfEnabled() {
    val generation = synchronized(STATE_LOCK) {
      generationLocked().takeIf { preferences.getBoolean(KEY_ENABLED, false) }
    }
    if (generation != null) {
      ContextCompat.startForegroundService(
        appContext,
        bridgeIntent(OperatorBridgeService.ACTION_START, generation),
      )
    }
  }

  fun rotatePairingCode(): String {
    val code = generatePairingCode()
    synchronized(STATE_LOCK) {
      preferences.edit().putString(KEY_PAIRING_CODE, code).commit()
    }
    setEnabled(false)
    return code
  }

  fun pairingCode(): String = synchronized(STATE_LOCK) {
    preferences.getString(KEY_PAIRING_CODE, null)
      ?: generatePairingCode().also { generated ->
        preferences.edit().putString(KEY_PAIRING_CODE, generated).commit()
      }
  }

  fun recordRunning(expectedGeneration: Long, running: Boolean, error: String? = null): Boolean =
    synchronized(STATE_LOCK) {
      if (expectedGeneration != generationLocked() ||
        (running && !preferences.getBoolean(KEY_ENABLED, false))
      ) return@synchronized false
    preferences.edit()
      .putBoolean(KEY_RUNNING, running)
      .putLong(KEY_RUNNING_GENERATION, expectedGeneration)
      .apply {
        if (error == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, error.take(240))
      }
      .commit()
  }

  fun generation(): Long = synchronized(STATE_LOCK) { generationLocked() }

  private fun generationLocked(): Long =
    preferences.getLong(KEY_GENERATION, 1L).coerceAtLeast(1L)

  private fun bridgeIntent(action: String, expectedGeneration: Long): Intent =
    Intent(appContext, OperatorBridgeService::class.java)
      .setAction(action)
      .putExtra(OperatorBridgeService.EXTRA_EXPECTED_GENERATION, expectedGeneration)

  private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L

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
    private const val KEY_RUNNING_GENERATION = "running_generation"
    private const val KEY_PAIRING_CODE = "pairing_code"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_GENERATION = "bridge_generation"
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val STATE_LOCK = Any()
  }
}
