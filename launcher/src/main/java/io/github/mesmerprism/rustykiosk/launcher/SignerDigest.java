package io.github.mesmerprism.rustykiosk.launcher;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class SignerDigest {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private SignerDigest() {}

  static String sha256(byte[] certificateBytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificateBytes);
      char[] hex = new char[digest.length * 2];
      for (int index = 0; index < digest.length; index++) {
        int value = digest[index] & 0xff;
        hex[index * 2] = HEX[value >>> 4];
        hex[index * 2 + 1] = HEX[value & 0x0f];
      }
      return new String(hex);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
