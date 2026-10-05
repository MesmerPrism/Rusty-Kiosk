package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class RustyKioskInstallCommitmentTest {
  @Test
  fun strictPartCommitmentParsesExactNameSizeAndDigest() {
    val bytes = "committed-apk-bytes".toByteArray()
    val commitment = RustyKioskInstallPartCommitmentPolicy.parse(
      JSONObject()
        .put("name", "base.apk")
        .put("bytes", bytes.size)
        .put("sha256", sha256(bytes))
    )
    assertEquals("base.apk", commitment.name)
    assertEquals(bytes.size.toLong(), commitment.bytes)
    assertEquals(sha256(bytes), commitment.sha256)

    listOf(
      JSONObject().put("name", "base.apk").put("bytes", bytes.size).put("sha256", sha256(bytes))
        .put("path", "/sdcard/base.apk"),
      JSONObject().put("name", "base.apk").put("bytes", "19").put("sha256", sha256(bytes)),
      JSONObject().put("name", "base.apk").put("bytes", bytes.size).put("sha256", sha256(bytes).uppercase()),
    ).forEach { invalid ->
      assertThrows(IllegalArgumentException::class.java) {
        RustyKioskInstallPartCommitmentPolicy.parse(invalid)
      }
    }
  }

  @Test
  fun sameOpenedHandleCopyAcceptsOnlyCommittedBytes() {
    val bytes = ByteArray(32 * 1024) { index -> (index and 0xff).toByte() }
    val commitment = RustyKioskInstallPartCommitment("base.apk", bytes.size.toLong(), sha256(bytes))
    val output = ByteArrayOutputStream()
    assertEquals(
      bytes.size.toLong(),
      RustyKioskInstallPartCommitmentPolicy.copyVerified(
        ByteArrayInputStream(bytes),
        output,
        commitment,
      )
    )
    assertArrayEquals(bytes, output.toByteArray())
  }

  @Test
  fun crossSessionReplacementAndSizeChangesFailClosed() {
    val original = "original-session-apk".toByteArray()
    val replacement = "replacement-by-other-session".toByteArray()
    val commitment = RustyKioskInstallPartCommitment(
      "base.apk",
      original.size.toLong(),
      sha256(original),
    )
    assertThrows(IllegalArgumentException::class.java) {
      RustyKioskInstallPartCommitmentPolicy.copyVerified(
        ByteArrayInputStream(replacement),
        ByteArrayOutputStream(),
        commitment,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      RustyKioskInstallPartCommitmentPolicy.copyVerified(
        ByteArrayInputStream(original.copyOf(original.size - 1)),
        ByteArrayOutputStream(),
        commitment,
      )
    }
  }

  @Test
  fun canonicalManifestBindsOrderAndEveryCommittedField() {
    val base = RustyKioskInstallPartCommitment("base.apk", 12L, "01".repeat(32))
    val split = RustyKioskInstallPartCommitment("split_config.apk", 23L, "ab".repeat(32))
    val ordered = listOf(base, split)
    val digest = RustyKioskInstallCommitmentManifestPolicy.canonicalSha256(ordered)
    assertTrue(
      RustyKioskInstallCommitmentManifestPolicy.matchesBoundManifest(
        ordered,
        digest,
        ordered,
      )
    )
    listOf(
      ordered.reversed(),
      listOf(base.copy(bytes = base.bytes + 1L), split),
      listOf(base.copy(sha256 = "02".repeat(32)), split),
      listOf(base.copy(name = "other.apk"), split),
    ).forEach { changed ->
      assertFalse(
        RustyKioskInstallCommitmentManifestPolicy.matchesBoundManifest(
          ordered,
          digest,
          changed,
        )
      )
    }
    assertFalse(
      RustyKioskInstallCommitmentManifestPolicy.matchesBoundManifest(
        ordered,
        "ff".repeat(32),
        ordered,
      )
    )
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
