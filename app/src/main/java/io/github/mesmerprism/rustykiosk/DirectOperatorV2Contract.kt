package io.github.mesmerprism.rustykiosk

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

internal enum class DirectOperatorV2Capability(val wireName: String) {
  CATALOG_SUMMARY("catalog-summary"),
  CATALOG_DETAIL("catalog-detail"),
  APP_LAUNCH("app-launch"),
  ;

  companion object {
    fun parse(value: String): DirectOperatorV2Capability =
      entries.singleOrNull { it.wireName == value }
        ?: throw IllegalArgumentException("Unknown direct-operator v2 capability.")
  }
}

internal data class DirectOperatorV2Grant(
  val granted: Boolean,
  val epoch: String,
)

internal data class DirectOperatorV2Profile(
  val pairingId: String,
  val keyEpoch: String,
  val ownerEpoch: String,
  val kdfSalt: String,
  val summaryGrant: DirectOperatorV2Grant,
  val detailGrant: DirectOperatorV2Grant,
) {
  init {
    require(TOKEN.matches(pairingId)) { "Invalid v2 pairing identity." }
    require(TOKEN.matches(keyEpoch)) { "Invalid v2 key epoch." }
    require(TOKEN.matches(ownerEpoch)) { "Invalid v2 owner epoch." }
    require(DirectOperatorV2Base64.decodeExact(kdfSalt, DirectOperatorV2Crypto.KDF_SALT_BYTES) != null) {
      "Invalid v2 KDF salt."
    }
    require(TOKEN.matches(summaryGrant.epoch) && TOKEN.matches(detailGrant.epoch)) {
      "Invalid v2 capability epoch."
    }
    require(!detailGrant.granted || summaryGrant.granted) {
      "Catalog detail cannot outlive the catalog-summary grant."
    }
  }

  fun allows(capability: DirectOperatorV2Capability): Boolean =
    when (capability) {
      DirectOperatorV2Capability.CATALOG_SUMMARY -> summaryGrant.granted
      DirectOperatorV2Capability.CATALOG_DETAIL -> detailGrant.granted
      DirectOperatorV2Capability.APP_LAUNCH -> false
    }

  companion object {
    private val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")
  }
}

internal object DirectOperatorV2RoutePolicy {
  const val CONTRACT_PATH = "/v2/contract"
  const val SESSION_OPEN_PATH = "/v2/session/open"
  const val CATALOG_SUMMARY_PATH = "/v2/catalog/summary"
  const val CATALOG_DETAIL_PATH = "/v2/catalog/detail"
  const val LAUNCH_ISSUE_PATH = "/v2/launch/reference/issue"
  const val LAUNCH_EXECUTE_PATH = "/v2/launch/reference/execute"

  fun capability(method: String, path: String): DirectOperatorV2Capability? =
    when ("${method.uppercase()} $path") {
      "POST $CATALOG_SUMMARY_PATH" -> DirectOperatorV2Capability.CATALOG_SUMMARY
      "POST $CATALOG_DETAIL_PATH" -> DirectOperatorV2Capability.CATALOG_DETAIL
      "POST $LAUNCH_ISSUE_PATH",
      "POST $LAUNCH_EXECUTE_PATH" -> DirectOperatorV2Capability.APP_LAUNCH
      else -> null
    }

