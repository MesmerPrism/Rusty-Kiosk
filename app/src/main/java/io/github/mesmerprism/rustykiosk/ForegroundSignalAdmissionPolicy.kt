package io.github.mesmerprism.rustykiosk

import io.github.mesmerprism.rustykiosk.foregroundsignal.ForegroundSignalContract

internal object ForegroundSignalAdmissionPolicy {
  fun accepts(
    armedTargetPackage: String?,
    armedProtocolVersion: Int?,
    armedSigningIdentity: String?,
    armedPackageLastUpdateTime: Long?,
    armedPackageVersionCode: Long?,
    callingPackage: String?,
    packagesForCallingUid: Set<String>,
    installedProtocolVersion: Int?,
    installedSigningIdentity: String?,
    installedPackageLastUpdateTime: Long?,
    installedPackageVersionCode: Long?,
    requestedProtocolVersion: Int?,
  ): Boolean =
    !armedTargetPackage.isNullOrBlank() &&
      callingPackage == armedTargetPackage &&
      packagesForCallingUid == setOf(armedTargetPackage) &&
      armedProtocolVersion == installedProtocolVersion &&
      !armedSigningIdentity.isNullOrBlank() &&
      armedSigningIdentity == installedSigningIdentity &&
      armedPackageLastUpdateTime != null &&
      armedPackageLastUpdateTime == installedPackageLastUpdateTime &&
      armedPackageVersionCode != null &&
      armedPackageVersionCode == installedPackageVersionCode &&
      installedProtocolVersion == requestedProtocolVersion &&
      requestedProtocolVersion == ForegroundSignalContract.PROTOCOL_VERSION
}
