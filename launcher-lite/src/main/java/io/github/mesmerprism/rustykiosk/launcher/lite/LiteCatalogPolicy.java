package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

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
    return admit(unique, loadedLabel, packageName, activityName, category, activityEnabled,
        activityExported, applicationEnabled, ownPackage, false);
  }

  static boolean admit(
      Map<String, LiteApp> unique, String loadedLabel, String packageName, String activityName,
      String category, boolean activityEnabled, boolean activityExported,
      boolean applicationEnabled, String ownPackage, boolean systemApp) {
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
    LiteApp app = new LiteApp(label, packageName, activityName, category, systemApp, Set.of(category));
    LiteApp prior = unique.putIfAbsent(app.key(), app);
    if (prior == null) return true;
    Set<String> categories = new LinkedHashSet<>(prior.categories);
    categories.add(category);
    unique.put(app.key(), new LiteApp(prior.label, prior.packageName, prior.activityName,
        prior.category, prior.systemApp || systemApp, categories));
    return false;
  }
}
