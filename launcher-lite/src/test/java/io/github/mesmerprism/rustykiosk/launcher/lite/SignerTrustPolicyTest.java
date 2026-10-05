package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class SignerTrustPolicyTest {
  private static final byte[] OFFICIAL = "official".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER = "other".getBytes(StandardCharsets.UTF_8);

  @Test
  public void acceptsOnlyPinnedSignerOrValidatedHistory() {
    String expected = SignerDigest.sha256(OFFICIAL);
    assertTrue(SignerTrustPolicy.matchesExpected(false, new byte[][] {OFFICIAL}, null, expected));
    assertTrue(
        SignerTrustPolicy.matchesExpected(
            false, new byte[][] {OTHER}, new byte[][] {OFFICIAL, OTHER}, expected));
    assertFalse(SignerTrustPolicy.matchesExpected(false, new byte[][] {OTHER}, null, expected));
    assertFalse(
        SignerTrustPolicy.matchesExpected(
            true, new byte[][] {OFFICIAL, OTHER}, new byte[][] {OFFICIAL}, expected));
  }
}
