package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

internal data class OperatorBridgeIssuedSession(
  val operationId: String,
  val sessionId: String,
  val sessionSecretBase64: String,
  val bridgeGeneration: Long,
  val issuedAtMs: Long,
  val expiresAtMs: Long,
)

internal data class OperatorBridgeSessionCredential(
  val sessionId: String,
  val secret: ByteArray,
  val bridgeGeneration: Long,
  val expiresAtMs: Long,
)

internal data class OperatorBridgeCleanupOwnership(
  val operationId: String,
  val sessionId: String,
  val bridgeGeneration: Long,
  val enabledByRequest: Boolean,
  val issuedAtMs: Long,
  val expiresAtMs: Long,
  val state: OperatorBridgeCleanupOwnershipState = OperatorBridgeCleanupOwnershipState.ENABLE_OWNED,
)

internal enum class OperatorBridgeCleanupOwnershipState(val wireName: String) {
  ENABLE_OWNED("enable_owned"),
  DISABLE_DISPATCHED("disable_dispatched"),
}

internal object OperatorBridgeCleanupOwnershipPolicy {
  fun isRetained(
    ownership: OperatorBridgeCleanupOwnership,
    now: Long,
    currentGeneration: Long,
  ): Boolean =
    now >= 0L && ownership.issuedAtMs in 0..now && ownership.expiresAtMs > now &&
      ownership.bridgeGeneration > 0L && ownership.bridgeGeneration == currentGeneration

  fun retainBounded(
    ownerships: List<OperatorBridgeCleanupOwnership>,
    now: Long,
    currentGeneration: Long,
  ): List<OperatorBridgeCleanupOwnership> =
    ownerships.filter { isRetained(it, now, currentGeneration) }
      .takeLast(OperatorBridgeSessionStore.MAX_CLEANUP_OWNERSHIP_TOMBSTONES)

  fun exactOwnedEnable(
    ownerships: List<OperatorBridgeCleanupOwnership>,
    operationId: String,
    sessionId: String,
    expectedGeneration: Long,
    now: Long,
    currentGeneration: Long,
  ): OperatorBridgeCleanupOwnership? =
    retainBounded(ownerships, now, currentGeneration).singleOrNull {
      it.state == OperatorBridgeCleanupOwnershipState.ENABLE_OWNED && it.enabledByRequest &&
        it.operationId == operationId && it.sessionId == sessionId &&
        it.bridgeGeneration == expectedGeneration
    }

  fun recoverableOwnedEnable(
    ownerships: List<OperatorBridgeCleanupOwnership>,
    operationId: String,
    now: Long,
    currentGeneration: Long,
  ): OperatorBridgeCleanupOwnership? =
    retainBounded(ownerships, now, currentGeneration).singleOrNull {
      it.state == OperatorBridgeCleanupOwnershipState.ENABLE_OWNED && it.enabledByRequest &&
        it.operationId == operationId &&
        it.bridgeGeneration == currentGeneration
    }

  fun dispatchedDisable(
    ownerships: List<OperatorBridgeCleanupOwnership>,
    operationId: String,
    now: Long,
    currentGeneration: Long,
  ): OperatorBridgeCleanupOwnership? =
    retainBounded(ownerships, now, currentGeneration).singleOrNull {
      it.state == OperatorBridgeCleanupOwnershipState.DISABLE_DISPATCHED &&
        it.enabledByRequest && it.operationId == operationId &&
        it.bridgeGeneration == currentGeneration
    }

  fun consumeCompletedDisables(
    ownerships: List<OperatorBridgeCleanupOwnership>,
    currentGeneration: Long,
    stoppedReadbackConverged: Boolean,
  ): Pair<List<OperatorBridgeCleanupOwnership>, Int> {
    if (!stoppedReadbackConverged) return ownerships to 0
    val retained = ownerships.filterNot {
      it.state == OperatorBridgeCleanupOwnershipState.DISABLE_DISPATCHED &&
        it.bridgeGeneration == currentGeneration
    }
    return retained to (ownerships.size - retained.size)
  }
}

