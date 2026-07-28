package io.github.mesmerprism.rustykiosk.launcher;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class SignerDigestTest {
  @Test
  public void emitsLowercaseSha256() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        SignerDigest.sha256("abc".getBytes(StandardCharsets.UTF_8)));
  }
}
