package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.security.MessageDigest

internal class InstalledAppRepository(context: Context) {
  private val appContext = context.applicationContext
  private val packageManager = appContext.packageManager

  fun snapshot(): InstalledSnapshot {
    val packages = queryInstalledPackages()
    val evidenceByPackage = packages.associate { it.packageName to it.signingEvidence }
    val launchable =
      frontDoorSpecs
        .flatMap { spec ->
          query(spec).mapNotNull { it.toInstalledApp(spec, evidenceByPackage[it.activityInfo?.packageName]) }
        }
        .sortedWith(
          compareBy<Pair<Int, InstalledApp>> { it.first }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.label }
        )
        .distinctBy { it.second.packageName }
        .map { it.second }
    return InstalledSnapshot(launchableApps = launchable, packages = packages)
  }

  private fun queryInstalledPackages(): List<InstalledPackage> {
    val applications =
      packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    return applications.mapNotNull { info ->
      val packageName = info.packageName?.trim().orEmpty()
      if (packageName.isEmpty() || packageName == appContext.packageName) return@mapNotNull null
      val label =
        runCatching { packageManager.getApplicationLabel(info).toString().trim() }
          .getOrNull()
          ?.takeIf(String::isNotEmpty)
          ?: packageName.substringAfterLast('.')
      InstalledPackage(
        label = label,
        packageName = packageName,
        signingEvidence = signingEvidence(packageName),
      )
    }
  }

  private fun query(spec: FrontDoorSpec): List<ResolveInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(spec.category)
    return packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
  }

  private fun ResolveInfo.toInstalledApp(
    spec: FrontDoorSpec,
    signingEvidence: AppSigningEvidence?,
  ): Pair<Int, InstalledApp>? {
    val activity = activityInfo ?: return null
    val packageName = activity.packageName?.trim().orEmpty()
    val activityName = activity.name?.trim().orEmpty()
    if (
      packageName.isEmpty() ||
        activityName.isEmpty() ||
        packageName == appContext.packageName ||
        !activity.exported
    ) {
      return null
    }
    val label =
      runCatching { loadLabel(packageManager)?.toString()?.trim() }
        .getOrNull()
        ?.takeIf(String::isNotEmpty)
        ?: packageName.substringAfterLast('.')
    return spec.priority to
      InstalledApp(
        label = label,
        packageName = packageName,
        target =
          LaunchTarget(
            packageName = packageName,
            activityName = activityName,
            action = Intent.ACTION_MAIN,
            categories = setOf(spec.category),
          ),
        source = spec.source,
        signingEvidence = signingEvidence,
      )
  }

  private fun signingEvidence(packageName: String): AppSigningEvidence? =
    runCatching {
        val packageInfo =
          packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
          )
        val signingInfo = packageInfo.signingInfo ?: return@runCatching null
        val current = signingInfo.apkContentsSigners.orEmpty().map(::certificateSha256).sorted()
        if (current.isEmpty()) return@runCatching null
        val history =
          if (signingInfo.hasPastSigningCertificates()) {
            signingInfo.signingCertificateHistory.orEmpty().map(::certificateSha256)
          } else {
            emptyList()
          }
        AppSigningEvidence(
          currentSignerSha256 = current,
          signingLineageSha256 = history,
          lineageVerifiedByAndroid = history.isNotEmpty(),
        )
      }
      .getOrNull()

  private fun certificateSha256(signature: android.content.pm.Signature): String =
    MessageDigest.getInstance("SHA-256")
      .digest(signature.toByteArray())
      .joinToString("") { byte -> "%02x".format(byte) }

  private data class FrontDoorSpec(
    val category: String,
    val priority: Int,
    val source: String,
  )

  private companion object {
    val frontDoorSpecs =
      listOf(
        FrontDoorSpec(Intent.CATEGORY_LAUNCHER, 0, "android-launcher"),
        FrontDoorSpec("com.oculus.intent.category.VR", 1, "quest-vr"),
        FrontDoorSpec("com.oculus.intent.category.2D", 2, "quest-2d"),
        FrontDoorSpec(Intent.CATEGORY_LEANBACK_LAUNCHER, 3, "android-leanback"),
        FrontDoorSpec(Intent.CATEGORY_INFO, 4, "android-info"),
      )
  }
}
