package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardLaunchHandoffLeasePolicyTest {
  private val lease =
    GuardLaunchHandoffLeaseSnapshot(
      targetPackage = "com.example.target",
      issuedAtElapsedMs = 1_000L,
      expiresAtElapsedMs = 6_000L,
    )

  @Test
  fun acceptsOnlyTheMatchingArmedTargetInsideTheBoundedWindow() {
    assertTrue(
      GuardLaunchHandoffLeasePolicy.shouldIgnoreOwnForeground(
        lease,
        armedTargetPackage = "com.example.target",
        nowElapsedMs = 2_000L,
      )
    )
    assertFalse(
      GuardLaunchHandoffLeasePolicy.shouldIgnoreOwnForeground(
        lease,
        armedTargetPackage = "com.example.other",
        nowElapsedMs = 2_000L,
      )
    )
    assertFalse(
      GuardLaunchHandoffLeasePolicy.shouldIgnoreOwnForeground(
        lease,
        armedTargetPackage = "com.example.target",
        nowElapsedMs = 6_001L,
      )
    )
  }

  @Test
  fun rejectsMissingAndPreBootElapsedTimes() {
    assertFalse(
      GuardLaunchHandoffLeasePolicy.shouldIgnoreOwnForeground(
        lease = null,
        armedTargetPackage = "com.example.target",
        nowElapsedMs = 2_000L,
      )
    )
    assertFalse(
      GuardLaunchHandoffLeasePolicy.shouldIgnoreOwnForeground(
        lease,
        armedTargetPackage = "com.example.target",
        nowElapsedMs = 999L,
      )
    )
  }
}
