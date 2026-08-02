package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

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

internal object OperatorBridgeSessionPolicy {
  fun requireIssuanceAllowed(
    now: Long,
    bridgeGeneration: Long,
    operationAlreadyUsed: Boolean,
    recentIssueTimes: List<Long>,
    activeSessionCount: Int,
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

  fun issue(operationId: String, bridgeGeneration: Long): OperatorBridgeIssuedSession =
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
      val issuedOperations = root.getJSONArray(KEY_ISSUED_OPERATIONS)
      val recent = root.getJSONArray(KEY_RECENT_ISSUES)
      val recentValues = (0 until recent.length()).map { recent.getLong(it) }
        .filter { it in (now - RATE_WINDOW_MS)..now }
      val sessions = root.getJSONArray(KEY_SESSIONS)
      val secretBytes = randomBytes(SESSION_SECRET_BYTES)
      OperatorBridgeSessionPolicy.requireIssuanceAllowed(
        now,
        bridgeGeneration,
        (0 until issuedOperations.length()).any { issuedOperations.getString(it) == operationId },
        recentValues,
        sessions.length(),
        secretBytes.size,
        lastObservedWallMs,
      )
      val sessionId = Base64.encodeToString(randomBytes(18), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
      val secretBase64 = Base64.encodeToString(secretBytes, Base64.NO_WRAP)
      val expiresAt = now + SESSION_LIFETIME_MS
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
        ((0 until issuedOperations.length()).map { issuedOperations.getString(it) } + operationId)
          .takeLast(MAX_OPERATION_TOMBSTONES)
      )
      root.put(KEY_RECENT_ISSUES, nextRecent).put(KEY_ISSUED_OPERATIONS, nextOperations)
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

  fun owns(operationId: String, sessionId: String, bridgeGeneration: Long): Boolean =
    synchronized(LOCK) {
      val now = wallNow()
      val root = runCatching { loadPurged(now, bridgeGeneration) }
        .getOrElse { return@synchronized false }
      val sessions = root.getJSONArray(KEY_SESSIONS)
      val owned = (0 until sessions.length()).any {
        val item = sessions.getJSONObject(it)
        item.optString("operation_id") == operationId &&
          item.optString("session_id") == sessionId &&
          item.optLong("bridge_generation") == bridgeGeneration
      }
      save(root)
      owned
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
    root.put(KEY_SESSIONS, retained)
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
    return root
      .put(KEY_SESSIONS, retained)
      .put(KEY_RECENT_ISSUES, root.optJSONArray(KEY_RECENT_ISSUES) ?: JSONArray())
      .put(KEY_ISSUED_OPERATIONS, root.optJSONArray(KEY_ISSUED_OPERATIONS) ?: JSONArray())
      .put(KEY_LAST_OBSERVED_WALL_MS, now)
  }

  private fun loadState(): JSONObject {
    val root = runCatching { JSONObject(preferences.getString(KEY_STATE, null) ?: "{}") }
      .getOrDefault(JSONObject())
    return root
      .put(KEY_SESSIONS, root.optJSONArray(KEY_SESSIONS) ?: JSONArray())
      .put(KEY_RECENT_ISSUES, root.optJSONArray(KEY_RECENT_ISSUES) ?: JSONArray())
      .put(KEY_ISSUED_OPERATIONS, root.optJSONArray(KEY_ISSUED_OPERATIONS) ?: JSONArray())
  }

  private fun save(root: JSONObject) {
    preferences.edit().putString(KEY_STATE, root.toString()).commit()
  }

  companion object {
    const val SESSION_LIFETIME_MS = 5 * 60 * 1000L
    const val MAX_CONCURRENT_SESSIONS = 4
    const val MAX_ISSUES_PER_WINDOW = 4
    const val SESSION_SECRET_BYTES = 32
    const val CAPABILITY = "rusty.kiosk.direct_operator.v1"
    private const val RATE_WINDOW_MS = OperatorBridgeSessionPolicy.RATE_WINDOW_MS
    private const val MAX_OPERATION_TOMBSTONES = 128
    private const val PREFERENCES = "rusty_kiosk_operator_sessions"
    private const val KEY_STATE = "state_json"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_RECENT_ISSUES = "recent_issues"
    private const val KEY_ISSUED_OPERATIONS = "issued_operations"
    private const val KEY_LAST_OBSERVED_WALL_MS = "last_observed_wall_ms"
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{16,64}")
    private val LOCK = Any()
  }
}
