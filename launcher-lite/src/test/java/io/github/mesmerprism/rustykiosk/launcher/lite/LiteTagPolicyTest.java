package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class LiteTagPolicyTest {
  @Test
  public void normalizesBoundedLocalTags() {
    assertEquals("media-demo", LiteTagPolicy.normalize(" Media Demo "));
    assertEquals("360", LiteTagPolicy.normalize("360"));
  }

  @Test
  public void rejectsUnboundedOrCommandLikeValues() {
    assertNull(LiteTagPolicy.normalize(""));
    assertNull(LiteTagPolicy.normalize("contains/slash"));
    assertNull(LiteTagPolicy.normalize("this-tag-is-far-too-long-for-lite"));
  }
}
