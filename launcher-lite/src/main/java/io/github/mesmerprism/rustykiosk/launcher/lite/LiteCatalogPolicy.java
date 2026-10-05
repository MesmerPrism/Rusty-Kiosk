package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.Map;

final class LiteCatalogPolicy {
  private LiteCatalogPolicy() {}

  static boolean admit(
      Map<String, LiteApp> unique,
      String loadedLabel,
      String packageName,
      String activityName,
      String category,
      boolean activityEnabled,
      boolean activityExported,
      boolean applicationEnabled,
      String ownPackage) {
    if (!activityEnabled
        || !activityExported
        || !applicationEnabled
        || ownPackage.equals(packageName)
        || packageName == null
        || activityName == null) {
      return false;
    }
    String label = loadedLabel == null ? "" : loadedLabel.trim();
    if (label.isEmpty()) {
      label = packageName;
    }
    LiteApp app = new LiteApp(label, packageName, activityName, category);
    return unique.putIfAbsent(app.key(), app) == null;
  }
}
