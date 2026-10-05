package io.github.mesmerprism.rustykiosk

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class OperatorBridgeAuthHeaders(
  val requestId: String,
  val timestampSeconds: Long,
  val contentSha256: String,
  val signature: String,
  val sessionId: String? = null,
)

internal object OperatorBridgeAuth {
  const val HEADER_REQUEST_ID = "x-rusty-request-id"
  const val HEADER_TIMESTAMP = "x-rusty-timestamp"
  const val HEADER_CONTENT_SHA256 = "x-rusty-content-sha256"
  const val HEADER_SIGNATURE = "x-rusty-signature"
  const val HEADER_SESSION_ID = "x-rusty-session-id"
  const val MAX_CLOCK_SKEW_SECONDS = 90L

  private val requestIdPattern = Regex("[A-Za-z0-9_-]{8,64}")
  private val shaPattern = Regex("[0-9a-f]{64}")
  private val sessionIdPattern = Regex("[A-Za-z0-9_-]{16,64}")

  fun parse(headers: Map<String, String>): Result<OperatorBridgeAuthHeaders> =
    runCatching {
      val normalized = headers.mapKeys { (key, _) -> key.lowercase(Locale.ROOT) }
      val requestId = normalized[HEADER_REQUEST_ID].orEmpty()
      require(requestIdPattern.matches(requestId)) { "A valid request id is required." }
      val timestamp = normalized[HEADER_TIMESTAMP]?.toLongOrNull()
        ?: error("A valid request timestamp is required.")
      val contentSha = normalized[HEADER_CONTENT_SHA256].orEmpty().lowercase(Locale.ROOT)
      require(shaPattern.matches(contentSha)) { "A valid content SHA-256 is required." }
      val signature = normalized[HEADER_SIGNATURE].orEmpty().lowercase(Locale.ROOT)
      require(shaPattern.matches(signature)) { "A valid HMAC signature is required." }
      val sessionId = normalized[HEADER_SESSION_ID]?.also { value ->
        require(sessionIdPattern.matches(value)) { "A valid ephemeral session id is required." }
      }
      OperatorBridgeAuthHeaders(requestId, timestamp, contentSha, signature, sessionId)
    }

  fun verify(
    pairingKey: String,
    method: String,
    requestTarget: String,
    body: ByteArray,
    headers: OperatorBridgeAuthHeaders,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
  ): Result<Unit> = verify(
    pairingKey.toByteArray(StandardCharsets.UTF_8), method, requestTarget, body, headers, nowSeconds
  )

  fun verify(
    pairingKey: ByteArray,
    method: String,
    requestTarget: String,
    body: ByteArray,
    headers: OperatorBridgeAuthHeaders,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
  ): Result<Unit> =
    runCatching {
      require(kotlin.math.abs(nowSeconds - headers.timestampSeconds) <= MAX_CLOCK_SKEW_SECONDS) {
        "The signed request has expired or the clocks are too far apart."
      }
      verifyDigest(
          pairingKey,
          method,
          requestTarget,
          sha256(body),
          headers,
          nowSeconds,
        )
        .getOrThrow()
    }

  fun verifyDigest(
    pairingKey: String,
    method: String,
    requestTarget: String,
    actualContentSha256: String,
    headers: OperatorBridgeAuthHeaders,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
  ): Result<Unit> = verifyDigest(
    pairingKey.toByteArray(StandardCharsets.UTF_8),
    method,
    requestTarget,
    actualContentSha256,
    headers,
    nowSeconds,
  )

  fun verifyDigest(
    pairingKey: ByteArray,
    method: String,
    requestTarget: String,
    actualContentSha256: String,
    headers: OperatorBridgeAuthHeaders,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
  ): Result<Unit> =
    runCatching {
      require(kotlin.math.abs(nowSeconds - headers.timestampSeconds) <= MAX_CLOCK_SKEW_SECONDS) {
        "The signed request has expired or the clocks are too far apart."
      }
      require(constantTimeEquals(actualContentSha256, headers.contentSha256)) {
        "The request body did not match its signed digest."
      }
      val expected =
        sign(
          pairingKey = pairingKey,
          method = method,
          requestTarget = requestTarget,
          requestId = headers.requestId,
          timestampSeconds = headers.timestampSeconds,
          contentSha256 = headers.contentSha256,
        )
      require(constantTimeEquals(expected, headers.signature)) {
        "The request signature was not accepted."
      }
    }

  fun sign(
    pairingKey: String,
    method: String,
    requestTarget: String,
    requestId: String,
    timestampSeconds: Long,
    contentSha256: String,
  ): String = sign(
    pairingKey.toByteArray(StandardCharsets.UTF_8),
    method,
    requestTarget,
    requestId,
    timestampSeconds,
    contentSha256,
  )

  fun sign(
    pairingKey: ByteArray,
    method: String,
    requestTarget: String,
    requestId: String,
    timestampSeconds: Long,
    contentSha256: String,
  ): String {
    require(requestIdPattern.matches(requestId))
    require(shaPattern.matches(contentSha256.lowercase(Locale.ROOT)))
    val canonical =
      listOf(
          method.uppercase(Locale.ROOT),
          requestTarget,
          requestId,
          timestampSeconds.toString(),
          contentSha256.lowercase(Locale.ROOT),
        )
        .joinToString("\n")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(pairingKey, "HmacSHA256"))
    return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).toHex()
  }

  fun signResponse(
    pairingKey: String,
    requestId: String,
    statusCode: Int,
    contentSha256: String,
  ): String = signResponse(
    pairingKey.toByteArray(StandardCharsets.UTF_8), requestId, statusCode, contentSha256
  )

  fun signResponse(
    pairingKey: ByteArray,
    requestId: String,
    statusCode: Int,
    contentSha256: String,
  ): String {
    require(requestIdPattern.matches(requestId))
    require(shaPattern.matches(contentSha256.lowercase(Locale.ROOT)))
    val canonical =
      listOf(
          "RESPONSE",
          requestId,
          statusCode.toString(),
          contentSha256.lowercase(Locale.ROOT),
        )
        .joinToString("\n")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(pairingKey, "HmacSHA256"))
    return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).toHex()
  }

  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

  private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

  private fun constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.toByteArray(StandardCharsets.US_ASCII),
      right.toByteArray(StandardCharsets.US_ASCII),
    )
}
