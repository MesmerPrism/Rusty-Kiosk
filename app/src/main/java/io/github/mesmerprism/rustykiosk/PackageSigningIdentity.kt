package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

internal object PackageSigningIdentityPolicy {
  fun canonical(signatures: List<ByteArray>, hasMultipleCurrentSigners: Boolean): String? {
    if (hasMultipleCurrentSigners || signatures.isEmpty()) return null
    val digests =
      signatures
        .map(::sha256)
        .distinct()
        .sorted()
    return digests.takeIf { it.isNotEmpty() }?.joinToString(",")
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
      }
}

internal data class PackageInstallationIdentity(
  val signingIdentity: String,
  val lastUpdateTime: Long,
  val versionCode: Long,
  val uid: Int,
)

internal class PackageSigningIdentityResolver(context: Context) {
  private val packageManager = context.applicationContext.packageManager

  @Suppress("DEPRECATION")
  fun resolve(packageName: String): PackageInstallationIdentity? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          val packageInfo =
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
          val signingInfo = packageInfo.signingInfo ?: return null
          val signatures =
            if (signingInfo.hasMultipleSigners()) {
              signingInfo.apkContentsSigners.orEmpty()
            } else {
              signingInfo.signingCertificateHistory.orEmpty()
            }
          val signingIdentity =
            PackageSigningIdentityPolicy.canonical(
              signatures = signatures.map(Signature::toByteArray),
              hasMultipleCurrentSigners = signingInfo.hasMultipleSigners(),
            ) ?: return null
          PackageInstallationIdentity(
            signingIdentity = signingIdentity,
            lastUpdateTime = packageInfo.lastUpdateTime.takeIf { it > 0L } ?: return null,
            versionCode = packageInfo.longVersionCode,
            uid = packageInfo.applicationInfo.uid,
          )
        } else {
          val packageInfo =
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
          val signatures = packageInfo.signatures.orEmpty()
          val signingIdentity =
            PackageSigningIdentityPolicy.canonical(
              signatures = signatures.map(Signature::toByteArray),
              hasMultipleCurrentSigners = signatures.size != 1,
            ) ?: return null
          PackageInstallationIdentity(
            signingIdentity = signingIdentity,
            lastUpdateTime = packageInfo.lastUpdateTime.takeIf { it > 0L } ?: return null,
            versionCode = packageInfo.versionCode.toLong(),
            uid = packageInfo.applicationInfo.uid,
          )
        }
      }
      .getOrNull()
}
