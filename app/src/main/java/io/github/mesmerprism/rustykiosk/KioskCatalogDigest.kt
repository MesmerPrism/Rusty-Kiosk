package io.github.mesmerprism.rustykiosk

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun catalogDigest(vararg parts: String): String {
  val sha = MessageDigest.getInstance("SHA-256")
  parts.forEach { part ->
    val bytes = part.toByteArray(StandardCharsets.UTF_8)
    sha.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
    sha.update(0)
    sha.update(bytes)
    sha.update(0)
  }
  return sha.digest().joinToString("") { byte -> "%02x".format(byte) }
}
