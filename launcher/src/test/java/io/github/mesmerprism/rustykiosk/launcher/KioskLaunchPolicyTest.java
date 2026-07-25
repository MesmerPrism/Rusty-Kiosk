package io.github.mesmerprism.rustykiosk.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class KioskLaunchPolicyTest {
  @Test
  public void missingPackageStopsBeforeTrustAndFrontDoorChecks() {
    assertEquals(
        KioskLaunchPolicy.Decision.MISSING,
        KioskLaunchPolicy.decide(false, false, false));
  }

  @Test
  public void signerMismatchNeverLaunches() {
    assertEquals(
        KioskLaunchPolicy.Decision.SIGNER_MISMATCH,
        KioskLaunchPolicy.decide(true, false, true));
  }

  @Test
  public void trustedPackageStillNeedsAFrontDoor() {
    assertEquals(
        KioskLaunchPolicy.Decision.NO_FRONT_DOOR,
        KioskLaunchPolicy.decide(true, true, false));
  }

  @Test
  public void trustedInstalledPackageWithFrontDoorIsReady() {
    assertEquals(
        KioskLaunchPolicy.Decision.READY,
        KioskLaunchPolicy.decide(true, true, true));
  }
}