internal object OperatorBridgeSessionPolicy {
  fun requireIssuanceAllowed(
    now: Long,
    bridgeGeneration: Long,
    operationAlreadyUsed: Boolean,
    recentIssueTimes: List<Long>,
    activeSessionCount: Int,
    issuedOperationCount: Int,
    entropyBytes: Int,
    lastObservedWallMs: Long = now,
  ) {
    require(now >= 0L && now <= Long.MAX_VALUE - OperatorBridgeSessionStore.SESSION_LIFETIME_MS) {
      "The bootstrap clock is invalid."
    }
    require(bridgeGeneration > 0L) { "A valid bridge generation is required." }
    require(lastObservedWallMs < 0L || now >= lastObservedWallMs) {
      "The bootstrap clock moved backwards."
    }
    require(!operationAlreadyUsed) { "That bootstrap operation id was already used." }
    require(issuedOperationCount in 0 until OperatorBridgeSessionStore.MAX_OPERATION_IDS_PER_EPOCH) {
      "The bounded bootstrap operation-id ledger is full; issuance fails closed for this bootstrap epoch."
    }
    require(recentIssueTimes.count { it in (now - RATE_WINDOW_MS)..now } <
      OperatorBridgeSessionStore.MAX_ISSUES_PER_WINDOW) {
      "Ephemeral session issuance is temporarily rate limited."
    }
    require(activeSessionCount < OperatorBridgeSessionStore.MAX_CONCURRENT_SESSIONS) {
      "The bounded ephemeral session limit is reached."
    }
    require(entropyBytes == OperatorBridgeSessionStore.SESSION_SECRET_BYTES) {
      "Session entropy source returned the wrong size."
    }
  }

  fun isUsable(
    now: Long,
    issuedAtMs: Long,
    expiresAtMs: Long,
    sessionGeneration: Long,
    currentGeneration: Long,
  ): Boolean =
    now >= 0L && issuedAtMs in 0..now && expiresAtMs > now &&
      sessionGeneration > 0L && sessionGeneration == currentGeneration

  const val RATE_WINDOW_MS = 60 * 1000L
}

internal object OperatorBridgeOperationLedgerPolicy {
  fun normalize(operationIds: List<String>): List<String> {
    require(operationIds.size <= OperatorBridgeSessionStore.MAX_OPERATION_IDS_PER_EPOCH) {
      "The bootstrap operation-id ledger exceeds its fixed bound."
    }
    require(operationIds.all { RustyKioskCliProtocol.validRequestId(it) != null }) {
      "The bootstrap operation-id ledger contains an invalid id."
    }
    require(operationIds.distinct().size == operationIds.size) {
      "The bootstrap operation-id ledger contains a duplicate id."
    }
    return operationIds
  }

  fun append(operationIds: List<String>, operationId: String): List<String> {
    val current = normalize(operationIds)
    require(operationId !in current) { "That bootstrap operation id was already used." }
    require(current.size < OperatorBridgeSessionStore.MAX_OPERATION_IDS_PER_EPOCH) {
      "The bounded bootstrap operation-id ledger is full; issuance fails closed for this bootstrap epoch."
    }
    return current + operationId
  }

  fun requireEpoch(storedEpoch: String?, currentEpoch: String): String {
    require(currentEpoch.isNotBlank()) { "The bootstrap issuance epoch is unavailable." }
    if (storedEpoch.isNullOrBlank()) return currentEpoch
    require(storedEpoch == currentEpoch) {
      "The bootstrap operation-id ledger belongs to a different issuance epoch."
    }
    return storedEpoch
  }
}

internal object OperatorBridgeStateShapePolicy {
  fun requireSchema(fresh: Boolean, schemaPresent: Boolean, schemaMatches: Boolean) {
    require((fresh && !schemaPresent) || (!fresh && schemaPresent && schemaMatches)) {
      "Stored bootstrap state has a missing or invalid schema."
    }
  }