  fun publicContract(profile: DirectOperatorV2Profile): JSONObject =
    JSONObject()
      .put("schema", DirectOperatorV2Crypto.PROFILE_SCHEMA)
      .put("profile", DirectOperatorV2Crypto.PROFILE)
      .put("session_open_schema", DirectOperatorV2SessionOpenRequest.SCHEMA)
      .put("request_envelope_schema", DirectOperatorV2RequestEnvelope.SCHEMA)
      .put("response_envelope_schema", DirectOperatorV2ResponseEnvelope.SCHEMA)
      .put("kdf", DirectOperatorV2Crypto.KDF)
      .put("aead", DirectOperatorV2Crypto.AEAD)
      .put("key_bits", DirectOperatorV2Crypto.KEY_BITS)
      .put("nonce_bytes", DirectOperatorV2Crypto.NONCE_BYTES)
      .put("session_lifetime_ms", DirectOperatorV2SessionManager.MAX_SESSION_LIFETIME_MS)
      .put("request_lifetime_ms", DirectOperatorV2SessionManager.MAX_REQUEST_LIFETIME_MS)
      .put("pairing_id", profile.pairingId)
      .put("key_epoch", profile.keyEpoch)
      .put("owner_epoch", profile.ownerEpoch)
      .put("kdf_salt", profile.kdfSalt)
      .put("catalog_summary_granted", profile.summaryGrant.granted)
      .put("catalog_summary_grant_epoch", profile.summaryGrant.epoch)
      .put("catalog_detail_granted", profile.detailGrant.granted)
      .put("catalog_detail_grant_epoch", profile.detailGrant.epoch)
      .put(
        "available_capabilities",
        JSONArray(
          DirectOperatorV2Capability.entries
            .filter(profile::allows)
            .map(DirectOperatorV2Capability::wireName)
        ),
      )
      .put(
        "defined_but_inactive_capabilities",
        JSONArray(listOf(DirectOperatorV2Capability.APP_LAUNCH.wireName)),
      )
      .put("launch_issue_schema", "rusty.kiosk.launch_reference_issue.v1")
      .put("launch_execute_schema", "rusty.kiosk.launch_reference_execute.v1")
      .put("normal_launch_scope", "kiosk.launch-catalog-entry-normal")
      .put("launch_authority_active", false)
      .put("direct_v1_catalog_allowed", false)
      .put("direct_v1_launch_allowed", false)
      .put("catalog_payload_cleartext", false)
      .put("arbitrary_intents", false)
      .put("arbitrary_targets", false)
}

internal data class DirectOperatorV2SessionOpenRequest(
  val pairingId: String,
  val keyEpoch: String,
  val ownerEpoch: String,
  val summaryGrantEpoch: String,
  val detailGrantEpoch: String,
  val requestId: String,
  val issuedAtMs: Long,
  val expiresAtMs: Long,
  val capabilities: List<DirectOperatorV2Capability>,
  val clientNonce: String,
  val proof: String,
) {
  fun proofParts(): List<String> =
    listOf(
      SCHEMA,
      DirectOperatorV2Crypto.PROFILE,
      pairingId,
      keyEpoch,
      ownerEpoch,
      summaryGrantEpoch,
      detailGrantEpoch,
      requestId,
      issuedAtMs.toString(),
      expiresAtMs.toString(),
      capabilities.joinToString(",") { it.wireName },
      clientNonce,
    )

  companion object {
    const val SCHEMA = "rusty.kiosk.direct_operator.session_open.v2"

    fun parseStrict(json: String): DirectOperatorV2SessionOpenRequest {
      val root = DirectOperatorV2Json.parse(json)
      root.requireExactFields(
        "schema",
        "profile",
        "pairing_id",
        "key_epoch",
        "owner_epoch",
        "catalog_summary_grant_epoch",
        "catalog_detail_grant_epoch",
        "request_id",
        "issued_at_ms",
        "expires_at_ms",
        "capabilities",
        "client_nonce",
        "proof",
      )
      require(root.requireString("schema") == SCHEMA) { "Unsupported session-open schema." }
      require(root.requireString("profile") == DirectOperatorV2Crypto.PROFILE) {
        "Direct-operator profile downgrade rejected."
      }
      val capabilities =
        root.getJSONArray("capabilities").strictStrings(MAX_CAPABILITIES)
          .map(DirectOperatorV2Capability::parse)
      require(capabilities.isNotEmpty() && capabilities.distinct() == capabilities) {
        "Capabilities must be nonempty, unique, and canonical."
      }
      require(capabilities == capabilities.sortedBy { it.wireName }) {
        "Capabilities must use canonical order."
      }
      return DirectOperatorV2SessionOpenRequest(
        pairingId = root.requireToken("pairing_id"),
        keyEpoch = root.requireToken("key_epoch"),
        ownerEpoch = root.requireToken("owner_epoch"),
        summaryGrantEpoch = root.requireToken("catalog_summary_grant_epoch"),
        detailGrantEpoch = root.requireToken("catalog_detail_grant_epoch"),
        requestId = root.requireRequestId("request_id"),
        issuedAtMs = root.requirePositiveLong("issued_at_ms"),
        expiresAtMs = root.requirePositiveLong("expires_at_ms"),
        capabilities = capabilities,
        clientNonce =
          root.requireString("client_nonce").also {
            require(DirectOperatorV2Base64.decodeExact(it, DirectOperatorV2Crypto.CLIENT_NONCE_BYTES) != null) {
              "Invalid client nonce."
            }
          },
        proof = root.requireSha256("proof"),
      )
    }

    private const val MAX_CAPABILITIES = 3
  }
}

