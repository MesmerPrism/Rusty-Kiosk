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
  val transitionConverged: Boolean,
  val endpoint: String?,
  val pairingCode: String,
  val installerAllowed: Boolean,
  val lastError: String?,
  val bridgeGeneration: Long,
  val catalogSummaryGranted: Boolean = false,
  val persistenceBlocked: Boolean = false,
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

  fun isTransitionConverged(
    enabled: Boolean,
    currentGeneration: Long,
    recordedRunning: Boolean,
    runningGeneration: Long,
  ): Boolean = currentGeneration > 0L && runningGeneration == currentGeneration &&
    recordedRunning == enabled

  fun canApplyOwnedDisable(
    enabled: Boolean,
    currentGeneration: Long,
    expectedGeneration: Long,
    ownershipMatches: Boolean,
  ): Boolean = enabled && currentGeneration > 0L &&
    currentGeneration == expectedGeneration && ownershipMatches
}

internal object OperatorBridgePairingCodePresentation {
  fun render(pairingCode: String, visible: Boolean): String =
    if (visible) pairingCode else pairingCode.map { if (it == '-') '-' else '•' }.joinToString("")
}

/** Serializes wearer and provider bridge-generation transitions across their separate adapters. */
internal object OperatorBridgeTransitionLock {
  val monitor = Any()
}

internal interface OperatorBridgeSettingsEffects {
  fun retireUsbGeneration(generation: Long)
  fun start(generation: Long)
  fun stop(generation: Long)
}

internal class OperatorBridgePersistenceFence {
  @Volatile var failure: String? = null
    private set
  fun block(message: String) { failure = message }
  fun clear() { failure = null }
}

internal object ProcessOperatorBridgePersistenceFence {
  val value = OperatorBridgePersistenceFence()
}

internal object OperatorBridgeRuntimeAdmissionPolicy {
  fun allows(blocked: Boolean, enabled: Boolean, currentGeneration: Long, serverGeneration: Long): Boolean =
    !blocked && enabled && currentGeneration > 0L && currentGeneration == serverGeneration
}

