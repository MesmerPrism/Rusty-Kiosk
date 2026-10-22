package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DirectOperatorV2ContractTest {
  @Test
  fun rfc5869Sha256VectorAndDirectionalSessionKeysAreStable() {
    val input = "0b".repeat(22).hex()
    val salt = "000102030405060708090a0b0c".hex()
    val info = "f0f1f2f3f4f5f6f7f8f9".hex()
    val prk = DirectOperatorV2Crypto.hkdfExtract(salt, input)

    assertEquals(
      "077709362c2e32df0ddc3f0dc47bba63" +
        "90b6c73bb50f9c3122ec844ad7c2b3e5",
      prk.hex(),
    )
    assertEquals(
      "3cb25f25faacd57a90434f64d0362f2a" +
        "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
        "34007208d5b887185865",
      DirectOperatorV2Crypto.hkdfExpand(prk, info, 42).hex(),
    )

    val fixture = Fixture()
    val response = fixture.open()
    val keys = fixture.keys(response)
    assertFalse(keys.requestKey.contentEquals(keys.responseKey))
  }

  @Test
  fun strictContractsRejectDamageDowngradeAndCapabilityEscalation() {
    val fixture = Fixture()
    val request = fixture.openRequest()
    val valid = fixture.openJson(request)
    assertEquals(request, DirectOperatorV2SessionOpenRequest.parseStrict(valid.toString()))

    valid.put("profile", "rusty-kiosk-direct-v1")
    assertTrue(runCatching { DirectOperatorV2SessionOpenRequest.parseStrict(valid.toString()) }.isFailure)

    val unknown = fixture.openJson(request).put("endpoint", "attacker")
    assertTrue(runCatching { DirectOperatorV2SessionOpenRequest.parseStrict(unknown.toString()) }.isFailure)

    val launch = fixture.openRequest(listOf(DirectOperatorV2Capability.APP_LAUNCH))
    assertTrue(runCatching { fixture.manager.open(launch, fixture.profile, PAIRING_CODE) }.isFailure)
  }

  @Test
  fun summaryAndDetailUseSeparateCurrentWearerGrants() {
    val summaryOnly = Fixture()
    val summaryContract = DirectOperatorV2RoutePolicy.publicContract(summaryOnly.profile)
    assertTrue(summaryContract.getBoolean("catalog_summary_granted"))
    assertFalse(summaryContract.getBoolean("catalog_detail_granted"))
    assertTrue(
      runCatching {
          summaryOnly.open(listOf(DirectOperatorV2Capability.CATALOG_DETAIL))
        }
        .isFailure
    )

    val detailed = Fixture(detailGranted = true)
    val response = detailed.open(listOf(DirectOperatorV2Capability.CATALOG_DETAIL))
    assertEquals(
      listOf(DirectOperatorV2Capability.CATALOG_DETAIL),
      response.capabilities,
    )

    val ungranted = Fixture(summaryGranted = false)
    assertTrue(runCatching { ungranted.open() }.isFailure)
  }

  @Test
  fun sessionOpenAndWearerRevocationAreLinearizedByTheGrantMutex() {
    val enteredOpen = CountDownLatch(1)
    val releaseOpen = CountDownLatch(1)
    val revocationCompleted = CountDownLatch(1)
    val revoked = AtomicBoolean(false)
    val opener =
      thread(start = true) {
        DirectOperatorV2GrantMutex.locked {
          enteredOpen.countDown()
          assertTrue(releaseOpen.await(2, TimeUnit.SECONDS))
          assertFalse(revoked.get())
        }
      }
    assertTrue(enteredOpen.await(2, TimeUnit.SECONDS))
    val revoker =
      thread(start = true) {
        DirectOperatorV2GrantMutex.locked {
          revoked.set(true)
          revocationCompleted.countDown()
        }
      }
    assertFalse(revocationCompleted.await(100, TimeUnit.MILLISECONDS))
    releaseOpen.countDown()
    opener.join(2_000)
    revoker.join(2_000)
    assertTrue(revocationCompleted.await(1, TimeUnit.SECONDS))
    assertTrue(revoked.get())

    val fixture = Fixture()
    val staleRequest = fixture.openRequest()
    val revokedProfile =
      fixture.profile.copy(
        summaryGrant =
          DirectOperatorV2Grant(false, "revoked_summary_epoch_0000000002")
      )
    assertTrue(
      runCatching {
          fixture.manager.open(staleRequest, revokedProfile, PAIRING_CODE)
        }
        .isFailure
    )
  }

  @Test
  fun damagedCiphertextDoesNotConsumeTheExpectedCounter() {
    val fixture = Fixture()
    val response = fixture.open()
    val exact = fixture.envelope(response, 1, "cipher_damage_01")
    val damagedBytes = DirectOperatorV2Base64.decode(exact.ciphertext)!!
    damagedBytes[damagedBytes.lastIndex] =
      (damagedBytes.last().toInt() xor 0x01).toByte()
    val damaged = exact.copy(ciphertext = DirectOperatorV2Base64.encode(damagedBytes))
    assertTrue(
      runCatching {
          fixture.manager.openRequest(
            "POST",
            DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
            damaged,
            fixture.profile,
          )
        }
        .isFailure
    )
    val opened =
      fixture.manager.openRequest(
        "POST",
        DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
        exact,
        fixture.profile,
      )
    assertEquals(1, opened.counter)
    opened.plaintext.fill(0)
  }

  @Test
  fun requestAadNonceAndRouteSubstitutionRejectBeforeCounterConsumption() {
    val fixture = Fixture()
    val response = fixture.open()
    val envelope = fixture.envelope(response, counter = 1, requestId = "request_aad_01")
    assertTrue(
      runCatching {
          fixture.manager.openRequest(
            "POST",
            DirectOperatorV2RoutePolicy.CATALOG_DETAIL_PATH,
            envelope,
            fixture.profile,
          )
        }
        .isFailure
    )

    val admitted =
      fixture.manager.openRequest(
        "POST",
        DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
        envelope,
        fixture.profile,
      )
    assertEquals(1, admitted.counter)

    val replay =
      runCatching {
        fixture.manager.openRequest(
          "POST",
          DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
          envelope,
          fixture.profile,
        )
      }
    assertTrue(replay.isFailure)
  }

  @Test
  fun responseUsesIndependentCountersAndEachRequestCanSealOnlyOnce() {
    val fixture = Fixture()
    val response = fixture.open()
    val first = fixture.admit(response, 1, "response_once_01")
    val firstSealed =
      fixture.manager.sealResponse(
        first,
        """{"accepted":true,"value":"first"}""".toByteArray(),
        fixture.profile,
      )
    assertEquals(1, firstSealed.requestCounter)
    assertEquals(1, firstSealed.responseCounter)
    assertTrue(
      runCatching {
          fixture.manager.sealResponse(first, "different".toByteArray(), fixture.profile)
        }
        .isFailure
    )

    val second = fixture.admit(response, 2, "response_once_02")
    val secondSealed =
      fixture.manager.sealResponse(second, "second".toByteArray(), fixture.profile)
    assertEquals(2, secondSealed.requestCounter)
    assertEquals(2, secondSealed.responseCounter)
    assertNotEquals(firstSealed.nonce, secondSealed.nonce)
    assertEquals(
      """{"accepted":true,"value":"first"}""",
      fixture.decryptResponse(response, firstSealed),
    )
    assertEquals("second", fixture.decryptResponse(response, secondSealed))
  }

  @Test
  fun exactAdmittedRequestAndCurrentGrantAreRecheckedBeforeResponse() {
    val fixture = Fixture()
    val response = fixture.open()
    val opened = fixture.admit(response, 1, "response_bind_01")
    assertTrue(
      runCatching {
          fixture.manager.sealResponse(
            opened.copy(requestDigest = "aa".repeat(32)),
            "forged".toByteArray(),
            fixture.profile,
          )
        }
        .isFailure
    )
    // The forged attempt did not consume the valid request's one response allocation.
    val sealed = fixture.manager.sealResponse(opened, "exact".toByteArray(), fixture.profile)
    assertEquals("exact", fixture.decryptResponse(response, sealed))

    val next = fixture.admit(response, 2, "response_bind_02")
    val revoked =
      fixture.profile.copy(
        summaryGrant =
          DirectOperatorV2Grant(
            false,
            "revoked_summary_epoch_0000000001",
          )
      )
    assertTrue(
      runCatching { fixture.manager.validateForDispatch(next, revoked) }.isFailure
    )
    assertTrue(
      runCatching { fixture.manager.sealResponse(next, "secret".toByteArray(), revoked) }.isFailure
    )
  }

  @Test
  fun responseUsesCopiedKeyMaterialWhenConcurrentClearDestroysLiveSession() {
    val fixture = Fixture(clearBeforeResponseEncrypt = true)
    val response = fixture.open()
    val opened = fixture.admit(response, 1, "response_clear_01")

    val sealed =
      fixture.manager.sealResponse(opened, "still encrypted".toByteArray(), fixture.profile)

    assertEquals("still encrypted", fixture.decryptResponse(response, sealed))
    assertTrue(
      runCatching {
          fixture.manager.sealResponse(opened, "second".toByteArray(), fixture.profile)
        }
        .isFailure
    )
  }

  @Test
  fun authenticatedInvalidPayloadStillConsumesItsRequestCounter() {
    val fixture = Fixture()
    val response = fixture.open()
    val invalid =
      fixture.envelope(
        response,
        counter = 1,
        requestId = "invalid_payload_01",
        plaintext = """{"schema":"damaged"}""",
      )
    val opened =
      fixture.manager.openRequest(
        "POST",
        DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
        invalid,
        fixture.profile,
      )
    assertTrue(opened.plaintext.isNotEmpty())

    val skipped =
      fixture.envelope(response, counter = 1, requestId = "invalid_payload_02")
    assertTrue(
      runCatching {
          fixture.manager.openRequest(
            "POST",
            DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
            skipped,
            fixture.profile,
          )
        }
        .isFailure
    )
    opened.plaintext.fill(0)
  }

  @Test
  fun liveSessionRequestWindowClosesInsteadOfEvictingReplayEvidence() {
    val fixture = Fixture()
    val response = fixture.open()
    (1L..256L).forEach { counter ->
      val opened =
        fixture.admit(
          response,
          counter,
          "bounded_request_${counter.toString().padStart(4, '0')}",
        )
      opened.plaintext.fill(0)
    }
    val overCap =
      fixture.envelope(response, 257, "bounded_request_0257")
    assertTrue(
      runCatching {
          fixture.manager.openRequest(
            "POST",
            DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
            overCap,
            fixture.profile,
          )
        }
        .isFailure
    )
    assertTrue(
      runCatching {
          fixture.manager.openRequest(
            "POST",
            DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
            overCap,
            fixture.profile,
          )
        }
        .isFailure
    )
  }

  @Test
  fun sessionOpenReplayWindowHardFailsAtCapacityWithoutFreshEviction() {
    val fixture = Fixture()
    val first = fixture.openRequest(requestId = "open_capacity_0001")
    fixture.manager.open(first, fixture.profile, PAIRING_CODE)
    (2..64).forEach { index ->
      val request = fixture.openRequest(requestId = "open_capacity_${index.toString().padStart(4, '0')}")
      fixture.manager.open(request, fixture.profile, PAIRING_CODE)
    }
    assertTrue(
      runCatching {
          fixture.manager.open(
            fixture.openRequest(requestId = "open_capacity_0065"),
            fixture.profile,
            PAIRING_CODE,
          )
        }
        .isFailure
    )
    assertTrue(runCatching { fixture.manager.open(first, fixture.profile, PAIRING_CODE) }.isFailure)
  }

  @Test
  fun persistedSettingsCodecRejectsPartialDamageAndDetailWithoutSummary() {
    val state =
      DirectOperatorV2SettingsState(
        pairingId = "pairing_identity_000000000001",
        keyEpoch = "pairing_key_epoch_0000000001",
        kdfSalt = DirectOperatorV2Base64.encode(ByteArray(32) { it.toByte() }),
        summaryGranted = true,
        summaryGrantEpoch = "summary_grant_epoch_000000001",
        detailGranted = true,
        detailGrantEpoch = "detail_grant_epoch_0000000001",
      )
    assertEquals(state, DirectOperatorV2SettingsState.parseStrict(state.toJson().toString()))
    val partial = state.toJson().also { it.remove("catalog_detail_grant_epoch") }
    assertEquals(null, DirectOperatorV2SettingsState.parseStrict(partial.toString()))
    val escalation =
      state.toJson()
        .put("catalog_summary_granted", false)
        .put("catalog_detail_granted", true)
    assertEquals(null, DirectOperatorV2SettingsState.parseStrict(escalation.toString()))
  }

  @Test
  fun originalRequestExpiryIsRecheckedBeforeDispatchAndSeal() {
    val fixture = Fixture()
    val session = fixture.open()
    val envelope = fixture.envelope(session, 1, "expiry_request_0001", lifetimeMs = 1_000)
    val opened = fixture.manager.openRequest("POST", DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
      envelope, fixture.profile)
    fixture.manager.validateForDispatch(opened, fixture.profile)
    fixture.now += 1_001
    assertTrue("Expired original request dispatched inside a live session",
      runCatching { fixture.manager.validateForDispatch(opened, fixture.profile) }.isFailure)
    assertTrue("Expired original request sealed inside a live session",
      runCatching { fixture.manager.sealResponse(opened, "secret".toByteArray(), fixture.profile) }.isFailure)
  }

  @Test
  fun responseCannotExtendOriginalRequestDeadline() {
    val fixture = Fixture()
    val session = fixture.open()
    val envelope = fixture.envelope(session, 1, "expiry_request_0002", lifetimeMs = 1_000)
    val opened = fixture.manager.openRequest("POST", DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
      envelope, fixture.profile)
    fixture.now += 250
    val response = fixture.manager.sealResponse(opened, "secret".toByteArray(), fixture.profile)
    assertEquals(envelope.expiresAtMs, response.expiresAtMs)
  }

  @Test
  fun clockRollbackCannotReviveDispatchOrResponse() {
    val fixture = Fixture()
    val session = fixture.open()
    val opened = fixture.admit(session, 1, "rollback_request_0001")
    fixture.now -= 5_001
    assertTrue(runCatching { fixture.manager.validateForDispatch(opened, fixture.profile) }.isFailure)
    assertTrue(runCatching { fixture.manager.sealResponse(opened, "secret".toByteArray(), fixture.profile) }.isFailure)
  }

  @Test
  fun anyClockRollbackInvalidatesAllKeysAndRefusesOpenUntilHighWaterRecovery() {
    val fixture = Fixture()
    val session = fixture.open()
    val opened = fixture.admit(session, 1, "rollback_request_0002")
    val highWater = fixture.now
    fixture.now -= 1
    assertTrue(runCatching { fixture.manager.validateForDispatch(opened, fixture.profile) }.isFailure)
    assertTrue(runCatching { fixture.manager.sealResponse(opened, "secret".toByteArray(), fixture.profile) }.isFailure)
    assertTrue(runCatching { fixture.manager.open(fixture.openRequest(requestId = "rollback_open_0002"), fixture.profile, PAIRING_CODE) }.isFailure)
    fixture.now = highWater
    assertTrue("Old keys revived after UTC recovery", runCatching { fixture.manager.validateForDispatch(opened, fixture.profile) }.isFailure)
    val fresh = fixture.manager.open(fixture.openRequest(requestId = "rollback_open_0003"), fixture.profile, PAIRING_CODE)
    assertTrue(fresh.sessionId.isNotEmpty())
    assertTrue("Old open replay evidence was discarded", runCatching { fixture.open() }.isFailure)
  }

  @Test
  fun deadlineCrossingBetweenResponseAllocationAndFinalClockReadRefusesSeal() {
    val fixture = Fixture()
    val session = fixture.open()
    val envelope = fixture.envelope(session, 1, "expiry_crossing_0001", lifetimeMs = 1_000)
    val opened = fixture.manager.openRequest("POST", DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
      envelope, fixture.profile)
    fixture.advanceAtRead = fixture.clockReads + 3
    assertTrue(runCatching { fixture.manager.sealResponse(opened, "secret".toByteArray(), fixture.profile) }.isFailure)
  }

  private class Fixture(
    clearBeforeResponseEncrypt: Boolean = false,
    summaryGranted: Boolean = true,
    detailGranted: Boolean = false,
  ) {
    var now = 1_800_000_000_000L
    var clockReads = 0
    var advanceAtRead: Int? = null
    val profile =
      DirectOperatorV2Profile(
        pairingId = "pairing_identity_000000000001",
        keyEpoch = "pairing_key_epoch_0000000001",
        ownerEpoch = "catalog_owner_epoch_0000000001",
        kdfSalt = DirectOperatorV2Base64.encode(ByteArray(32) { (it + 1).toByte() }),
        summaryGrant =
          DirectOperatorV2Grant(summaryGranted, "summary_grant_epoch_000000001"),
        detailGrant =
          DirectOperatorV2Grant(detailGranted, "detail_grant_epoch_0000000001"),
      )
    private lateinit var sessionManager: DirectOperatorV2SessionManager
    val manager: DirectOperatorV2SessionManager
      get() = sessionManager

    init {
      sessionManager =
        DirectOperatorV2SessionManager(
          CountingSecureRandom(),
          now = {
            clockReads++
            if (clockReads == advanceAtRead) now += 1_001
            now
          },
          beforeResponseEncrypt = {
            if (clearBeforeResponseEncrypt) sessionManager.clear()
          },
        )
    }

    fun open(
      capabilities: List<DirectOperatorV2Capability> =
        listOf(DirectOperatorV2Capability.CATALOG_SUMMARY),
    ): DirectOperatorV2SessionOpenResponse =
      manager.open(openRequest(capabilities), profile, PAIRING_CODE)

    fun openRequest(
      capabilities: List<DirectOperatorV2Capability> =
        listOf(DirectOperatorV2Capability.CATALOG_SUMMARY),
      requestId: String = "session_open_0001",
    ): DirectOperatorV2SessionOpenRequest {
      val unsigned =
        DirectOperatorV2SessionOpenRequest(
          pairingId = profile.pairingId,
          keyEpoch = profile.keyEpoch,
          ownerEpoch = profile.ownerEpoch,
          summaryGrantEpoch = profile.summaryGrant.epoch,
          detailGrantEpoch = profile.detailGrant.epoch,
          requestId = requestId,
          issuedAtMs = now,
          expiresAtMs = now + 90_000L,
          capabilities = capabilities.sortedBy { it.wireName },
          clientNonce = DirectOperatorV2Base64.encode(ByteArray(32) { (it + 9).toByte() }),
          proof = "",
        )
      val secret = DirectOperatorV2Crypto.pairingSecret(PAIRING_CODE, profile)
      val handshake = DirectOperatorV2Crypto.handshakeKey(secret, profile)
      return try {
        unsigned.copy(proof = DirectOperatorV2Crypto.proof(handshake, unsigned.proofParts()))
      } finally {
        secret.fill(0)
        handshake.fill(0)
      }
    }

    fun openJson(request: DirectOperatorV2SessionOpenRequest): JSONObject =
      JSONObject()
        .put("schema", DirectOperatorV2SessionOpenRequest.SCHEMA)
        .put("profile", DirectOperatorV2Crypto.PROFILE)
        .put("pairing_id", request.pairingId)
        .put("key_epoch", request.keyEpoch)
        .put("owner_epoch", request.ownerEpoch)
        .put("catalog_summary_grant_epoch", request.summaryGrantEpoch)
        .put("catalog_detail_grant_epoch", request.detailGrantEpoch)
        .put("request_id", request.requestId)
        .put("issued_at_ms", request.issuedAtMs)
        .put("expires_at_ms", request.expiresAtMs)
        .put("capabilities", request.capabilities.map { it.wireName })
        .put("client_nonce", request.clientNonce)
        .put("proof", request.proof)

    fun keys(response: DirectOperatorV2SessionOpenResponse): DirectOperatorV2SessionKeys {
      val secret = DirectOperatorV2Crypto.pairingSecret(PAIRING_CODE, profile)
      return try {
        DirectOperatorV2Crypto.deriveSessionKeys(
          secret,
          profile,
          response.sessionId,
          openRequest().clientNonce,
          response.serverNonce,
          response.capabilities,
        )
      } finally {
        secret.fill(0)
      }
    }

    fun envelope(
      response: DirectOperatorV2SessionOpenResponse,
      counter: Long,
      requestId: String,
      plaintext: String = """{"schema":"rusty.kiosk.catalog_request.v2"}""",
      lifetimeMs: Long = 30_000L,
    ): DirectOperatorV2RequestEnvelope {
      val keys = keys(response)
      val prefix =
        DirectOperatorV2Base64.decodeExact(
          response.requestNoncePrefix,
          DirectOperatorV2Crypto.NONCE_PREFIX_BYTES,
        )!!
      val nonce = DirectOperatorV2Crypto.nonce(prefix, counter)
      val unsigned =
        DirectOperatorV2RequestEnvelope(
          sessionId = response.sessionId,
          requestId = requestId,
          counter = counter,
          issuedAtMs = now,
          expiresAtMs = now + lifetimeMs,
          capability = DirectOperatorV2Capability.CATALOG_SUMMARY,
          nonce = DirectOperatorV2Base64.encode(nonce),
          ciphertext = "",
        )
      val ciphertext =
        DirectOperatorV2Crypto.encrypt(
          keys.requestKey,
          nonce,
          DirectOperatorV2Crypto.requestAad(
            "POST",
            DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
            unsigned,
          ),
          plaintext.toByteArray(StandardCharsets.UTF_8),
        )
      keys.requestKey.fill(0)
      keys.responseKey.fill(0)
      return unsigned.copy(ciphertext = DirectOperatorV2Base64.encode(ciphertext))
    }

    fun admit(
      response: DirectOperatorV2SessionOpenResponse,
      counter: Long,
      requestId: String,
    ): DirectOperatorV2OpenedRequest =
      manager.openRequest(
        "POST",
        DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH,
        envelope(response, counter, requestId),
        profile,
      )

    fun decryptResponse(
      opened: DirectOperatorV2SessionOpenResponse,
      envelope: DirectOperatorV2ResponseEnvelope,
    ): String {
      val keys = keys(opened)
      return try {
        DirectOperatorV2Crypto.decrypt(
          keys.responseKey,
          DirectOperatorV2Base64.decodeExact(envelope.nonce, DirectOperatorV2Crypto.NONCE_BYTES)!!,
          DirectOperatorV2Crypto.responseAad(envelope.copy(ciphertext = "")),
          DirectOperatorV2Base64.decode(envelope.ciphertext)!!,
        )
          .toString(StandardCharsets.UTF_8)
      } finally {
        keys.requestKey.fill(0)
        keys.responseKey.fill(0)
      }
    }
  }

  private class CountingSecureRandom : SecureRandom() {
    private var next = 1

    override fun nextBytes(bytes: ByteArray) {
      bytes.indices.forEach { index -> bytes[index] = (next++ and 0xff).toByte() }
    }
  }

  private fun String.hex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

  companion object {
    private const val PAIRING_CODE = "0123-4567-89AB-CDEF-GHJK-MNPQ"
  }
}