internal data class DirectOperatorV2SessionOpenResponse(
  val pairingId: String,
  val keyEpoch: String,
  val ownerEpoch: String,
  val summaryGrantEpoch: String,
  val detailGrantEpoch: String,
  val requestId: String,
  val responseId: String,
  val sessionId: String,
  val issuedAtMs: Long,
  val expiresAtMs: Long,
  val capabilities: List<DirectOperatorV2Capability>,
  val serverNonce: String,
  val requestNoncePrefix: String,
  val responseNoncePrefix: String,
  val proof: String,
) {
  fun proofParts(): List<String> =
    listOf(
      SCHEMA,
      DirectOperatorV2Crypto.PROFILE,
      pairingId,
      keyEpoch,
      ownerEpoch,
      summaryGrantEpoch,
      detailGrantEpoch,
      requestId,
      responseId,
      sessionId,
      issuedAtMs.toString(),
      expiresAtMs.toString(),
      capabilities.joinToString(",") { it.wireName },
      serverNonce,
      requestNoncePrefix,
      responseNoncePrefix,
    )

  fun toJson(): JSONObject =
    JSONObject()
      .put("schema", SCHEMA)
      .put("profile", DirectOperatorV2Crypto.PROFILE)
      .put("pairing_id", pairingId)
      .put("key_epoch", keyEpoch)
      .put("owner_epoch", ownerEpoch)
      .put("catalog_summary_grant_epoch", summaryGrantEpoch)
      .put("catalog_detail_grant_epoch", detailGrantEpoch)
      .put("request_id", requestId)
      .put("response_id", responseId)
      .put("session_id", sessionId)
      .put("issued_at_ms", issuedAtMs)
      .put("expires_at_ms", expiresAtMs)
      .put("capabilities", JSONArray(capabilities.map { it.wireName }))
      .put("server_nonce", serverNonce)
      .put("request_nonce_prefix", requestNoncePrefix)
      .put("response_nonce_prefix", responseNoncePrefix)
      .put("proof", proof)

  companion object {
    const val SCHEMA = "rusty.kiosk.direct_operator.session_open_receipt.v2"
  }
}

internal data class DirectOperatorV2RequestEnvelope(
  val sessionId: String,
  val requestId: String,
  val counter: Long,
  val issuedAtMs: Long,
  val expiresAtMs: Long,
  val capability: DirectOperatorV2Capability,
  val nonce: String,
  val ciphertext: String,
) {
  companion object {
    const val SCHEMA = "rusty.kiosk.direct_operator.request_envelope.v2"

    fun parseStrict(json: String): DirectOperatorV2RequestEnvelope {
      val root = DirectOperatorV2Json.parse(json)
      root.requireExactFields(
        "schema",
        "profile",
        "session_id",
        "request_id",
        "counter",
        "issued_at_ms",
        "expires_at_ms",
        "capability",
        "nonce",
        "ciphertext",
      )
      require(root.requireString("schema") == SCHEMA) { "Unsupported v2 request envelope." }
      require(root.requireString("profile") == DirectOperatorV2Crypto.PROFILE) {
        "Direct-operator profile downgrade rejected."
      }
      return DirectOperatorV2RequestEnvelope(
        sessionId = root.requireToken("session_id"),
        requestId = root.requireRequestId("request_id"),
        counter = root.requirePositiveLong("counter"),
        issuedAtMs = root.requirePositiveLong("issued_at_ms"),
        expiresAtMs = root.requirePositiveLong("expires_at_ms"),
        capability = DirectOperatorV2Capability.parse(root.requireString("capability")),
        nonce =
          root.requireString("nonce").also {
            require(DirectOperatorV2Base64.decodeExact(it, DirectOperatorV2Crypto.NONCE_BYTES) != null) {
              "Invalid request nonce."
            }
          },
        ciphertext =
          root.requireString("ciphertext").also {
            require(it.length in 22..DirectOperatorV2Crypto.MAX_CIPHERTEXT_BASE64_CHARS) {
              "Invalid or oversized v2 ciphertext."
            }
            require(DirectOperatorV2Base64.decode(it) != null) { "Invalid v2 ciphertext encoding." }
          },
      )
    }
  }
}

