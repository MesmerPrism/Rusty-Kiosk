package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.ArrayList;
import java.util.List;
import io.github.mesmerprism.rustykiosk.catalog.CatalogSearch;
import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class LiteApp {
  final String label;
  final String packageName;
  final String activityName;
  final String category;
  final boolean systemApp;
  final Set<String> categories;

  LiteApp(String label, String packageName, String activityName, String category) {
    this(label, packageName, activityName, category, false, Set.of(category));
  }

  LiteApp(String label, String packageName, String activityName, String category,
      boolean systemApp, Set<String> categories) {
    this.label = Objects.requireNonNull(label);
    this.packageName = Objects.requireNonNull(packageName);
    this.activityName = Objects.requireNonNull(activityName);
    this.category = Objects.requireNonNull(category);
    this.systemApp = systemApp;
    this.categories = Collections.unmodifiableSet(new LinkedHashSet<>(categories));
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
