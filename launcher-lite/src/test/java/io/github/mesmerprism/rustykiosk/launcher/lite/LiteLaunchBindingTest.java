package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LiteLaunchBindingTest {
  private final LiteApp app = new LiteApp("Demo", "example.demo", "example.Main", "launcher");
  private final LiteInstalledIdentity installed =
      new LiteInstalledIdentity("example.demo", "example.Main", "launcher", 4, 1000, 12000, "abc");

  @Test
  public void acceptsOnlyExactIdentityRequirementAndDeadline() {
    LiteLaunchBinding binding = new LiteLaunchBinding(app, installed, WifiRequirement.ON, 5_000);
    assertTrue(binding.accepts(installed, WifiRequirement.ON, 125_000));
    assertFalse(binding.accepts(installed, WifiRequirement.OFF, 125_000));
    assertFalse(binding.accepts(installed, WifiRequirement.ON, 125_001));
    assertFalse(
        binding.accepts(
            new LiteInstalledIdentity(
                "example.demo", "example.Main", "launcher", 5, 1001, 12000, "abc"),
            WifiRequirement.ON,
            6_000));
  }
}
