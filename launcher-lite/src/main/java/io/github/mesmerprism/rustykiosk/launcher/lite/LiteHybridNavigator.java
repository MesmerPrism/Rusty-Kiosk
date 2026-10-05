package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** Meta's exclusive hybrid transitions. No domain action is passed to the next host. */
final class LiteHybridNavigator {
  private LiteHybridNavigator() {}

  static void launchImmersive(Activity activity) {
    activity.startActivity(new Intent(activity, RustyLauncherLiteSpatialActivity.class)
        .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    activity.finishAndRemoveTask();
  }

  static void launchWindow(Activity activity) {
    Intent window = new Intent(activity, RustyLauncherLiteActivity.class)
        .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent pending = PendingIntent.getActivity(activity, 0, window,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("extra_launch_in_home_pending_intent", pending);
    ResolveInfo resolved = activity.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
    ActivityInfo target = resolved == null ? null : resolved.activityInfo;
    if (target == null || !target.exported || !target.enabled || target.applicationInfo == null
        || !target.applicationInfo.enabled || target.packageName == null || target.name == null) {
      throw new ActivityNotFoundException("No enabled exported Meta Home activity");
    }
    home.setComponent(new ComponentName(target.packageName, target.name));
    activity.startActivity(home);
    activity.finishAndRemoveTask();
  }
}
