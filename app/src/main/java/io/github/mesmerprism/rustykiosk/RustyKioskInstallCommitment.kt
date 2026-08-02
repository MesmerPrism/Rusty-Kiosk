package io.github.mesmerprism.rustykiosk

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class RustyKioskInstallPartCommitment(
  val name: String,
  val bytes: Long,
  val sha256: String,
)

internal object RustyKioskInstallPartCommitmentPolicy {
  val SHA256 = Regex("[0-9a-f]{64}")

  fun parse(json: JSONObject): RustyKioskInstallPartCommitment {
    val fields = json.keys().asSequence().toSet()
    require(fields == setOf("name", "bytes", "sha256")) {
      "Each install part must contain only name, bytes, and sha256."
    }
    val rawName = json.get("name")
    val rawBytes = json.get("bytes")
    val rawSha = json.get("sha256")
    require(rawName is String && rawName.isNotBlank()) { "An install part name is required." }
    require(rawBytes is Number && POSITIVE_INTEGER.matches(rawBytes.toString())) {
      "An install part requires an exact positive integer byte count."
    }
    val bytes = rawBytes.toString().toLongOrNull()
      ?: throw IllegalArgumentException("The install part byte count is outside the supported range.")
    require(rawSha is String && SHA256.matches(rawSha)) {
      "An install part requires a lowercase SHA-256 commitment."
    }
    return RustyKioskInstallPartCommitment(rawName, bytes, rawSha)
  }

  /** Copies and verifies one opened source handle; callers must abandon the session on failure. */
  fun copyVerified(
    input: InputStream,
    output: OutputStream,
    commitment: RustyKioskInstallPartCommitment,
  ): Long {
    require(commitment.bytes > 0L && SHA256.matches(commitment.sha256)) {
      "The install part commitment is invalid."
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      require(read > 0) { "The staged APK stream made no progress." }
      require(copied <= commitment.bytes - read) {
        "The staged APK grew beyond its committed byte count."
      }
      digest.update(buffer, 0, read)
      output.write(buffer, 0, read)
      copied += read
    }
    require(copied == commitment.bytes) { "The staged APK byte count changed before copy completed." }
    val actualSha = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    require(actualSha == commitment.sha256) { "The staged APK digest changed before copy completed." }
    return copied
  }

  private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
}

internal object RustyKioskInstallCommitmentManifestPolicy {
  fun validate(
    commitments: List<RustyKioskInstallPartCommitment>,
  ): List<RustyKioskInstallPartCommitment> {
    require(commitments.size in 1..RustyKioskInstaller.MAX_APK_PARTS) {
      "The ordered install commitment set is empty or too large."
    }
    require(commitments.map { it.name }.distinct().size == commitments.size) {
      "The ordered install commitment set contains duplicate names."
    }
    commitments.forEach { commitment ->
      require(SAFE_APK_NAME.matches(commitment.name) &&
        commitment.name.substringAfterLast('.', "").equals("apk", ignoreCase = true)
      ) { "The ordered install commitment contains an invalid APK name." }
      require(commitment.bytes in 1..RustyKioskInstaller.MAX_APK_BYTES) {
        "The ordered install commitment contains an invalid byte count."
      }
      require(RustyKioskInstallPartCommitmentPolicy.SHA256.matches(commitment.sha256)) {
        "The ordered install commitment contains an invalid SHA-256."
      }
    }
    return commitments
  }

  fun canonicalSha256(commitments: List<RustyKioskInstallPartCommitment>): String {
    val ordered = validate(commitments)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(CANONICAL_PREFIX)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(ordered.size).array())
    ordered.forEach { commitment ->
      val name = commitment.name.toByteArray(StandardCharsets.UTF_8)
      digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(name.size).array())
      digest.update(name)
      digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(commitment.bytes).array())
      digest.update(commitment.sha256.toByteArray(StandardCharsets.US_ASCII))
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
  }

  fun toJson(commitments: List<RustyKioskInstallPartCommitment>): JSONArray =
    JSONArray().also { array ->
      validate(commitments).forEach { commitment ->
        array.put(
          JSONObject()
            .put("name", commitment.name)
            .put("bytes", commitment.bytes)
            .put("sha256", commitment.sha256)
        )
      }
    }

  fun parse(array: JSONArray): List<RustyKioskInstallPartCommitment> =
    validate(
      (0 until array.length()).map { index ->
        RustyKioskInstallPartCommitmentPolicy.parse(array.getJSONObject(index))
      }
    )

  fun matchesBoundManifest(
    storedCommitments: List<RustyKioskInstallPartCommitment>,
    storedSha256: String,
    incomingCommitments: List<RustyKioskInstallPartCommitment>,
  ): Boolean =
    runCatching {
      validate(storedCommitments)
      validate(incomingCommitments)
      RustyKioskInstallPartCommitmentPolicy.SHA256.matches(storedSha256) &&
        storedCommitments == incomingCommitments &&
        storedSha256 == canonicalSha256(storedCommitments) &&
        storedSha256 == canonicalSha256(incomingCommitments)
    }.getOrDefault(false)

  private val CANONICAL_PREFIX =
    "rusty.kiosk.install_commitments.v1\u0000".toByteArray(StandardCharsets.US_ASCII)
  private val SAFE_APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._ ()+@-]{0,159}")
}
