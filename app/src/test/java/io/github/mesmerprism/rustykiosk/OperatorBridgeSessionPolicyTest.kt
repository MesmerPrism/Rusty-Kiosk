package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorBridgeSessionPolicyTest {
  @Test
  fun issuanceRequiresHighEntropyUniqueOperationAndPositiveGeneration() {
    OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 4L, false, emptyList(), 0, 32)
    listOf(
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 0L, false, emptyList(), 0, 32) },
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 4L, true, emptyList(), 0, 32) },
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 4L, false, emptyList(), 0, 16) },
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(-1L, 4L, false, emptyList(), 0, 32) },
    ).forEach { rejected -> assertThrows(IllegalArgumentException::class.java, rejected) }
  }

  @Test
  fun issuanceIsRateAndConcurrencyBounded() {
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeSessionPolicy.requireIssuanceAllowed(
        60_000L,
        1L,
        false,
        List(OperatorBridgeSessionStore.MAX_ISSUES_PER_WINDOW) { 59_000L + it },
        0,
        32,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeSessionPolicy.requireIssuanceAllowed(
        60_000L,
        1L,
        false,
        emptyList(),
        OperatorBridgeSessionStore.MAX_CONCURRENT_SESSIONS,
        32,
      )
    }
  }

  @Test
  fun persistedLastObservedWallTimeRejectsRollbackBeforeRateWindowCanReset() {
    OperatorBridgeSessionPolicy.requireIssuanceAllowed(
      10_000L, 1L, false, emptyList(), 0, 32, lastObservedWallMs = 10_000L,
    )
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeSessionPolicy.requireIssuanceAllowed(
        9_999L, 1L, false, emptyList(), 0, 32, lastObservedWallMs = 10_000L,
      )
    }
  }

  @Test
  fun sessionUseFailsOnExpiryClockRollbackAndGenerationSubstitution() {
    assertTrue(OperatorBridgeSessionPolicy.isUsable(1_500L, 1_000L, 2_000L, 7L, 7L))
    assertFalse(OperatorBridgeSessionPolicy.isUsable(2_000L, 1_000L, 2_000L, 7L, 7L))
    assertFalse(OperatorBridgeSessionPolicy.isUsable(999L, 1_000L, 2_000L, 7L, 7L))
    assertFalse(OperatorBridgeSessionPolicy.isUsable(1_500L, 1_000L, 2_000L, 7L, 8L))
  }

  @Test
  fun ephemeralRawByteKeySignsWithoutPersistentPairingCodeConversion() {
    val key = ByteArray(32) { it.toByte() }
    val body = "{}".toByteArray()
    val requestId = "session_request_1"
    val timestamp = 1_000L
    val digest = OperatorBridgeAuth.sha256(body)
    val signature = OperatorBridgeAuth.sign(key, "POST", "/v1/status", requestId, timestamp, digest)
    val headers = OperatorBridgeAuthHeaders(requestId, timestamp, digest, signature, "session_identifier_1")
    assertTrue(
      OperatorBridgeAuth.verify(key, "POST", "/v1/status", body, headers, timestamp).isSuccess
    )
    assertTrue(
      OperatorBridgeAuth.verify(ByteArray(32) { 9 }, "POST", "/v1/status", body, headers, timestamp).isFailure
    )
  }
}