internal data class DirectOperatorV2ResponseEnvelope(
  val sessionId: String,
  val requestId: String,
  val responseId: String,
  val requestCounter: Long,
  val requestDigest: String,
  val responseCounter: Long,
  val issuedAtMs: Long,
  val expiresAtMs: Long,
  val capability: DirectOperatorV2Capability,
  val nonce: String,
  val ciphertext: String,
) {
  fun toJson(): JSONObject =
    JSONObject()
      .put("schema", SCHEMA)
      .put("profile", DirectOperatorV2Crypto.PROFILE)
      .put("session_id", sessionId)
      .put("request_id", requestId)
      .put("response_id", responseId)
      .put("request_counter", requestCounter)
      .put("request_digest", requestDigest)
      .put("response_counter", responseCounter)
      .put("issued_at_ms", issuedAtMs)
      .put("expires_at_ms", expiresAtMs)
      .put("capability", capability.wireName)
      .put("nonce", nonce)
      .put("ciphertext", ciphertext)

  companion object {
    const val SCHEMA = "rusty.kiosk.direct_operator.response_envelope.v2"
  }
}

internal object DirectOperatorV2Canonical {
  fun encode(parts: List<String>): ByteArray {
    val output = ByteArrayOutputStream()
    parts.forEach { part ->
      val bytes = part.toByteArray(StandardCharsets.UTF_8)
      output.write(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
      output.write(':'.code)
      output.write(bytes)
    }
    return output.toByteArray()
  }
}

internal object DirectOperatorV2Base64 {
  fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  fun decode(value: String): ByteArray? =
    runCatching {
        val decoded = Base64.getUrlDecoder().decode(value)
        require(encode(decoded) == value) { "Non-canonical Base64 URL encoding." }
        decoded
      }
      .getOrNull()

  fun decodeExact(value: String, bytes: Int): ByteArray? = decode(value)?.takeIf { it.size == bytes }
}

private fun JSONObject.requireExactFields(vararg names: String) {
  require(keys().asSequence().toSet() == names.toSet()) {
    "Direct-operator fields do not match the strict schema."
  }
}

private fun JSONObject.requireToken(name: String): String =
  requireString(name).also {
    require(Regex("^[A-Za-z0-9_-]{16,128}$").matches(it)) { "Invalid $name." }
  }

private fun JSONObject.requireRequestId(name: String): String =
  requireString(name).also {
    require(Regex("^[A-Za-z0-9_-]{8,96}$").matches(it)) { "Invalid $name." }
  }

private fun JSONObject.requireSha256(name: String): String =
  requireString(name).also {
    require(Regex("^[0-9a-f]{64}$").matches(it)) { "Invalid $name." }
  }

private fun JSONObject.requirePositiveLong(name: String): Long =
  get(name).let { raw ->
    require(
      (raw is Int || raw is Long) &&
        raw.toString().matches(Regex("^[1-9][0-9]{0,18}$"))
    ) {
      "Invalid $name."
    }
    raw.toString().toLong()
  }

private fun JSONArray.strictStrings(maximum: Int): List<String> {
  require(length() in 0..maximum) { "Array exceeds its fixed bound." }
  return (0 until length()).map { index ->
    require(get(index) is String) { "Array entries must be strings." }
    getString(index)
  }
}

private fun JSONObject.requireString(name: String): String =
  get(name).also { require(it is String) { "Invalid $name type." } } as String
