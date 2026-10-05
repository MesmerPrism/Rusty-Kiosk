package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WifiRequirementTest {
  @Test
  public void anyAcceptsEveryKnownOrUnavailableState() {
    assertTrue(WifiRequirement.ANY.isSatisfied(true));
    assertTrue(WifiRequirement.ANY.isSatisfied(false));
    assertTrue(WifiRequirement.ANY.isSatisfied((Boolean) null));
  }

  @Test
  public void onAndOffRequireMatchingKnownState() {
    assertTrue(WifiRequirement.ON.isSatisfied(true));
    assertFalse(WifiRequirement.ON.isSatisfied(false));
    assertFalse(WifiRequirement.ON.isSatisfied((Boolean) null));
    assertTrue(WifiRequirement.OFF.isSatisfied(false));
    assertFalse(WifiRequirement.OFF.isSatisfied(true));
    assertFalse(WifiRequirement.OFF.isSatisfied((Boolean) null));
  }

  @Test
  public void malformedStoredValueFailsToUnrestrictedAny() {
    assertSame(WifiRequirement.ANY, WifiRequirement.fromStored("unknown"));
    assertSame(WifiRequirement.ON, WifiRequirement.fromStored("wifi-on"));
    assertSame(WifiRequirement.OFF, WifiRequirement.fromStored("wifi-off"));
  }
}
