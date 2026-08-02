package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorBridgeSessionPolicyTest {
  @Test
  fun issuanceRequiresHighEntropyUniqueOperationAndPositiveGeneration() {
    OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 4L, false, emptyList(), 0, 0, 32)
    listOf(
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 0L, false, emptyList(), 0, 0, 32) },
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 4L, true, emptyList(), 0, 1, 32) },
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(1_000L, 4L, false, emptyList(), 0, 0, 16) },
      { OperatorBridgeSessionPolicy.requireIssuanceAllowed(-1L, 4L, false, emptyList(), 0, 0, 32) },
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
        0,
        32,
      )
    }
  }

  @Test
  fun persistedLastObservedWallTimeRejectsRollbackBeforeRateWindowCanReset() {
    OperatorBridgeSessionPolicy.requireIssuanceAllowed(
      10_000L, 1L, false, emptyList(), 0, 0, 32, lastObservedWallMs = 10_000L,
    )
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeSessionPolicy.requireIssuanceAllowed(
        9_999L, 1L, false, emptyList(), 0, 0, 32, lastObservedWallMs = 10_000L,
      )
    }
  }

  @Test
  fun operationIdsNeverEvictAndRemainUsedAcrossBridgeGenerations() {
    val fullLedger = (0 until OperatorBridgeSessionStore.MAX_OPERATION_IDS_PER_EPOCH).map { index ->
      "operation_${index.toString().padStart(8, '0')}"
    }
    assertEquals(fullLedger, OperatorBridgeOperationLedgerPolicy.normalize(fullLedger))
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeOperationLedgerPolicy.append(fullLedger, "operation_new_identifier")
    }
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeOperationLedgerPolicy.append(fullLedger.take(256), fullLedger.first())
    }
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeSessionPolicy.requireIssuanceAllowed(
        now = 2_000L,
        bridgeGeneration = 99L,
        operationAlreadyUsed = true,
        recentIssueTimes = emptyList(),
        activeSessionCount = 0,
        issuedOperationCount = 256,
        entropyBytes = OperatorBridgeSessionStore.SESSION_SECRET_BYTES,
      )
    }
  }

  @Test
  fun operationLedgerIsExplicitlyBootstrapEpochScopedAndFailsClosedOnMismatch() {
    assertEquals(
      "provider-epoch-a",
      OperatorBridgeOperationLedgerPolicy.requireEpoch(null, "provider-epoch-a"),
    )
    assertEquals(
      "provider-epoch-a",
      OperatorBridgeOperationLedgerPolicy.requireEpoch("provider-epoch-a", "provider-epoch-a"),
    )
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeOperationLedgerPolicy.requireEpoch("provider-epoch-old", "provider-epoch-new")
    }
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeOperationLedgerPolicy.normalize(
        listOf("operation_identifier", "operation_identifier")
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      OperatorBridgeOperationLedgerPolicy.normalize(listOf("bad id"))
    }
  }

  @Test
  fun storedReplayArraysInitializeOnlyForFreshStateAndRejectWrongTypes() {
    OperatorBridgeStateShapePolicy.requireSchema(
      fresh = true,
      schemaPresent = false,
      schemaMatches = false,
    )
    OperatorBridgeStateShapePolicy.requireArray(
      fresh = true,
      fieldPresent = false,
      fieldIsArray = false,
    )
    OperatorBridgeStateShapePolicy.requireSchema(
      fresh = false,
      schemaPresent = true,
      schemaMatches = true,
    )
    OperatorBridgeStateShapePolicy.requireArray(
      fresh = false,
      fieldPresent = true,
      fieldIsArray = true,
    )
    listOf(
      { OperatorBridgeStateShapePolicy.requireSchema(false, false, false) },
      { OperatorBridgeStateShapePolicy.requireSchema(false, true, false) },
      { OperatorBridgeStateShapePolicy.requireArray(false, false, false) },
      { OperatorBridgeStateShapePolicy.requireArray(true, true, true) },
      { OperatorBridgeStateShapePolicy.requireArray(false, true, false) },
    ).forEach { damaged ->
      assertThrows(IllegalArgumentException::class.java, damaged)
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

  @Test
  fun cleanupOwnershipOutlivesFiveMinuteSecretWithoutRetainingThatSecret() {
    val issuedAt = 1_000L
    val ownership = cleanupOwnership(
      issuedAt = issuedAt,
      expiresAt = issuedAt + OperatorBridgeSessionStore.CLEANUP_OWNERSHIP_LIFETIME_MS,
    )
    val afterSecretExpiry = issuedAt + OperatorBridgeSessionStore.SESSION_LIFETIME_MS + 1L
    assertTrue(
      OperatorBridgeCleanupOwnershipPolicy.isRetained(
        ownership,
        afterSecretExpiry,
        ownership.bridgeGeneration,
      )
    )
    assertTrue(
      OperatorBridgeCleanupOwnershipPolicy.exactOwnedEnable(
        listOf(ownership),
        ownership.operationId,
        ownership.sessionId,
        ownership.bridgeGeneration,
        afterSecretExpiry,
        ownership.bridgeGeneration,
      ) != null
    )
  }

  @Test
  fun cleanupRejectsPreexistingListenerWrongBindingAndGeneration() {
    val now = 2_000L
    val owned = cleanupOwnership(issuedAt = 1_000L, expiresAt = 5_000L)
    val preexisting = owned.copy(operationId = "operation_preexisting", enabledByRequest = false)
    assertNull(
      OperatorBridgeCleanupOwnershipPolicy.exactOwnedEnable(
        listOf(preexisting),
        preexisting.operationId,
        preexisting.sessionId,
        preexisting.bridgeGeneration,
        now,
        preexisting.bridgeGeneration,
      )
    )
    assertNull(
      OperatorBridgeCleanupOwnershipPolicy.exactOwnedEnable(
        listOf(owned),
        "operation_wrong",
        owned.sessionId,
        owned.bridgeGeneration,
        now,
        owned.bridgeGeneration,
      )
    )
    assertNull(
      OperatorBridgeCleanupOwnershipPolicy.exactOwnedEnable(
        listOf(owned),
        owned.operationId,
        "another_session_identifier",
        owned.bridgeGeneration,
        now,
        owned.bridgeGeneration,
      )
    )
    assertNull(
      OperatorBridgeCleanupOwnershipPolicy.exactOwnedEnable(
        listOf(owned),
        owned.operationId,
        owned.sessionId,
        owned.bridgeGeneration,
        now,
        owned.bridgeGeneration + 1L,
      )
    )
  }

  @Test
  fun recoveryUsesOnlyUniqueBootstrapOwnedOperationAndGeneration() {
    val owned = cleanupOwnership()
    val another = owned.copy(operationId = "operation_another", sessionId = "session_identifier_2")
    assertEquals(
      owned,
      OperatorBridgeCleanupOwnershipPolicy.recoverableOwnedEnable(
        listOf(owned, another),
        owned.operationId,
        now = 2_000L,
        currentGeneration = owned.bridgeGeneration,
      )
    )
    assertNull(
      OperatorBridgeCleanupOwnershipPolicy.recoverableOwnedEnable(
        listOf(owned, owned.copy(sessionId = "session_identifier_3")),
        owned.operationId,
        now = 2_000L,
        currentGeneration = owned.bridgeGeneration,
      )
    )
  }

  @Test
  fun dispatchedStopRetrySurvivesSecretExpiryAndConsumesOnlyAfterStoppedReadback() {
    val dispatched = cleanupOwnership(
      expiresAt = OperatorBridgeSessionStore.CLEANUP_OWNERSHIP_LIFETIME_MS,
    ).copy(
      bridgeGeneration = 8L,
      state = OperatorBridgeCleanupOwnershipState.DISABLE_DISPATCHED,
    )
    val afterSecretExpiry = OperatorBridgeSessionStore.SESSION_LIFETIME_MS + 1L
    assertEquals(
      dispatched,
      OperatorBridgeCleanupOwnershipPolicy.dispatchedDisable(
        listOf(dispatched),
        dispatched.operationId,
        afterSecretExpiry,
        dispatched.bridgeGeneration,
      )
    )
    val pending = OperatorBridgeCleanupOwnershipPolicy.consumeCompletedDisables(
      listOf(dispatched),
      dispatched.bridgeGeneration,
      stoppedReadbackConverged = false,
    )
    assertEquals(listOf(dispatched), pending.first)
    assertEquals(0, pending.second)

    val completed = OperatorBridgeCleanupOwnershipPolicy.consumeCompletedDisables(
      pending.first,
      dispatched.bridgeGeneration,
      stoppedReadbackConverged = true,
    )
    assertTrue(completed.first.isEmpty())
    assertEquals(1, completed.second)
    val replay = OperatorBridgeCleanupOwnershipPolicy.consumeCompletedDisables(
      completed.first,
      dispatched.bridgeGeneration,
      stoppedReadbackConverged = true,
    )
    assertEquals(0, replay.second)
  }

  @Test
  fun cleanupOwnershipExpiresDropsOnGenerationTransitionAndIsBounded() {
    val owned = cleanupOwnership(expiresAt = 3_000L)
    assertTrue(
      OperatorBridgeCleanupOwnershipPolicy.retainBounded(
        listOf(owned),
        now = 3_000L,
        currentGeneration = owned.bridgeGeneration,
      ).isEmpty()
    )
    assertTrue(
      OperatorBridgeCleanupOwnershipPolicy.retainBounded(
        listOf(owned),
        now = 2_000L,
        currentGeneration = owned.bridgeGeneration + 1L,
      ).isEmpty()
    )
    val many = (0 until OperatorBridgeSessionStore.MAX_CLEANUP_OWNERSHIP_TOMBSTONES + 7).map { index ->
      cleanupOwnership(
        operationId = "operation_${index.toString().padStart(8, '0')}",
        sessionId = "session_${index.toString().padStart(12, '0')}",
      )
    }
    val retained = OperatorBridgeCleanupOwnershipPolicy.retainBounded(
      many,
      now = 2_000L,
      currentGeneration = owned.bridgeGeneration,
    )
    assertEquals(OperatorBridgeSessionStore.MAX_CLEANUP_OWNERSHIP_TOMBSTONES, retained.size)
    assertEquals(many.takeLast(retained.size), retained)
  }

  private fun cleanupOwnership(
    operationId: String = "operation_identifier_1",
    sessionId: String = "session_identifier_1",
    enabledByRequest: Boolean = true,
    issuedAt: Long = 1_000L,
    expiresAt: Long = 10_000L,
  ) = OperatorBridgeCleanupOwnership(
    operationId,
    sessionId,
    bridgeGeneration = 7L,
    enabledByRequest,
    issuedAt,
    expiresAt,
  )
}
