package io.github.mesmerprism.rustykiosk

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64

internal data class DirectOperatorV2OpenedRequest(
  val sessionId: String,
  val requestId: String,
  val counter: Long,
  val capability: DirectOperatorV2Capability,
  val requestDigest: String,
  val plaintext: ByteArray,
)

internal class DirectOperatorV2SessionManager(
  private val random: SecureRandom = SecureRandom(),
  private val now: () -> Long = System::currentTimeMillis,
  private val beforeResponseEncrypt: () -> Unit = {},
) {
  private val sessions = LinkedHashMap<String, Session>()
  private val openRequestIds = LinkedHashMap<String, Long>()
  private var utcHighWaterMs = 0L

  /** Any observed wall-clock rollback destroys live keys and fails closed until UTC catches up. */
  @Synchronized
  private fun checkedNow(): Long {
    val current = now()
    if (current < utcHighWaterMs || current <= 0 || current > Long.MAX_VALUE - MAX_SESSION_LIFETIME_MS) {
      sessions.keys.toList().forEach(::removeSession)
      error("V2 clock is unsafe; live sessions were invalidated.")
    }
    utcHighWaterMs = current
    return current
  }

  @Synchronized
  fun open(
    request: DirectOperatorV2SessionOpenRequest,
    currentProfile: DirectOperatorV2Profile,
    pairingCode: String,
  ): DirectOperatorV2SessionOpenResponse {
    val currentTime = checkedNow()
    require(request.pairingId == currentProfile.pairingId) { "The pairing identity changed." }
    require(request.keyEpoch == currentProfile.keyEpoch) { "The pairing key epoch changed." }
    require(request.ownerEpoch == currentProfile.ownerEpoch) { "The Kiosk owner epoch changed." }
    require(request.summaryGrantEpoch == currentProfile.summaryGrant.epoch) {
      "The catalog-summary grant epoch changed."
    }
    require(request.detailGrantEpoch == currentProfile.detailGrant.epoch) {
      "The catalog-detail grant epoch changed."
    }
    requireFresh(
      request.issuedAtMs,
      request.expiresAtMs,
      currentTime,
      MAX_SESSION_LIFETIME_MS,
    )
    require(request.capabilities.all(currentProfile::allows)) {
      "A requested v2 capability has no current wearer grant."
    }
    require(DirectOperatorV2Capability.APP_LAUNCH !in request.capabilities) {
      "App launch remains inactive until retained external lease authority is available."
    }
    val secret = DirectOperatorV2Crypto.pairingSecret(pairingCode, currentProfile)
    val handshakeKey = DirectOperatorV2Crypto.handshakeKey(secret, currentProfile)
    try {
      require(DirectOperatorV2Crypto.verifyProof(handshakeKey, request.proofParts(), request.proof)) {
        "Session-open proof was not accepted."
      }
      openRequestIds.entries.removeIf { it.value < currentTime }
      require(request.requestId !in openRequestIds) { "Session-open request was already used." }
      require(openRequestIds.size < MAX_OPEN_REQUEST_IDS) {
        "The bounded session-open replay window is full."
      }
      openRequestIds[request.requestId] = request.expiresAtMs

      val sessionId = randomToken(24)
      val responseId = randomToken(18)
      val serverNonce = randomBytes(DirectOperatorV2Crypto.SERVER_NONCE_BYTES)
      val requestPrefix = randomBytes(DirectOperatorV2Crypto.NONCE_PREFIX_BYTES)
      val responsePrefix = randomBytes(DirectOperatorV2Crypto.NONCE_PREFIX_BYTES)
      val expiresAt = minOf(request.expiresAtMs, currentTime + MAX_SESSION_LIFETIME_MS)
      val keys =
        DirectOperatorV2Crypto.deriveSessionKeys(
          pairingSecret = secret,
          profile = currentProfile,
          sessionId = sessionId,
          clientNonce = request.clientNonce,
          serverNonce = DirectOperatorV2Base64.encode(serverNonce),
          capabilities = request.capabilities,
        )
      val session =
        Session(
          id = sessionId,
          profile = currentProfile,
          capabilities = request.capabilities.toSet(),
          requestKey = keys.requestKey,
          responseKey = keys.responseKey,
          requestNoncePrefix = requestPrefix,
          responseNoncePrefix = responsePrefix,
          expiresAtMs = expiresAt,
        )
      prune(currentTime)
      require(sessionId !in sessions) { "Session identity collision." }
      sessions[sessionId] = session
      while (sessions.size > MAX_SESSIONS) removeSession(sessions.keys.first())
      val unsigned =
        DirectOperatorV2SessionOpenResponse(
          pairingId = currentProfile.pairingId,
          keyEpoch = currentProfile.keyEpoch,
          ownerEpoch = currentProfile.ownerEpoch,
          summaryGrantEpoch = currentProfile.summaryGrant.epoch,
          detailGrantEpoch = currentProfile.detailGrant.epoch,
          requestId = request.requestId,
          responseId = responseId,
          sessionId = sessionId,
          issuedAtMs = currentTime,
          expiresAtMs = expiresAt,
          capabilities = request.capabilities,
          serverNonce = DirectOperatorV2Base64.encode(serverNonce),
          requestNoncePrefix = DirectOperatorV2Base64.encode(requestPrefix),
          responseNoncePrefix = DirectOperatorV2Base64.encode(responsePrefix),
          proof = "",
        )
      return unsigned.copy(proof = DirectOperatorV2Crypto.proof(handshakeKey, unsigned.proofParts()))
    } finally {
      secret.fill(0)
      handshakeKey.fill(0)
    }
  }

  fun openRequest(
    method: String,
    path: String,
    envelope: DirectOperatorV2RequestEnvelope,
    currentProfile: DirectOperatorV2Profile,
  ): DirectOperatorV2OpenedRequest {
    val requestMaterial =
      synchronized(this) {
        prune(checkedNow())
        val session = sessions[envelope.sessionId] ?: error("V2 session is unknown or expired.")
        RequestMaterial(
          session = session,
          profile = session.profile,
          capabilities = session.capabilities,
          requestKey = session.requestKey.copyOf(),
          requestNoncePrefix = session.requestNoncePrefix.copyOf(),
        )
      }
    try {
      val session = requestMaterial.session
      require(requestMaterial.profile == currentProfile) {
        synchronized(this) { removeSession(envelope.sessionId) }
        "Pairing, owner, key, or wearer-grant epoch changed."
      }
      val routeCapability =
        DirectOperatorV2RoutePolicy.capability(method, path)
          ?: error("Unknown direct-operator v2 route.")
      require(routeCapability == envelope.capability) {
        "V2 route/capability substitution rejected."
      }
      require(routeCapability in requestMaterial.capabilities) {
        "Capability was not admitted to this session."
      }
      val currentTime = checkedNow()
      requireFresh(
        envelope.issuedAtMs,
        envelope.expiresAtMs,
        currentTime,
        MAX_REQUEST_LIFETIME_MS,
      )
      val expectedNonce =
        DirectOperatorV2Crypto.nonce(requestMaterial.requestNoncePrefix, envelope.counter)
      val suppliedNonce =
        DirectOperatorV2Base64.decodeExact(envelope.nonce, DirectOperatorV2Crypto.NONCE_BYTES)
          ?: error("Invalid request nonce.")
      require(expectedNonce.contentEquals(suppliedNonce)) {
        "Request nonce/counter binding failed."
      }
      val ciphertext =
        DirectOperatorV2Base64.decode(envelope.ciphertext)
          ?: error("Invalid v2 ciphertext encoding.")
      val plaintext =
        DirectOperatorV2Crypto.decrypt(
          requestMaterial.requestKey,
          suppliedNonce,
          DirectOperatorV2Crypto.requestAad(method, path, envelope),
          ciphertext,
        )
      val requestDigest =
        MessageDigest.getInstance("SHA-256")
          .digest(DirectOperatorV2Crypto.requestAad(method, path, envelope) + ciphertext)
          .joinToString("") { "%02x".format(it) }
      try {
        synchronized(this) {
          val current = sessions[envelope.sessionId]
          require(current === session) { "V2 session changed during request admission." }
          require(envelope.counter == session.lastRequestCounter + 1) {
            "Request counter is replayed, skipped, or out of order."
          }
          require(envelope.requestId !in session.requestRecords) {
            "Request identity was already used."
          }
          if (session.requestRecords.size >= MAX_REQUESTS_PER_SESSION) {
            removeSession(envelope.sessionId)
            error("The bounded v2 session request window is full.")
          }
          session.lastRequestCounter = envelope.counter
          session.requestRecords[envelope.requestId] =
            AdmittedRequest(
              counter = envelope.counter,
              digest = requestDigest,
              capability = envelope.capability,
              issuedAtMs = envelope.issuedAtMs,
              expiresAtMs = envelope.expiresAtMs,
            )
        }
      } catch (throwable: Throwable) {
        plaintext.fill(0)
        throw throwable
      }
      return DirectOperatorV2OpenedRequest(
        sessionId = envelope.sessionId,
        requestId = envelope.requestId,
        counter = envelope.counter,
        capability = envelope.capability,
        requestDigest = requestDigest,
        plaintext = plaintext,
      )
    } finally {
      requestMaterial.destroy()
    }
  }

  fun sealResponse(
    opened: DirectOperatorV2OpenedRequest,
    payload: ByteArray,
    currentProfile: DirectOperatorV2Profile,
  ): DirectOperatorV2ResponseEnvelope {
    require(payload.size <= DirectOperatorV2Crypto.MAX_PLAINTEXT_BYTES)
    val responseAllocation =
      synchronized(this) {
        prune(checkedNow())
        val session = sessions[opened.sessionId] ?: error("V2 session expired before response.")
        require(session.profile == currentProfile) {
          removeSession(opened.sessionId)
          "Pairing, owner, key, or wearer-grant epoch changed before response."
        }
        val admitted =
          session.requestRecords[opened.requestId] ?: error("Response request was not admitted.")
        require(
          admitted.counter == opened.counter &&
            admitted.digest == opened.requestDigest &&
            admitted.capability == opened.capability
        ) {
          "Response metadata does not match the exact admitted request."
        }
        requireFresh(admitted.issuedAtMs, admitted.expiresAtMs, checkedNow(), MAX_REQUEST_LIFETIME_MS)
        require(session.respondedRequestIds.add(opened.requestId)) {
          "A response was already allocated for this request."
        }
        require(session.lastResponseCounter < Long.MAX_VALUE) {
          removeSession(opened.sessionId)
          "V2 response counter exhausted."
        }
        session.lastResponseCounter += 1
        ResponseAllocation(
          responseKey = session.responseKey.copyOf(),
          responseNoncePrefix = session.responseNoncePrefix.copyOf(),
          expiresAtMs = minOf(session.expiresAtMs, admitted.expiresAtMs),
          responseCounter = session.lastResponseCounter,
        )
      }
    return try {
      val currentTime = checkedNow()
      require(currentTime <= responseAllocation.expiresAtMs) { "The original request expired before response encryption." }
      val expiresAt =
        minOf(responseAllocation.expiresAtMs, currentTime + MAX_REQUEST_LIFETIME_MS)
      val nonce =
        DirectOperatorV2Crypto.nonce(
          responseAllocation.responseNoncePrefix,
          responseAllocation.responseCounter,
        )
      val responseId = randomToken(18)
      val unsigned =
        DirectOperatorV2ResponseEnvelope(
          sessionId = opened.sessionId,
          requestId = opened.requestId,
          responseId = responseId,
          requestCounter = opened.counter,
          requestDigest = opened.requestDigest,
          responseCounter = responseAllocation.responseCounter,
          issuedAtMs = currentTime,
          expiresAtMs = expiresAt,
          capability = opened.capability,
          nonce = DirectOperatorV2Base64.encode(nonce),
          ciphertext = "",
        )
      beforeResponseEncrypt()
      val ciphertext =
        DirectOperatorV2Crypto.encrypt(
          responseAllocation.responseKey,
          nonce,
          DirectOperatorV2Crypto.responseAad(unsigned),
          payload,
        )
      unsigned.copy(ciphertext = DirectOperatorV2Base64.encode(ciphertext))
    } finally {
      responseAllocation.destroy()
    }
  }

  @Synchronized
  fun validateForDispatch(
    opened: DirectOperatorV2OpenedRequest,
    currentProfile: DirectOperatorV2Profile,
  ) {
    prune(checkedNow())
    val session = sessions[opened.sessionId] ?: error("V2 session expired before dispatch.")
    require(session.profile == currentProfile) {
      removeSession(opened.sessionId)
      "Pairing, owner, key, or wearer-grant epoch changed before dispatch."
    }
    val admitted =
      session.requestRecords[opened.requestId] ?: error("Dispatch request was not admitted.")
    require(
      admitted.counter == opened.counter &&
        admitted.digest == opened.requestDigest &&
        admitted.capability == opened.capability
    ) {
      "Dispatch metadata does not match the exact admitted request."
    }
    requireFresh(admitted.issuedAtMs, admitted.expiresAtMs, checkedNow(), MAX_REQUEST_LIFETIME_MS)
    require(opened.requestId !in session.respondedRequestIds) {
      "Dispatch request already reached response allocation."
    }
  }

  @Synchronized
  fun clear() {
    sessions.keys.toList().forEach(::removeSession)
    openRequestIds.clear()
  }

  @Synchronized
  private fun prune(currentTime: Long) {
    sessions.values.filter { it.expiresAtMs < currentTime }.map { it.id }.forEach(::removeSession)
  }

  private fun removeSession(sessionId: String) {
    sessions.remove(sessionId)?.destroy()
  }

  private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

  private fun randomToken(size: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(size))

  private data class Session(
    val id: String,
    val profile: DirectOperatorV2Profile,
    val capabilities: Set<DirectOperatorV2Capability>,
    val requestKey: ByteArray,
    val responseKey: ByteArray,
    val requestNoncePrefix: ByteArray,
    val responseNoncePrefix: ByteArray,
    val expiresAtMs: Long,
    var lastRequestCounter: Long = 0,
    var lastResponseCounter: Long = 0,
    val requestRecords: MutableMap<String, AdmittedRequest> = LinkedHashMap(),
    val respondedRequestIds: MutableSet<String> = LinkedHashSet(),
  ) {
    fun destroy() {
      requestKey.fill(0)
      responseKey.fill(0)
      requestNoncePrefix.fill(0)
      responseNoncePrefix.fill(0)
      requestRecords.clear()
      respondedRequestIds.clear()
    }
  }

  private data class ResponseAllocation(
    val responseKey: ByteArray,
    val responseNoncePrefix: ByteArray,
    val expiresAtMs: Long,
    val responseCounter: Long,
  ) {
    fun destroy() {
      responseKey.fill(0)
      responseNoncePrefix.fill(0)
    }
  }

  private data class RequestMaterial(
    val session: Session,
    val profile: DirectOperatorV2Profile,
    val capabilities: Set<DirectOperatorV2Capability>,
    val requestKey: ByteArray,
    val requestNoncePrefix: ByteArray,
  ) {
    fun destroy() {
      requestKey.fill(0)
      requestNoncePrefix.fill(0)
    }
  }

  private data class AdmittedRequest(
    val counter: Long,
    val digest: String,
    val capability: DirectOperatorV2Capability,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
  )

  companion object {
    const val MAX_SESSION_LIFETIME_MS = 90_000L
    const val MAX_REQUEST_LIFETIME_MS = 30_000L
    private const val MAX_SESSIONS = 8
    private const val MAX_OPEN_REQUEST_IDS = 64
    private const val MAX_REQUESTS_PER_SESSION = 256

    private fun requireFresh(
      issuedAtMs: Long,
      expiresAtMs: Long,
      nowMs: Long,
      maximumLifetimeMs: Long,
    ) {
      require(issuedAtMs <= nowMs + 5_000L) { "V2 request time is in the future." }
      require(expiresAtMs >= nowMs) { "V2 request expired." }
      require(expiresAtMs >= issuedAtMs && expiresAtMs - issuedAtMs <= maximumLifetimeMs) {
        "V2 request lifetime exceeds its fixed bound."
      }
    }
  }
}
