package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.ArrayList;
import java.util.List;
import io.github.mesmerprism.rustykiosk.catalog.CatalogSearch;
import java.util.Objects;

final class LiteApp {
  final String label;
  final String packageName;
  final String activityName;
  final String category;

  LiteApp(String label, String packageName, String activityName, String category) {
    this.label = Objects.requireNonNull(label);
    this.packageName = Objects.requireNonNull(packageName);
    this.activityName = Objects.requireNonNull(activityName);
    this.category = Objects.requireNonNull(category);
  }

  String key() {
    return packageName + "/" + activityName;
  }

  boolean matches(String query, Iterable<String> tags) {
    List<String> fields = new ArrayList<>();
    fields.add(label);
    fields.add(packageName);
    for (String tag : tags) fields.add(tag);
    return CatalogSearch.matches(query, fields);
  }
}