internal class OperatorBridgeSettings private constructor(
  private val appContext: Context?,
  private val preferences: android.content.SharedPreferences,
  private val effects: OperatorBridgeSettingsEffects,
  private val fence: OperatorBridgePersistenceFence,
) {
  constructor(context: Context) : this(context.applicationContext,
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    AndroidOperatorBridgeSettingsEffects(context.applicationContext), ProcessOperatorBridgePersistenceFence.value)

  /** Fault seam into the actual settings methods; it creates no alternate persisted state owner. */
  internal constructor(preferences: android.content.SharedPreferences, effects: OperatorBridgeSettingsEffects,
    fence: OperatorBridgePersistenceFence) : this(null, preferences, effects, fence)

  fun snapshot(): OperatorBridgeSnapshot = synchronized(OperatorBridgeTransitionLock.monitor) {
    DirectOperatorV2GrantMutex.locked {
      synchronized(STATE_LOCK) {
        val enabled = preferences.getBoolean(KEY_ENABLED, false)
        val generation = generationLocked()
        OperatorBridgeSnapshot(
          enabled = enabled,
          running = preferences.getBoolean(KEY_RUNNING, false) && fence.failure == null,
          transitionConverged = OperatorBridgeActionPolicy.isTransitionConverged(
            enabled,
            generation,
            preferences.getBoolean(KEY_RUNNING, false),
            preferences.getLong(KEY_RUNNING_GENERATION, 0L),
          ) && fence.failure == null,
          endpoint = preferredIpv4()?.let { "http://$it:$PORT" },
          pairingCode = pairingCode(),
          installerAllowed = appContext?.packageManager?.canRequestPackageInstalls() ?: false,
          lastError = fence.failure ?: preferences.getString(KEY_LAST_ERROR, null),
          bridgeGeneration = generation,
          catalogSummaryGranted = enabled && v2StateLocked().summaryGranted,
          persistenceBlocked = fence.failure != null,
        )
      }
    }
  }

  /** Returns true only when this request changed a wearer-disabled bridge to enabled. */
  fun setEnabled(enabled: Boolean): Boolean = synchronized(OperatorBridgeTransitionLock.monitor) {
    DirectOperatorV2GrantMutex.locked { setEnabledLocked(enabled) }
  }

  /** Fails closed if another wearer/provider transition advanced the expected generation. */
  fun setEnabledIfGeneration(enabled: Boolean, expectedGeneration: Long): Boolean =
    synchronized(OperatorBridgeTransitionLock.monitor) {
      if (expectedGeneration <= 0L || generation() != expectedGeneration) {
        return@synchronized false
      }
      DirectOperatorV2GrantMutex.locked { setEnabledLocked(enabled) }
      true
    }

  private fun setEnabledLocked(enabled: Boolean): Boolean {
    val recovering = fence.failure != null
    try {
      val transition = synchronized(STATE_LOCK) {
        val wasEnabled = preferences.getBoolean(KEY_ENABLED, false)
        val previousGeneration = generationLocked()
        val nextGeneration = if (enabled != wasEnabled || recovering) {
          nextGeneration(previousGeneration)
        } else previousGeneration
        val editor = preferences.edit().putBoolean(KEY_ENABLED, enabled)
          .putLong(KEY_GENERATION, nextGeneration)
        if (!enabled || recovering) {
          val revoked = if (recovering) newV2State() else v2StateLocked().copy(
            summaryGranted = false, summaryGrantEpoch = randomToken(),
            detailGranted = false, detailGrantEpoch = randomToken())
          editor.putString(KEY_V2_STATE, revoked.toJson().toString())
          ProcessDirectOperatorV2Sessions.manager.clear()
        }
        if (recovering) {
          editor.putString(KEY_PAIRING_CODE, generatePairingCode())
            .putString(KEY_OWNER_EPOCH, randomToken())
        }
        if (enabled != wasEnabled || recovering) {
          ProcessDirectOperatorV2Sessions.manager.clear()
          effects.retireUsbGeneration(previousGeneration)
        }
        require(editor.commit()) { "Direct-link transition could not be persisted." }
        Pair(enabled && !wasEnabled, nextGeneration)
      }
      if (enabled) effects.start(transition.second) else effects.stop(transition.second)
      fence.clear()
      return transition.first
    } catch (failure: Throwable) {
      blockAfterFailure("Direct-link transition failed; use Enable direct link to recover safely.")
      throw failure
    }
  }

  fun ensureStartedIfEnabled() {
    val generation = synchronized(STATE_LOCK) {
      generationLocked().takeIf { preferences.getBoolean(KEY_ENABLED, false) }
    }
    if (generation != null) {
      effects.start(generation)
    }
  }

  fun requestStopIfDisabled(expectedGeneration: Long): Boolean =
    synchronized(OperatorBridgeTransitionLock.monitor) {
      val disabledAtExpectedGeneration = synchronized(STATE_LOCK) {
        expectedGeneration > 0L && generationLocked() == expectedGeneration &&
          !preferences.getBoolean(KEY_ENABLED, false)
      }
      if (!disabledAtExpectedGeneration) return@synchronized false
      effects.stop(expectedGeneration)
      true
    }

  fun rotatePairingCode(): String = synchronized(OperatorBridgeTransitionLock.monitor) {
    DirectOperatorV2GrantMutex.locked {
      fence.block("Pairing rotation is in progress.")
      try {
        val code = generatePairingCode()
        val nextGeneration = synchronized(STATE_LOCK) {
          val previousGeneration = generationLocked()
          val next = nextGeneration(previousGeneration)
          ProcessDirectOperatorV2Sessions.manager.clear()
          effects.retireUsbGeneration(previousGeneration)
          // One Editor commits the complete new identity and disabled/revoked state together.
          require(preferences.edit()
            .putString(KEY_PAIRING_CODE, code)
            .putString(KEY_OWNER_EPOCH, randomToken())
            .putString(KEY_V2_STATE, newV2State().toJson().toString())
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_GENERATION, next)
            .commit()) { "Pairing rotation could not be persisted." }
          next
        }
        effects.stop(nextGeneration)
        fence.clear()
        code
      } catch (failure: Throwable) {
        blockAfterFailure("Pairing rotation failed; direct access is blocked. Use Enable direct link to recover.")
        throw failure
      }
    }
  }

  /** Only the Activity's explicit wearer control calls this granting route. */
  fun setV2CatalogSummaryGranted(granted: Boolean) =
    synchronized(OperatorBridgeTransitionLock.monitor) {
      DirectOperatorV2GrantMutex.locked {
        try {
          synchronized(STATE_LOCK) {
            require(!granted || (preferences.getBoolean(KEY_ENABLED, false) && fence.failure == null)) {
              "Enable or recover the direct link before sharing catalogue summary."
            }
            val current = v2StateLocked()
            ProcessDirectOperatorV2Sessions.manager.clear()
            persistV2StateLocked(current.copy(
              summaryGranted = granted,
              summaryGrantEpoch = randomToken(),
              detailGranted = false,
              detailGrantEpoch = randomToken(),
            ))
          }
        } catch (failure: Throwable) {
          blockAfterFailure("Catalogue consent change failed; direct access is blocked. Use Enable direct link to recover.")
          throw failure
        }
      }
    }

  private fun blockAfterFailure(message: String) {
    fence.block(message)
    ProcessDirectOperatorV2Sessions.manager.clear()
  }

  fun directV2Profile(): DirectOperatorV2Profile =
    synchronized(OperatorBridgeTransitionLock.monitor) {
      DirectOperatorV2GrantMutex.locked {
        synchronized(STATE_LOCK) {
          val state = v2StateLocked()
          val ownerEpoch = preferences.getString(KEY_OWNER_EPOCH, null)
            ?.takeIf { Regex("^[A-Za-z0-9_-]{16,96}$").matches(it) }
            ?: randomToken().also {
              require(preferences.edit().putString(KEY_OWNER_EPOCH, it).commit())
            }
          DirectOperatorV2Profile(
            state.pairingId, state.keyEpoch, ownerEpoch, state.kdfSalt,
            DirectOperatorV2Grant(preferences.getBoolean(KEY_ENABLED, false) && state.summaryGranted,
              state.summaryGrantEpoch),
            DirectOperatorV2Grant(false, state.detailGrantEpoch),
          )
        }
      }
    }

  private fun v2StateLocked(): DirectOperatorV2SettingsState {
    preferences.getString(KEY_V2_STATE, null)?.let { raw ->
      DirectOperatorV2SettingsState.parseStrict(raw)?.takeIf { !it.detailGranted }?.let { return it }
    }
    return newV2State().also(::persistV2StateLocked)
  }

  private fun persistV2StateLocked(state: DirectOperatorV2SettingsState) {
    require(preferences.edit().putString(KEY_V2_STATE, state.toJson().toString()).commit()) {
      "Catalogue consent could not be persisted."
    }
  }

  private fun newV2State() = DirectOperatorV2SettingsState(
    randomToken(), randomToken(),
    DirectOperatorV2Base64.encode(ByteArray(DirectOperatorV2Crypto.KDF_SALT_BYTES)
      .also(SecureRandom()::nextBytes)),
    false, randomToken(), false, randomToken(),
  )

  private fun randomToken() = DirectOperatorV2Base64.encode(ByteArray(24).also(SecureRandom()::nextBytes))

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

  fun acceptsGeneration(serverGeneration: Long): Boolean = synchronized(STATE_LOCK) {
    OperatorBridgeRuntimeAdmissionPolicy.allows(fence.failure != null,
      preferences.getBoolean(KEY_ENABLED, false), generationLocked(), serverGeneration)
  }

  fun generation(): Long = synchronized(STATE_LOCK) { generationLocked() }

  fun generationAfterTransition(current: Long): Long = nextGeneration(current)

  private fun generationLocked(): Long =
    preferences.getLong(KEY_GENERATION, 1L).coerceAtLeast(1L)

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
    private const val KEY_V2_STATE = "direct_v2_state"
    private const val KEY_OWNER_EPOCH = "catalog_owner_epoch"
    private const val KEY_GENERATION = "bridge_generation"
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val STATE_LOCK = Any()
  }
}

private class AndroidOperatorBridgeSettingsEffects(private val context: Context) : OperatorBridgeSettingsEffects {
  override fun retireUsbGeneration(generation: Long) {
    OperatorBridgeSessionStore(context).revokeGeneration(generation)
  }
  override fun start(generation: Long) {
    ContextCompat.startForegroundService(context, intent(OperatorBridgeService.ACTION_START, generation))
  }
  override fun stop(generation: Long) {
    context.startService(intent(OperatorBridgeService.ACTION_STOP, generation))
  }
  private fun intent(action: String, generation: Long): Intent =
    Intent(context, OperatorBridgeService::class.java).setAction(action)
      .putExtra(OperatorBridgeService.EXTRA_EXPECTED_GENERATION, generation)
}
