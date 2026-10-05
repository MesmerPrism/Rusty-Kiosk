package io.github.mesmerprism.rustykiosk.launcher.lite;

enum WifiRequirement {
  ANY("any"),
  ON("wifi-on"),
  OFF("wifi-off");

  final String storedValue;

  WifiRequirement(String storedValue) {
    this.storedValue = storedValue;
  }

  boolean isSatisfied(boolean wifiEnabled) {
    return this == ANY || (this == ON && wifiEnabled) || (this == OFF && !wifiEnabled);
  }

  boolean isSatisfied(Boolean wifiEnabled) {
    return this == ANY || (wifiEnabled != null && isSatisfied(wifiEnabled.booleanValue()));
  }

  static WifiRequirement fromStored(String value) {
    for (WifiRequirement requirement : values()) {
      if (requirement.storedValue.equals(value)) {
        return requirement;
      }
    }
    return ANY;
  }
}
