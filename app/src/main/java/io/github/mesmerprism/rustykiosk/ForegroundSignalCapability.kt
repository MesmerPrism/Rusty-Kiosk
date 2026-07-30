package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.content.pm.PackageManager
import io.github.mesmerprism.rustykiosk.foregroundsignal.ForegroundSignalContract

internal data class ForegroundSignalCapability(
  val protocolVersion: Int,
  val signingIdentity: String,
  val packageLastUpdateTime: Long,
  val packageVersionCode: Long,
)

internal object ForegroundSignalCapabilityPolicy {
  fun parseSupportedVersion(value: Any?): Int? {
    val reported =
      when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
      }
    return reported?.takeIf { it == ForegroundSignalContract.PROTOCOL_VERSION }
  }
}

internal class ForegroundSignalCapabilityDetector(context: Context) {
  private val appContext = context.applicationContext
  private val packageManager = appContext.packageManager
  private val signingIdentityResolver = PackageSigningIdentityResolver(appContext)

  @Suppress("DEPRECATION")
  fun supported(packageName: String): ForegroundSignalCapability? =
    runCatching {
        val applicationInfo =
          packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val protocolVersion =
          ForegroundSignalCapabilityPolicy.parseSupportedVersion(
            applicationInfo.metaData?.get(ForegroundSignalContract.CAPABILITY_METADATA)
          ) ?: return null
        val packageIdentity = signingIdentityResolver.resolve(packageName) ?: return null
        ForegroundSignalCapability(
          protocolVersion = protocolVersion,
          signingIdentity = packageIdentity.signingIdentity,
          packageLastUpdateTime = packageIdentity.lastUpdateTime,
          packageVersionCode = packageIdentity.versionCode,
        )
      }
      .getOrNull()
}
