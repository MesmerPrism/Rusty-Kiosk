package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDebugContractTest {
  @Test
  fun acceptsOnlyBoundedRequestIds() {
    assertTrue(GuardDebugContract.validRequestId("request_1234"))
    assertFalse(GuardDebugContract.validRequestId("short"))
    assertFalse(GuardDebugContract.validRequestId("request with spaces"))
    assertFalse(GuardDebugContract.validRequestId("x".repeat(65)))
  }
}
