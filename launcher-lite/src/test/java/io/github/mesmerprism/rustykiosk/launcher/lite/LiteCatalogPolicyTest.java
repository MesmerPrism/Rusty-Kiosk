package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class LiteCatalogPolicyTest {
  @Test
  public void rejectsSelfDisabledAndUnexportedActivities() {
    Map<String, LiteApp> apps = new LinkedHashMap<>();
    assertFalse(admit(apps, "lite", true, true, true, "lite"));
    assertFalse(admit(apps, "other", false, true, true, "lite"));
    assertFalse(admit(apps, "other", true, false, true, "lite"));
    assertFalse(admit(apps, "other", true, true, false, "lite"));
    assertTrue(apps.isEmpty());
  }

  @Test
  public void admitsAndDeduplicatesExactComponentsInQueryOrder() {
    Map<String, LiteApp> apps = new LinkedHashMap<>();
    assertTrue(admit(apps, "other", true, true, true, "lite"));
    assertFalse(admit(apps, "other", true, true, true, "lite"));
    assertEquals(1, apps.size());
    assertEquals("launcher", apps.values().iterator().next().category);
  }

  private static boolean admit(
      Map<String, LiteApp> apps,
      String packageName,
      boolean activityEnabled,
      boolean exported,
      boolean applicationEnabled,
      String ownPackage) {
    return LiteCatalogPolicy.admit(
        apps,
        "Demo",
        packageName,
        packageName + ".Main",
        "launcher",
        activityEnabled,
        exported,
        applicationEnabled,
        ownPackage);
  }
}
