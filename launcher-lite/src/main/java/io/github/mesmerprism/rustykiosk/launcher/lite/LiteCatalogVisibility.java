package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Catalogue presentation only; hidden entries remain admitted launchables and retain preferences. */
final class LiteCatalogVisibility {
  enum Kind { APP, SYSTEM, INTERNAL }

  // Observed public front doors, never a namespace-wide exemption. New entries require review.
  private static final Set<String> PUBLIC_META_COMPONENTS = Set.of(
      "com.oculus.browser/com.oculus.browser.OculusLauncherActivity",
      "com.oculus.store/com.oculus.store.StoreActivity",
      "com.oculus.tv/com.oculus.tv.LivingRoomActivity",
      "com.oculus.systemutilities/com.oculus.systemutilities.FileManagerActivity",
      "com.oculus.hzosgallery/com.oculus.hzosgallery.HzOSGalleryActivity",
      "com.oculus.remotedesktop/com.oculus.remotedesktop.activity.main.RemoteDesktopMainActivity",
      "com.oculus.avatareditor/com.oculus.avatareditor.AvatarEditorActivity",
      "com.oculus.horizonmediaplayer/com.oculus.horizonmediaplayer.HorizonMediaPlayerActivity",
      "com.oculus.metacam/com.oculus.panelapp.sharing.SharingPanelActivity");
  // Quest shell/dialog/onboarding packages sometimes advertise LAUNCHER and 2D themselves.
  private static final Set<String> INTERNAL_META_PACKAGES = Set.of(
      "com.oculus.vrshell", "com.oculus.systemux", "com.oculus.panelapp.library",
      "com.oculus.panelapp.kiosk", "com.meta.surfacetypingnux", "com.oculus.firsttimenux",
      "com.oculus.guardiansetup", "com.oculus.guidebook", "com.oculus.os.chargecontrol",
      "com.oculus.os.clearactivity", "com.oculus.os.qrcodereader", "com.oculus.os.voidactivity",
      "com.oculus.identitymanagement.service", "com.oculus.ovrmonitormetricsservice",
      "com.meta.pclinkservice.server", "com.oculus.extrapermissions");
  // These settings utilities may be distributed without Android's system-app flag.
  private static final Set<String> SYSTEM_UTILITY_PACKAGES = Set.of(
      "com.oculus.accountscenter", "com.oculus.vrprivacycheckup");
  private static final Set<String> INTERNAL_META_COMPONENTS = Set.of(
      "com.oculus.socialplatform/com.oculus.panelapp.people.PeopleShelfActivity",
      "com.oculus.socialplatform/com.oculus.btCompanion.MainActivity");

  private final Set<String> packagesWithFrontDoor = new HashSet<>();

  LiteCatalogVisibility(List<LiteApp> catalogue) {
    for (LiteApp app : catalogue) {
      if (hasPublicCategory(app)) packagesWithFrontDoor.add(app.packageName);
    }
  }

  Kind kind(LiteApp app) {
    if (PUBLIC_META_COMPONENTS.contains(app.key()) && hasPublicCategory(app)) return Kind.APP;
    if (SYSTEM_UTILITY_PACKAGES.contains(app.packageName)) return Kind.SYSTEM;
    if (INTERNAL_META_COMPONENTS.contains(app.key())) return Kind.INTERNAL;
    if (app.packageName.equals("com.oculus.xrstreamingclient")) {
      return app.activityName.equals("com.oculus.xrstreamingclient.MainActivity")
          ? Kind.SYSTEM : Kind.INTERNAL;
    }
    if (INTERNAL_META_PACKAGES.contains(app.packageName) || isPublicMetaPackage(app.packageName)) {
      return Kind.INTERNAL;
    }
    if (app.packageName.equals("com.oculus.panelapp.settings")) {
      return app.activityName.equals("com.oculus.panelapp.settings.SettingsActivity")
          ? Kind.SYSTEM : Kind.INTERNAL;
    }
    // A VR-only sideloaded app is often its package's only intended entry point.
    // Hide auxiliary VR components only when there is another public front door,
    // or Android marks the package as a system application.
    if (!hasPublicCategory(app)
        && (app.systemApp || packagesWithFrontDoor.contains(app.packageName))) return Kind.INTERNAL;
    if (app.systemApp) return Kind.SYSTEM;
    return Kind.APP;
  }

  private static boolean isPublicMetaPackage(String packageName) {
    for (String component : PUBLIC_META_COMPONENTS) {
      if (component.startsWith(packageName + "/")) return true;
    }
    return false;
  }

  boolean visible(LiteApp app, boolean showSystemApps, boolean showInternalActivities) {
    Kind kind = kind(app);
    return kind == Kind.APP || (kind == Kind.SYSTEM && showSystemApps)
        || (kind == Kind.INTERNAL && showInternalActivities);
  }

  private static boolean hasPublicCategory(LiteApp app) {
    return app.categories.contains("android.intent.category.LAUNCHER")
        || app.categories.contains("android.intent.category.LEANBACK_LAUNCHER")
        || app.categories.contains("com.oculus.intent.category.2D");
  }
}
