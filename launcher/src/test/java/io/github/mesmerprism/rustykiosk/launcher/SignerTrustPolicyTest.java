package io.github.mesmerprism.rustykiosk.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.Test;

public final class SignerTrustPolicyTest {
  private static final byte[] OFFICIAL = "official-certificate".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ROTATED = "rotated-certificate".getBytes(StandardCharsets.UTF_8);
  private static final byte[] UNTRUSTED = "untrusted-certificate".getBytes(StandardCharsets.UTF_8);
  private static final String OFFICIAL_SHA256 = SignerDigest.sha256(OFFICIAL);

  @Test
  public void currentOfficialSignerIsAcceptedWhenHistoryIsUnavailable() {
    assertTrue(
        SignerTrustPolicy.matchesExpected(
            false, new byte[][] {OFFICIAL}, null, OFFICIAL_SHA256));
  }

  @Test
  public void officialSignerInValidatedRotationHistoryIsAccepted() {
    assertTrue(
        SignerTrustPolicy.matchesExpected(
            false,
            new byte[][] {ROTATED},
            new byte[][] {OFFICIAL, ROTATED},
            OFFICIAL_SHA256.toUpperCase(Locale.ROOT)));
  }

  @Test
  public void untrustedSignerIsRejected() {
    assertFalse(
        SignerTrustPolicy.matchesExpected(
            false, new byte[][] {UNTRUSTED}, null, OFFICIAL_SHA256));
  }

  @Test
  public void multipleCurrentSignersFailClosed() {
    assertFalse(
        SignerTrustPolicy.matchesExpected(
            true,
            new byte[][] {OFFICIAL, UNTRUSTED},
            new byte[][] {OFFICIAL},
            OFFICIAL_SHA256));
  }

  @Test
  public void nullEmptyAndMalformedInputsFailClosed() {
    assertFalse(SignerTrustPolicy.matchesExpected(false, null, null, OFFICIAL_SHA256));
    assertFalse(
        SignerTrustPolicy.matchesExpected(false, new byte[0][], new byte[0][], OFFICIAL_SHA256));
    assertFalse(
        SignerTrustPolicy.matchesExpected(
            false, new byte[][] {null}, null, OFFICIAL_SHA256));
    assertFalse(
        SignerTrustPolicy.matchesExpected(
            false, new byte[][] {OFFICIAL}, null, "not-a-sha256"));
  }
}
