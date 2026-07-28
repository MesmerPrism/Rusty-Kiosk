package io.github.mesmerprism.rustykiosk.launcher;

final class KioskLaunchPolicy {
  enum Decision {
    MISSING,
    SIGNER_MISMATCH,
    NO_FRONT_DOOR,
    READY
  }

  private KioskLaunchPolicy() {}

  static Decision decide(boolean installed, boolean signerTrusted, boolean hasFrontDoor) {
    if (!installed) {
      return Decision.MISSING;
    }
    if (!signerTrusted) {
      return Decision.SIGNER_MISMATCH;
    }
    if (!hasFrontDoor) {
      return Decision.NO_FRONT_DOOR;
    }
    return Decision.READY;
  }
}
