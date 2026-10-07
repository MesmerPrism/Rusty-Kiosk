package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LiteCatalog {
  private static final String[] CATEGORIES = {
    Intent.CATEGORY_LAUNCHER,
    "com.oculus.intent.category.2D",
    "com.oculus.intent.category.VR",
    Intent.CATEGORY_LEANBACK_LAUNCHER
  };

  private LiteCatalog() {}

  static List<LiteApp> load(PackageManager packageManager, String ownPackage) {
    Map<String, LiteApp> unique = new LinkedHashMap<>();
    for (String category : CATEGORIES) {
      Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
      for (ResolveInfo resolved : packageManager.queryIntentActivities(query, 0)) {
        ActivityInfo activity = resolved.activityInfo;
        if (activity == null || activity.applicationInfo == null) {
          continue;
        }
        CharSequence loadedLabel = resolved.loadLabel(packageManager);
        LiteCatalogPolicy.admit(
            unique,
            loadedLabel == null ? null : loadedLabel.toString(),
            activity.packageName,
            activity.name,
            category,
            activity.enabled,
            activity.exported,
            activity.applicationInfo.enabled,
            ownPackage,
            (activity.applicationInfo.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
      }
    }
    List<LiteApp> apps = new ArrayList<>(unique.values());
    apps.sort(
        Comparator.comparing((LiteApp app) -> app.label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(app -> app.packageName)
            .thenComparing(app -> app.activityName));
    return apps;
  }
}
