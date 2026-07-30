package io.github.mesmerprism.rustykiosk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import io.github.mesmerprism.rustykiosk.foregroundsignal.ForegroundSignalContract

/** Authenticated, call-only ingress for an armed app's advisory foreground-loss signal. */
class ForegroundSignalProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val providerContext = context ?: return response(false, "provider_unavailable")
    val requestedProtocolVersion =
      extras?.getInt(ForegroundSignalContract.EXTRA_PROTOCOL_VERSION)
        ?: arg?.toIntOrNull()
    val methodAccepted =
      requestedProtocolVersion == ForegroundSignalContract.PROTOCOL_VERSION &&
        method == ForegroundSignalContract.METHOD_FOREGROUND_LOST
    if (!methodAccepted) return response(false, "unknown_method_or_protocol")
    val config = GuardStateStore(providerContext).loadArmed()
      ?: return response(false, "guard_not_armed")
    val callingUid = Binder.getCallingUid()
    val resolvedCallingPackage = runCatching { callingPackage }.getOrNull()
    val packagesForUid =
      providerContext.packageManager.getPackagesForUid(callingUid)?.toSet().orEmpty()
    val installedCapability =
      ForegroundSignalCapabilityDetector(providerContext).supported(config.target.packageName)
    if (
      !ForegroundSignalAdmissionPolicy.accepts(
        armedTargetPackage = config.target.packageName,
        armedProtocolVersion = config.foregroundSignalProtocolVersion,
        armedSigningIdentity = config.targetSigningIdentity,
        armedPackageLastUpdateTime = config.targetPackageLastUpdateTime,
        armedPackageVersionCode = config.targetPackageVersionCode,
        callingPackage = resolvedCallingPackage,
        packagesForCallingUid = packagesForUid,
        installedProtocolVersion = installedCapability?.protocolVersion,
        installedSigningIdentity = installedCapability?.signingIdentity,
        installedPackageLastUpdateTime = installedCapability?.packageLastUpdateTime,
        installedPackageVersionCode = installedCapability?.packageVersionCode,
        requestedProtocolVersion = requestedProtocolVersion,
      )
    ) {
      return response(false, "caller_not_admitted")
    }
    val admittedProtocolVersion =
      requestedProtocolVersion ?: return response(false, "protocol_missing")

    val receivedAtNanos = SystemClock.elapsedRealtimeNanos()
    val sentAtNanos =
      extras?.getLong(ForegroundSignalContract.EXTRA_SIGNAL_ELAPSED_REALTIME_NANOS, -1L) ?: -1L
    val transportLatencyMs =
      if (sentAtNanos in 1..receivedAtNanos) {
        (receivedAtNanos - sentAtNanos) / 1_000_000L
      } else {
        null
      }
    val queued =
      ForegroundSignalRouter.dispatch(
        ForegroundSignal(
          generation = config.generation,
          callerPackage = config.target.packageName,
          protocolVersion = admittedProtocolVersion,
          source =
            normalizeSource(
              extras?.getString(ForegroundSignalContract.EXTRA_SIGNAL_SOURCE)
                ?: "app-foreground-callback"
            ),
          receivedAtMs = SystemClock.elapsedRealtime(),
          transportLatencyMs = transportLatencyMs,
        )
      )
    return response(queued, if (queued) "accepted" else "accessibility_unavailable")
  }

  override fun getType(uri: Uri): String? = null

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = throw UnsupportedOperationException("Foreground signal supports call() only.")

  override fun insert(uri: Uri, values: ContentValues?): Uri? =
    throw UnsupportedOperationException("Foreground signal supports call() only.")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException("Foreground signal supports call() only.")

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = throw UnsupportedOperationException("Foreground signal supports call() only.")

  private fun response(accepted: Boolean, status: String): Bundle =
    Bundle().apply {
      putBoolean(ForegroundSignalContract.RESPONSE_ACCEPTED, accepted)
      putString(ForegroundSignalContract.RESPONSE_STATUS, status)
    }

  private fun normalizeSource(source: String): String =
    source
      .trim()
      .replace(Regex("[^A-Za-z0-9._-]"), "_")
      .ifBlank { "app-foreground-callback" }
      .take(80)
}
