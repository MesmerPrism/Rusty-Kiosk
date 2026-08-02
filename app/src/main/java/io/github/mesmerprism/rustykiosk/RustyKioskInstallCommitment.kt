package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
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
