package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.*;
import org.junit.Test;

public final class LiteSpatialPlacementTest {
  @Test public void panelUsesViewerYawAndDistance() {
    LiteSpatialPlacement p = LiteSpatialPlacement.resolve(2, 1.8f, 3, 4, 0);
    assertEquals(3.5f, p.x, 0.0001f);
    assertEquals(1.65f, p.y, 0.0001f);
    assertEquals(3f, p.z, 0.0001f);
    assertEquals(1f, p.forwardX, 0.0001f);
  }
  @Test public void invalidTrackingUsesUprightFiniteFallback() {
    LiteSpatialPlacement p = LiteSpatialPlacement.resolve(Float.NaN, 1, 1, Float.NaN, 0);
    assertEquals(0f, p.x, 0.0001f);
    assertEquals(1.45f, p.y, 0.0001f);
    assertEquals(-1.5f, p.z, 0.0001f);
  }
  @Test public void nearVerticalGazeFallsBackAndHeightIsBounded() {
    LiteSpatialPlacement p = LiteSpatialPlacement.resolve(0, 100, 0, 0, 0);
    assertEquals(2.2f, p.y, 0.0001f);
    assertEquals(-1f, p.forwardZ, 0.0001f);
  }
}
