package io.github.mesmerprism.rustykiosk

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class DirectOperatorV2SessionKeys(
  val requestKey: ByteArray,
  val responseKey: ByteArray,
)

internal object DirectOperatorV2Crypto {
  const val PROFILE = "rusty-kiosk-direct-v2"
  const val PROFILE_SCHEMA = "rusty.kiosk.direct_operator.v2"
  const val KDF = "HKDF-SHA256"
  const val AEAD = "AES-256-GCM"
  const val KEY_BITS = 256
  const val KEY_BYTES = KEY_BITS / 8
  const val KDF_SALT_BYTES = 32
  const val CLIENT_NONCE_BYTES = 32
  const val SERVER_NONCE_BYTES = 32
  const val NONCE_PREFIX_BYTES = 4
  const val NONCE_BYTES = 12
  const val MAX_PLAINTEXT_BYTES = 768 * 1024
  const val MAX_CIPHERTEXT_BASE64_CHARS = 1_048_600

  fun pairingSecret(pairingCode: String, profile: DirectOperatorV2Profile): ByteArray {
    require(pairingCode.length in 26..64) { "Invalid pairing material." }
    val salt =
      DirectOperatorV2Base64.decodeExact(profile.kdfSalt, KDF_SALT_BYTES)
        ?: error("Invalid KDF salt.")
    val extractSalt =
      DirectOperatorV2Canonical.encode(
        listOf(PROFILE, "extract-v1", profile.pairingId, profile.keyEpoch, profile.ownerEpoch)
      ) + salt
    return hkdfExtract(extractSalt, pairingCode.toByteArray(StandardCharsets.US_ASCII))
  }

  fun handshakeKey(pairingSecret: ByteArray, profile: DirectOperatorV2Profile): ByteArray =
    hkdfExpand(
      pairingSecret,
      DirectOperatorV2Canonical.encode(
        listOf(
          PROFILE,
          "handshake-key-v1",
          profile.pairingId,
          profile.keyEpoch,
          profile.ownerEpoch,
          profile.summaryGrant.epoch,
          profile.detailGrant.epoch,
        )
      ),
      KEY_BYTES,
    )

  fun deriveSessionKeys(
    pairingSecret: ByteArray,
    profile: DirectOperatorV2Profile,
    sessionId: String,
    clientNonce: String,
    serverNonce: String,
    capabilities: List<DirectOperatorV2Capability>,
  ): DirectOperatorV2SessionKeys {
    val common =
      listOf(
        PROFILE,
        profile.pairingId,
        profile.keyEpoch,
        profile.ownerEpoch,
        profile.summaryGrant.epoch,
        profile.detailGrant.epoch,
        sessionId,
        clientNonce,
        serverNonce,
        capabilities.joinToString(",") { it.wireName },
      )
    return DirectOperatorV2SessionKeys(
      requestKey =
        hkdfExpand(
          pairingSecret,
          DirectOperatorV2Canonical.encode(listOf("request-key-v1") + common),
          KEY_BYTES,
        ),
      responseKey =
        hkdfExpand(
          pairingSecret,
          DirectOperatorV2Canonical.encode(listOf("response-key-v1") + common),
          KEY_BYTES,
        ),
    )
  }

  fun proof(key: ByteArray, parts: List<String>): String =
    hmac(key, DirectOperatorV2Canonical.encode(parts)).toHex()

  fun verifyProof(key: ByteArray, parts: List<String>, supplied: String): Boolean =
    MessageDigest.isEqual(
      proof(key, parts).toByteArray(StandardCharsets.US_ASCII),
      supplied.toByteArray(StandardCharsets.US_ASCII),
    )

  fun nonce(prefix: ByteArray, counter: Long): ByteArray {
    require(prefix.size == NONCE_PREFIX_BYTES && counter > 0)
    return ByteBuffer.allocate(NONCE_BYTES).put(prefix).putLong(counter).array()
  }

  fun requestAad(
    method: String,
    path: String,
    envelope: DirectOperatorV2RequestEnvelope,
  ): ByteArray =
    DirectOperatorV2Canonical.encode(
      listOf(
        PROFILE,
        "request",
        DirectOperatorV2RequestEnvelope.SCHEMA,
        method.uppercase(),
        path,
        envelope.sessionId,
        envelope.requestId,
        envelope.counter.toString(),
        envelope.issuedAtMs.toString(),
        envelope.expiresAtMs.toString(),
        envelope.capability.wireName,
        envelope.nonce,
      )
    )

  fun responseAad(envelope: DirectOperatorV2ResponseEnvelope): ByteArray =
    DirectOperatorV2Canonical.encode(
      listOf(
        PROFILE,
        "response",
        DirectOperatorV2ResponseEnvelope.SCHEMA,
        envelope.sessionId,
        envelope.requestId,
        envelope.responseId,
        envelope.requestCounter.toString(),
        envelope.requestDigest,
        envelope.responseCounter.toString(),
        envelope.issuedAtMs.toString(),
        envelope.expiresAtMs.toString(),
        envelope.capability.wireName,
        envelope.nonce,
      )
    )

  fun encrypt(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plaintext: ByteArray,
  ): ByteArray {
    require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
    require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "V2 plaintext exceeds its fixed bound." }
    return Cipher.getInstance("AES/GCM/NoPadding").run {
      init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key.copyOf(), "AES"),
        GCMParameterSpec(128, nonce.copyOf()),
      )
      updateAAD(aad)
      doFinal(plaintext)
    }
  }

  fun decrypt(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    ciphertext: ByteArray,
  ): ByteArray {
    require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
    require(ciphertext.size in 16..(MAX_PLAINTEXT_BYTES + 16)) {
      "V2 ciphertext exceeds its fixed bound."
    }
    return Cipher.getInstance("AES/GCM/NoPadding").run {
      init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key.copyOf(), "AES"),
        GCMParameterSpec(128, nonce.copyOf()),
      )
      updateAAD(aad)
      doFinal(ciphertext).also {
        require(it.size <= MAX_PLAINTEXT_BYTES) { "V2 plaintext exceeds its fixed bound." }
      }
    }
  }

  internal fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
    hmac(salt, inputKeyMaterial)

  internal fun hkdfExpand(
    pseudoRandomKey: ByteArray,
    info: ByteArray,
    length: Int,
  ): ByteArray {
    require(length in 1..(255 * 32)) { "Invalid HKDF output length." }
    val output = ByteArray(length)
    var previous = ByteArray(0)
    var written = 0
    var counter = 1
    while (written < length) {
      val block = hmac(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
      val copied = minOf(block.size, length - written)
      block.copyInto(output, written, 0, copied)
      previous.fill(0)
      previous = block
      written += copied
      counter += 1
    }
    previous.fill(0)
    return output
  }

  private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key.copyOf(), "HmacSHA256"))
      doFinal(message)
    }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
