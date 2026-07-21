package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupHelperProtocolTest {
  @Test
  fun parsesOnlyMatchingFixedResult() {
    val result =
      SetupHelperProtocol.parseValues(
        requestId = 42L,
        requestedOperation = SetupHelperOperation.STATUS,
        returnedRequestId = 42L,
        returnedOperation = SetupHelperOperation.STATUS.wireName,
        success = true,
        helperReady = true,
        requestAfterBoot = false,
        message = "ready",
      )
    assertTrue(result.success)
    assertTrue(result.helperReady)
    assertFalse(result.requestAfterBoot)
    assertEquals("ready", result.message)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMismatchedRequestId() {
    SetupHelperProtocol.parseValues(
      requestId = 42L,
      requestedOperation = SetupHelperOperation.STATUS,
      returnedRequestId = 41L,
      returnedOperation = SetupHelperOperation.STATUS.wireName,
      success = true,
      helperReady = true,
      requestAfterBoot = false,
      message = "ready",
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMismatchedOperation() {
    SetupHelperProtocol.parseValues(
      requestId = 42L,
      requestedOperation = SetupHelperOperation.STATUS,
      returnedRequestId = 42L,
      returnedOperation = SetupHelperOperation.ENABLE_ACCESSIBILITY.wireName,
      success = true,
      helperReady = true,
      requestAfterBoot = false,
      message = "ready",
    )
  }
}
