package io.github.mesmerprism.rustykiosk.launcher.lite;

final class LiteLaunchBinding {
  private static final long MAX_PENDING_MILLISECONDS = 120_000L;
  final LiteApp app;
  final LiteInstalledIdentity installedIdentity;
  final WifiRequirement requirement;
  final long expiresAtElapsedRealtime;

  LiteLaunchBinding(
      LiteApp app,
      LiteInstalledIdentity installedIdentity,
      WifiRequirement requirement,
      long createdAtElapsedRealtime) {
    this.app = app;
    this.installedIdentity = installedIdentity;
    this.requirement = requirement;
    this.expiresAtElapsedRealtime = createdAtElapsedRealtime + MAX_PENDING_MILLISECONDS;
  }

  boolean accepts(
      LiteInstalledIdentity currentIdentity,
      WifiRequirement currentRequirement,
      long nowElapsedRealtime) {
    return nowElapsedRealtime <= expiresAtElapsedRealtime
        && installedIdentity.equals(currentIdentity)
        && requirement == currentRequirement;
  }
}
