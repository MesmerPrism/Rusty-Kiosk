package io.github.mesmerprism.rustykiosk.launcher.lite;

/** Presentation policy only; no launch, Wi-Fi or privileged control authority. */
interface LitePresentationHost {
  boolean isImmersive();
  String mode();
  void switchPresentation();
}
