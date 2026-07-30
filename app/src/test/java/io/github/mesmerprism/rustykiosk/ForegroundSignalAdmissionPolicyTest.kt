package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSignalAdmissionPolicyTest {
  private val target = "com.example.target"

  @Test
  fun admitsOnlyCurrentTargetWithExclusiveUidProtocolAndSigningIdentity() {
    assertTrue(accepts())
    assertFalse(accepts(armedTargetPackage = null))
    assertFalse(accepts(armedProtocolVersion = null))
    assertFalse(accepts(armedSigningIdentity = null))
    assertFalse(accepts(callingPackage = "com.example.other"))
    assertFalse(accepts(packagesForCallingUid = setOf("com.example.other")))
    assertFalse(accepts(packagesForCallingUid = setOf(target, "com.example.shared")))
    assertFalse(accepts(installedProtocolVersion = null))
    assertFalse(accepts(installedSigningIdentity = null))
    assertFalse(accepts(installedSigningIdentity = "different-signer"))
    assertFalse(accepts(installedPackageLastUpdateTime = 2_000L))
    assertFalse(accepts(installedPackageVersionCode = 8L))
    assertFalse(accepts(requestedProtocolVersion = 1))
    assertFalse(accepts(requestedProtocolVersion = 3))
  }

  private fun accepts(
    armedTargetPackage: String? = target,
    armedProtocolVersion: Int? = 2,
    armedSigningIdentity: String? = "signer-lineage",
    armedPackageLastUpdateTime: Long? = 1_000L,
    armedPackageVersionCode: Long? = 7L,
    callingPackage: String? = target,
    packagesForCallingUid: Set<String> = setOf(target),
    installedProtocolVersion: Int? = 2,
    installedSigningIdentity: String? = "signer-lineage",
    installedPackageLastUpdateTime: Long? = 1_000L,
    installedPackageVersionCode: Long? = 7L,
    requestedProtocolVersion: Int? = 2,
  ): Boolean =
    ForegroundSignalAdmissionPolicy.accepts(
      armedTargetPackage = armedTargetPackage,
      armedProtocolVersion = armedProtocolVersion,
      armedSigningIdentity = armedSigningIdentity,
      armedPackageLastUpdateTime = armedPackageLastUpdateTime,
      armedPackageVersionCode = armedPackageVersionCode,
      callingPackage = callingPackage,
      packagesForCallingUid = packagesForCallingUid,
      installedProtocolVersion = installedProtocolVersion,
      installedSigningIdentity = installedSigningIdentity,
      installedPackageLastUpdateTime = installedPackageLastUpdateTime,
      installedPackageVersionCode = installedPackageVersionCode,
      requestedProtocolVersion = requestedProtocolVersion,
    )
}