  fun requireArray(fresh: Boolean, fieldPresent: Boolean, fieldIsArray: Boolean) {
    require((fresh && !fieldPresent) || (!fresh && fieldPresent && fieldIsArray)) {
      "Stored bootstrap state has a missing, null, or wrong-type array."
    }
  }
}

/**
 * App-private ephemeral bootstrap sessions. The secret is issued once through the DUMP provider,
 * is scoped only to direct-operator HMAC, and is never included in status or audit projection.
 */
internal class OperatorBridgeSessionStore(
  context: Context,
  private val wallNow: () -> Long = System::currentTimeMillis,
  private val randomBytes: (Int) -> ByteArray = { size ->
    ByteArray(size).also(SecureRandom()::nextBytes)
  },
) {
  private val preferences = context.applicationContext.getSharedPreferences(
    PREFERENCES,
    Context.MODE_PRIVATE,
  )

  fun issue(
    operationId: String,
    bridgeGeneration: Long,
    enabledByRequest: Boolean,
  ): OperatorBridgeIssuedSession =
    synchronized(LOCK) {
      require(RustyKioskCliProtocol.validRequestId(operationId) != null) {
        "A valid bootstrap operation id is required."
      }
      val now = wallNow()
      val root = loadPurged(now, bridgeGeneration)
      // Persist observation before any later rate/concurrency/entropy rejection so a failed
      // forward-time issuance attempt cannot be followed by a clock rollback that resets policy.
      save(root)
      val lastObservedWallMs = root.optLong(KEY_LAST_OBSERVED_WALL_MS, -1L)
      val issuedOperations = issuedOperations(root)
      val cleanupOwnerships = cleanupOwnerships(root)
      val recent = root.getJSONArray(KEY_RECENT_ISSUES)
      val recentValues = (0 until recent.length()).map { recent.getLong(it) }
        .filter { it in (now - RATE_WINDOW_MS)..now }
      val sessions = root.getJSONArray(KEY_SESSIONS)
      val secretBytes = randomBytes(SESSION_SECRET_BYTES)
      OperatorBridgeSessionPolicy.requireIssuanceAllowed(
        now,
        bridgeGeneration,
        operationId in issuedOperations ||
          cleanupOwnerships.any { it.operationId == operationId },
        recentValues,
        sessions.length(),
        issuedOperations.size,
        secretBytes.size,
        lastObservedWallMs,
      )
      val sessionId = Base64.encodeToString(randomBytes(18), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
      val secretBase64 = Base64.encodeToString(secretBytes, Base64.NO_WRAP)
      val expiresAt = now + SESSION_LIFETIME_MS
      require(now <= Long.MAX_VALUE - CLEANUP_OWNERSHIP_LIFETIME_MS) {
        "The cleanup-ownership clock is invalid."
      }
      val cleanupExpiresAt = now + CLEANUP_OWNERSHIP_LIFETIME_MS
      sessions.put(JSONObject()
        .put("session_id", sessionId)
        .put("operation_id", operationId)
        .put("secret_base64", secretBase64)
        .put("capability", CAPABILITY)
        .put("bridge_generation", bridgeGeneration)
        .put("issued_at_ms", now)
        .put("expires_at_ms", expiresAt)
        .put("first_used_at_ms", JSONObject.NULL)
        .put("last_used_at_ms", JSONObject.NULL))
      val nextRecent = JSONArray(recentValues + now)
      val nextOperations = JSONArray(
        OperatorBridgeOperationLedgerPolicy.append(issuedOperations, operationId)
      )
      val nextCleanupOwnerships = OperatorBridgeCleanupOwnershipPolicy.retainBounded(
        cleanupOwnerships + OperatorBridgeCleanupOwnership(
          operationId,
          sessionId,
          bridgeGeneration,
          enabledByRequest,
          now,
          cleanupExpiresAt,
        ),
        now,
        bridgeGeneration,
      )
      root.put(KEY_RECENT_ISSUES, nextRecent)
        .put(KEY_ISSUED_OPERATIONS, nextOperations)
        .put(KEY_CLEANUP_OWNERSHIPS, encodeCleanupOwnerships(nextCleanupOwnerships))
      save(root)
      OperatorBridgeIssuedSession(operationId, sessionId, secretBase64, bridgeGeneration, now, expiresAt)
    }

  fun resolve(sessionId: String, bridgeGeneration: Long): OperatorBridgeSessionCredential? =
    synchronized(LOCK) {
      if (!SESSION_ID.matches(sessionId) || bridgeGeneration <= 0L) return@synchronized null
      val now = wallNow()
      val root = runCatching { loadPurged(now, bridgeGeneration) }
        .getOrElse { return@synchronized null }
      val sessions = root.getJSONArray(KEY_SESSIONS)
      val index = (0 until sessions.length()).singleOrNull {
        sessions.getJSONObject(it).optString("session_id") == sessionId
      } ?: run {
        save(root)
        return@synchronized null
      }
      val item = sessions.getJSONObject(index)
      if (!OperatorBridgeSessionPolicy.isUsable(
          now,
          item.optLong("issued_at_ms"),
          item.optLong("expires_at_ms"),
          item.optLong("bridge_generation"),
          bridgeGeneration,
        ) || item.optString("capability") != CAPABILITY) {
        save(root)
        return@synchronized null
      }
      save(root)
      val secretBytes = runCatching {
        Base64.decode(item.getString("secret_base64"), Base64.DEFAULT)
      }.getOrNull()?.takeIf { it.size == SESSION_SECRET_BYTES } ?: return@synchronized null
      OperatorBridgeSessionCredential(
        sessionId,
        secretBytes,
        bridgeGeneration,
        item.getLong("expires_at_ms"),
      )
    }

  fun recordAuthenticatedUse(sessionId: String, bridgeGeneration: Long): Boolean =
    synchronized(LOCK) {
      if (!SESSION_ID.matches(sessionId)) return@synchronized false
      val now = wallNow()
      val root = runCatching { loadPurged(now, bridgeGeneration) }
        .getOrElse { return@synchronized false }
      val sessions = root.getJSONArray(KEY_SESSIONS)
      val item = (0 until sessions.length()).map { sessions.getJSONObject(it) }
        .singleOrNull { it.optString("session_id") == sessionId }
        ?: return@synchronized false
      if (item.isNull("first_used_at_ms")) item.put("first_used_at_ms", now)
      item.put("last_used_at_ms", now)
      save(root)
      true
    }

  fun ownedCleanupEnable(
    operationId: String,
    sessionId: String,
    bridgeGeneration: Long,
  ): OperatorBridgeCleanupOwnership? =
    synchronized(LOCK) {
      val now = wallNow()
      val root = runCatching { loadPurged(now, bridgeGeneration) }
        .getOrElse { return@synchronized null }
      val owned = OperatorBridgeCleanupOwnershipPolicy.exactOwnedEnable(
        cleanupOwnerships(root),
        operationId,
        sessionId,
        bridgeGeneration,
        now,
        bridgeGeneration,
      )
      save(root)
      owned
    }

  fun recoverableCleanupEnable(
    operationId: String,
    bridgeGeneration: Long,
  ): OperatorBridgeCleanupOwnership? = synchronized(LOCK) {
    if (RustyKioskCliProtocol.validRequestId(operationId) == null || bridgeGeneration <= 0L) {
      return@synchronized null
    }
    val now = wallNow()
    val root = runCatching { loadPurged(now, bridgeGeneration) }
      .getOrElse { return@synchronized null }
    val ownership = OperatorBridgeCleanupOwnershipPolicy.recoverableOwnedEnable(
      cleanupOwnerships(root),
      operationId,
      now,
      bridgeGeneration,
    )
    save(root)
    ownership
  }

  fun dispatchedCleanupDisable(
    operationId: String,
    bridgeGeneration: Long,
  ): OperatorBridgeCleanupOwnership? = synchronized(LOCK) {
    if (RustyKioskCliProtocol.validRequestId(operationId) == null || bridgeGeneration <= 0L) {
      return@synchronized null
    }
    val now = wallNow()
    val root = runCatching { loadPurged(now, bridgeGeneration) }
      .getOrElse { return@synchronized null }
    val ownership = OperatorBridgeCleanupOwnershipPolicy.dispatchedDisable(
      cleanupOwnerships(root),
      operationId,
      now,
      bridgeGeneration,
    )
    save(root)
    ownership
  }

  /** Persist retry authority before the generation mutation can dispatch STOP or lose its reply. */
  fun prepareDisableDispatch(
    ownership: OperatorBridgeCleanupOwnership,
    postDisableGeneration: Long,
  ): Boolean = synchronized(LOCK) {
    if (ownership.state != OperatorBridgeCleanupOwnershipState.ENABLE_OWNED ||
      !ownership.enabledByRequest || postDisableGeneration <= 0L
    ) return@synchronized false
    val now = wallNow()
    val root = runCatching { loadPurged(now, ownership.bridgeGeneration) }
      .getOrElse { return@synchronized false }
    val current = OperatorBridgeCleanupOwnershipPolicy.exactOwnedEnable(
      cleanupOwnerships(root),
      ownership.operationId,
      ownership.sessionId,
      ownership.bridgeGeneration,
      now,
      ownership.bridgeGeneration,
    ) ?: return@synchronized false
    val prepared = current.copy(
      bridgeGeneration = postDisableGeneration,
      state = OperatorBridgeCleanupOwnershipState.DISABLE_DISPATCHED,
    )
    val existing = cleanupOwnerships(root).filterNot {
      it.state == OperatorBridgeCleanupOwnershipState.DISABLE_DISPATCHED &&
        it.operationId == prepared.operationId
    }
    root.put(
      KEY_CLEANUP_OWNERSHIPS,
      encodeCleanupOwnerships(
        (existing + prepared).takeLast(MAX_CLEANUP_OWNERSHIP_TOMBSTONES)
      ),
    )
    save(root)
    true
  }

  fun consumeCompletedDisableDispatches(bridgeGeneration: Long): Int = synchronized(LOCK) {
    if (bridgeGeneration <= 0L) return@synchronized 0
    val now = wallNow()
    val root = runCatching { loadPurged(now, bridgeGeneration) }
      .getOrElse { return@synchronized 0 }
    val ownerships = cleanupOwnerships(root)
    val (retained, consumed) = OperatorBridgeCleanupOwnershipPolicy.consumeCompletedDisables(
      ownerships,
      bridgeGeneration,
      stoppedReadbackConverged = true,
    )
    root.put(KEY_CLEANUP_OWNERSHIPS, encodeCleanupOwnerships(retained))
    save(root)
    consumed
  }

  fun revokeGeneration(bridgeGeneration: Long) = synchronized(LOCK) {
    val now = wallNow()
    val root = loadState()
    val lastObserved = root.optLong(KEY_LAST_OBSERVED_WALL_MS, -1L)
    val recordedNow = if (now >= 0L && now >= lastObserved) now else lastObserved.coerceAtLeast(0L)
    val sessions = root.getJSONArray(KEY_SESSIONS)
    val retained = JSONArray()
    for (index in 0 until sessions.length()) {
      val item = sessions.getJSONObject(index)
      if (item.optLong("bridge_generation") != bridgeGeneration) retained.put(item)
    }
    val retainedCleanupOwnerships = cleanupOwnerships(root).filter {
      it.bridgeGeneration != bridgeGeneration
    }
    root.put(KEY_SESSIONS, retained)
      .put(KEY_CLEANUP_OWNERSHIPS, encodeCleanupOwnerships(retainedCleanupOwnerships))
      .put("last_revoked_generation", bridgeGeneration)
      .put("last_revoked_at_ms", recordedNow)
      .put(KEY_LAST_OBSERVED_WALL_MS, recordedNow)
    save(root)
  }

  fun activeSessionCount(bridgeGeneration: Long): Int = synchronized(LOCK) {
    val root = runCatching { loadPurged(wallNow(), bridgeGeneration) }
      .getOrElse { return@synchronized 0 }
    save(root)
    root.getJSONArray(KEY_SESSIONS).length()
  }

  private fun loadPurged(now: Long, bridgeGeneration: Long): JSONObject {
    val root = loadState()
    val lastObserved = root.optLong(KEY_LAST_OBSERVED_WALL_MS, -1L)
    require(now >= 0L && (lastObserved < 0L || now >= lastObserved)) {
      "The session-store clock moved backwards."
    }
    val source = root.optJSONArray(KEY_SESSIONS) ?: JSONArray()
    val retained = JSONArray()
    for (index in 0 until source.length()) {
      val item = source.optJSONObject(index) ?: continue
      val issued = item.optLong("issued_at_ms", -1L)
      val expires = item.optLong("expires_at_ms", -1L)
      if (OperatorBridgeSessionPolicy.isUsable(
          now,
          issued,
          expires,
          item.optLong("bridge_generation"),
          bridgeGeneration,
        )) {
        retained.put(item)
      }
    }
    val retainedCleanupOwnerships = OperatorBridgeCleanupOwnershipPolicy.retainBounded(
      cleanupOwnerships(root),
      now,
      bridgeGeneration,
    )
    return root
      .put(KEY_SESSIONS, retained)
      .put(KEY_CLEANUP_OWNERSHIPS, encodeCleanupOwnerships(retainedCleanupOwnerships))
      .put(KEY_RECENT_ISSUES, root.getJSONArray(KEY_RECENT_ISSUES))
      .put(KEY_ISSUED_OPERATIONS, root.getJSONArray(KEY_ISSUED_OPERATIONS))
      .put(KEY_LAST_OBSERVED_WALL_MS, now)
  }

  private fun loadState(): JSONObject {
    val stored = preferences.getString(KEY_STATE, null)
    val fresh = stored == null
    val root = if (fresh) JSONObject() else JSONObject(requireNotNull(stored))
    OperatorBridgeStateShapePolicy.requireSchema(
      fresh,
      root.has(KEY_STATE_SCHEMA),
      root.opt(KEY_STATE_SCHEMA) == STATE_SCHEMA,
    )
    REQUIRED_ARRAY_KEYS.forEach { key ->
      OperatorBridgeStateShapePolicy.requireArray(
        fresh,
        root.has(key),
        root.opt(key) is JSONArray,
      )
    }
    if (fresh) {
      root.put(KEY_STATE_SCHEMA, STATE_SCHEMA)
      REQUIRED_ARRAY_KEYS.forEach { key -> root.put(key, JSONArray()) }
    }
    val storedEpoch = if (fresh) {
      null
    } else {
      val value = root.get(KEY_PROVIDER_EPOCH)
      require(value is String && value.isNotBlank()) {
        "Stored bootstrap state has a missing or invalid issuance epoch."
      }
      value
    }
    val epoch = OperatorBridgeOperationLedgerPolicy.requireEpoch(
      storedEpoch,
      providerEpochLocked(),
    )
    val operations = root.getJSONArray(KEY_ISSUED_OPERATIONS)
    OperatorBridgeOperationLedgerPolicy.normalize(
      (0 until operations.length()).map { operations.getString(it) }
    )
    return root
      .put(KEY_PROVIDER_EPOCH, epoch)
  }

  private fun providerEpochLocked(): String {
    val stored = preferences.getString(KEY_PROVIDER_EPOCH_ANCHOR, null)
    if (stored != null) {
      require(stored.isNotBlank()) { "The bootstrap issuance epoch anchor is malformed." }
      return stored
    }
    val generated = UUID.randomUUID().toString()
    check(preferences.edit().putString(KEY_PROVIDER_EPOCH_ANCHOR, generated).commit()) {
      "The bootstrap issuance epoch anchor could not be persisted."
    }
    return generated
  }

  private fun issuedOperations(root: JSONObject): List<String> {
    val source = root.getJSONArray(KEY_ISSUED_OPERATIONS)
    return OperatorBridgeOperationLedgerPolicy.normalize(
      (0 until source.length()).map { source.getString(it) }
    )
  }

  private fun cleanupOwnerships(root: JSONObject): List<OperatorBridgeCleanupOwnership> {
    val source = root.getJSONArray(KEY_CLEANUP_OWNERSHIPS)
    return buildList {
      for (index in 0 until source.length()) {
        val item = source.optJSONObject(index) ?: continue
        val operationId = item.optString("operation_id")
        val sessionId = item.optString("session_id")
        val generation = item.optLong("bridge_generation", -1L)
        val issuedAt = item.optLong("issued_at_ms", -1L)
        val expiresAt = item.optLong("expires_at_ms", -1L)
        val state = OperatorBridgeCleanupOwnershipState.entries.singleOrNull {
          it.wireName == item.optString("state", OperatorBridgeCleanupOwnershipState.ENABLE_OWNED.wireName)
        } ?: continue
        if (RustyKioskCliProtocol.validRequestId(operationId) == null ||
          !SESSION_ID.matches(sessionId) || generation <= 0L || issuedAt < 0L || expiresAt <= issuedAt
        ) continue
        add(
          OperatorBridgeCleanupOwnership(
            operationId,
            sessionId,
            generation,
            item.optBoolean("enabled_by_request", false),
            issuedAt,
            expiresAt,
            state,
          )
        )
      }
    }
  }

  private fun encodeCleanupOwnerships(
    ownerships: List<OperatorBridgeCleanupOwnership>,
  ): JSONArray = JSONArray().also { array ->
    ownerships.forEach { ownership ->
      array.put(
        JSONObject()
          .put("operation_id", ownership.operationId)
          .put("session_id", ownership.sessionId)
          .put("bridge_generation", ownership.bridgeGeneration)
          .put("enabled_by_request", ownership.enabledByRequest)
          .put("issued_at_ms", ownership.issuedAtMs)
          .put("expires_at_ms", ownership.expiresAtMs)
          .put("state", ownership.state.wireName)
      )
    }
  }

  private fun save(root: JSONObject) {
    check(preferences.edit().putString(KEY_STATE, root.toString()).commit()) {
      "The bootstrap session and operation ledger could not be persisted."
    }
  }

  companion object {
    const val SESSION_LIFETIME_MS = 5 * 60 * 1000L
    const val CLEANUP_OWNERSHIP_LIFETIME_MS = 24L * 60L * 60L * 1000L
    const val MAX_CONCURRENT_SESSIONS = 4
    const val MAX_ISSUES_PER_WINDOW = 4
    const val SESSION_SECRET_BYTES = 32
    const val MAX_CLEANUP_OWNERSHIP_TOMBSTONES = 512
    const val CAPABILITY = "rusty.kiosk.direct_operator.v2"
    private const val RATE_WINDOW_MS = OperatorBridgeSessionPolicy.RATE_WINDOW_MS
    const val MAX_OPERATION_IDS_PER_EPOCH = 4096
    private const val PREFERENCES = "rusty_kiosk_operator_sessions"
    private const val KEY_STATE = "state_json"
    private const val KEY_STATE_SCHEMA = "schema"
    private const val STATE_SCHEMA = "rusty.kiosk.operator_session_state.v1"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_RECENT_ISSUES = "recent_issues"
    private const val KEY_ISSUED_OPERATIONS = "issued_operations"
    private const val KEY_CLEANUP_OWNERSHIPS = "cleanup_ownerships"
    private const val KEY_LAST_OBSERVED_WALL_MS = "last_observed_wall_ms"
    private const val KEY_PROVIDER_EPOCH = "provider_epoch"
    private const val KEY_PROVIDER_EPOCH_ANCHOR = "provider_epoch_anchor"
    private val REQUIRED_ARRAY_KEYS = listOf(
      KEY_SESSIONS,
      KEY_RECENT_ISSUES,
      KEY_ISSUED_OPERATIONS,
      KEY_CLEANUP_OWNERSHIPS,
    )
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{16,64}")
    private val LOCK = Any()
  }
}
