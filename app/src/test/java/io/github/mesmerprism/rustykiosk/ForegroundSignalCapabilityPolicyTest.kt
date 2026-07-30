package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundSignalCapabilityPolicyTest {
  @Test
  fun acceptsOnlyProtocolTwoFromNumericOrStringMetadata() {
    assertEquals(2, ForegroundSignalCapabilityPolicy.parseSupportedVersion(2))
    assertEquals(2, ForegroundSignalCapabilityPolicy.parseSupportedVersion("2"))
    assertNull(ForegroundSignalCapabilityPolicy.parseSupportedVersion(null))
    assertNull(ForegroundSignalCapabilityPolicy.parseSupportedVersion(0))
    assertNull(ForegroundSignalCapabilityPolicy.parseSupportedVersion(1))
    assertNull(ForegroundSignalCapabilityPolicy.parseSupportedVersion("1"))
    assertNull(ForegroundSignalCapabilityPolicy.parseSupportedVersion(3))
    assertNull(ForegroundSignalCapabilityPolicy.parseSupportedVersion("not-a-version"))
  }
}
