package io.github.mesmerprism.rustykiosk.launcher;

import java.util.Locale;

final class SignerTrustPolicy {
  private SignerTrustPolicy() {}

  static boolean matchesExpected(
      boolean hasMultipleCurrentSigners,
      byte[][] currentSignerCertificates,
      byte[][] signingCertificateHistory,
      String expectedSha256) {
    if (hasMultipleCurrentSigners
        || expectedSha256 == null
        || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
      return false;
    }
    byte[][] candidates =
        signingCertificateHistory != null
            ? signingCertificateHistory
            : currentSignerCertificates;
    if (candidates == null || candidates.length == 0) {
      return false;
    }
    String expected = expectedSha256.toLowerCase(Locale.ROOT);
    for (byte[] certificate : candidates) {
      if (certificate != null && expected.equals(SignerDigest.sha256(certificate))) {
        return true;
      }
    }
    return false;
  }
}
