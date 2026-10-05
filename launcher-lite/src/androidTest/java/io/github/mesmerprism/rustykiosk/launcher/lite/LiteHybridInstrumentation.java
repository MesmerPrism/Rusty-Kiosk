package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

/** Test-APK-only semantic host transition check; no production command component or coordinates. */
final class LiteHybridInstrumentation {
  private static final String WINDOW = "io.github.mesmerprism.rustykiosk.launcher.lite.RustyLauncherLiteActivity";
  private static final String SPATIAL = "io.github.mesmerprism.rustykiosk.launcher.lite.RustyLauncherLiteSpatialActivity";

  static void run(Instrumentation test, Bundle result) throws Exception {
    PackageManager packages = test.getTargetContext().getPackageManager();
    PackageInfo target = packages.getPackageInfo(test.getTargetContext().getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    PackageInfo testPackage = packages.getPackageInfo(test.getContext().getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    if ((target.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      throw new AssertionError("Presentation qualification requires the non-debuggable release target");
    }
    Signature[] targetSigners = target.signingInfo == null ? null : target.signingInfo.getApkContentsSigners();
    Signature[] testSigners = testPackage.signingInfo == null ? null : testPackage.signingInfo.getApkContentsSigners();
    if (targetSigners == null || testSigners == null || targetSigners.length != 1 || testSigners.length != 1
        || !targetSigners[0].equals(testSigners[0])) {
      throw new AssertionError("Release target and instrumentation APK must have the same sole current signer");
    }
    SharedPreferences preferences = test.getTargetContext().getSharedPreferences("launcher-lite-state", 0);
    Map<String, Object> beforeActivity = snapshot(preferences);
    Activity window = test.startActivitySync(new Intent().setClassName(test.getTargetContext(), WINDOW)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    View windowRoot = waitPanel(test, window, false);
    Map<String, Object> before = snapshot(preferences);
    int initializedKeys = assertInitialPreservation(test, beforeActivity, before);
    assertSearch(test, windowRoot, preferences.getString("search", ""));
    Instrumentation.ActivityMonitor spatialMonitor = test.addMonitor(SPATIAL, null, false);
    int outgoingWindowTask = window.getTaskId();
    Activity spatial;
    try {
      click(test, windowRoot, R.id.presentation_button);
      spatial = test.waitForMonitorWithTimeout(spatialMonitor, 15000);
      if (spatial == null) throw new AssertionError("Immersive host was not created");
    } finally { test.removeMonitor(spatialMonitor); }
    View spatialRoot = waitPanel(test, spatial, true);
    assertExclusiveTransition(test, window, outgoingWindowTask, spatial);
    assertOverlayFocusHandler(test, spatial, spatialRoot);
    result.putString("overlay_focus_and_pause_preserve_editor_stop_cleans_editor", "pass");
    assertSearch(test, spatialRoot, preferences.getString("search", ""));
    if (!before.equals(snapshot(preferences))) throw new AssertionError("Domain preferences changed during immersive transition");

    Instrumentation.ActivityMonitor windowMonitor = test.addMonitor(WINDOW, null, false);
    int outgoingSpatialTask = spatial.getTaskId();
    Activity returned;
    try {
      click(test, spatialRoot, R.id.presentation_button);
      returned = test.waitForMonitorWithTimeout(windowMonitor, 15000);
      if (returned == null) throw new AssertionError("Home did not launch the window PendingIntent");
    } finally { test.removeMonitor(windowMonitor); }
    View returnedRoot = waitPanel(test, returned, false);
    assertExclusiveTransition(test, spatial, outgoingSpatialTask, returned);
    assertSearch(test, returnedRoot, preferences.getString("search", ""));
    if (!before.equals(snapshot(preferences))) throw new AssertionError("Domain preferences changed during window return");
    result.putString("pre_activity_existing_preferences_preserved", "pass");
    result.putInt("pre_activity_preference_key_count", beforeActivity.size());
    result.putInt("allowed_initialized_key_count", initializedKeys);
    result.putInt("transition_preference_key_count", before.size());
    result.putString("transition_preferences_exactly_equal", "pass");
    result.putString("preference_evidence", "read-only-snapshot-before-first-activity-no-key-or-value-disclosure");
    result.putString("target_package", target.packageName);
    result.putString("target_version_name", target.versionName);
    result.putLong("target_version_code", target.getLongVersionCode());
    result.putBoolean("target_debuggable", false);
    result.putBoolean("target_test_same_current_signer", true);
    result.putString("window_to_immersive", "pass");
    result.putString("registered_native_panel_and_scene_entity", "pass");
    result.putString("immersive_to_home_window", "pass");
    result.putString("persisted_domain_state", "pass");
    result.putString("distinct_tasks_outgoing_destroyed_incoming_survives", "pass");
    result.putString("scope", "test-only-semantic-handlers-not-physical-pointer-keyboard-or-compositor-acceptance");
  }

  private static void assertOverlayFocusHandler(Instrumentation test, Activity spatial, View root) {
    Throwable[] failure = new Throwable[1];
    test.runOnMainSync(() -> {
      try {
        Field controllerField = spatial.getClass().getDeclaredField("panel");
        controllerField.setAccessible(true);
        LitePanelController controller = (LitePanelController) controllerField.get(spatial);
        if (controller == null) throw new AssertionError("Immersive controller is absent");
        EditText editor = root.findViewById(R.id.search);
        if (!editor.requestFocus()) throw new AssertionError("Native editor could not receive focus");
        // Exercise the shared production focus-loss handler without SDK lifecycle injection.
        // No provider request, launch, text edit or domain preference mutation is synthesized.
        controller.onFocusLost("instrumentation-overlay-focus");
        if (!editor.hasFocus()) throw new AssertionError("Overlay focus loss cleared its native editor");
        // A paused host can remain visible below the IME. Pause revokes dispatch, not editor ownership.
        controller.onPause();
        if (!editor.hasFocus()) throw new AssertionError("Visible Activity pause cleared its native editor");
        Field resumed = LitePanelController.class.getDeclaredField("resumed");
        resumed.setAccessible(true);
        if (resumed.getBoolean(controller)) throw new AssertionError("Paused controller retained launch authority");
        controller.onStop();
        if (editor.hasFocus()) throw new AssertionError("Stopped Activity did not clear editor focus");
        controller.onResume();
      } catch (Throwable error) { failure[0] = error; }
    });
    if (failure[0] != null) throw new AssertionError("Native editor lifecycle handler check failed", failure[0]);
  }

  private static Map<String, Object> snapshot(SharedPreferences preferences) {
    Map<String, Object> result = new HashMap<>();
    for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
      Object value = entry.getValue();
      // String sets must not share live preference references with the baseline.
      result.put(entry.getKey(), value instanceof Set<?> ? new HashSet<>((Set<?>) value) : value);
    }
    return result;
  }

  private static int assertInitialPreservation(Instrumentation test, Map<String, Object> before,
      Map<String, Object> after) {
    for (Map.Entry<String, Object> entry : before.entrySet()) {
      if (!after.containsKey(entry.getKey()) || !Objects.equals(entry.getValue(), after.get(entry.getKey()))) {
        // Never include wearer search, tags, favorite components or Wi-Fi preferences in a failure receipt.
        throw new AssertionError("An existing preference changed during the first Activity initialization");
      }
    }
    int initialized = 0;
    for (Map.Entry<String, Object> entry : after.entrySet()) {
      if (before.containsKey(entry.getKey())) continue;
      String key = entry.getKey();
      Object value = entry.getValue();
      boolean allowed = ("record-keys".equals(key) && value instanceof Set<?> && ((Set<?>) value).isEmpty())
          || ("favorites-only".equals(key) && Boolean.FALSE.equals(value))
          || ("search".equals(key) && "".equals(value));
      if ("selected-component".equals(key) && value instanceof String) {
        for (LiteApp app : LiteCatalog.load(test.getTargetContext().getPackageManager(), test.getTargetContext().getPackageName())) {
          if (app.key().equals(value)) { allowed = true; break; }
        }
      }
      if (!allowed) throw new AssertionError("Unexpected preference initialization outside the default browsing allowlist");
      initialized++;
    }
    return initialized;
  }

  private static View waitPanel(Instrumentation test, Activity activity, boolean spatial) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 15000;
    while (SystemClock.elapsedRealtime() < deadline) {
      View[] found = new View[1];
      Throwable[] error = new Throwable[1];
      test.runOnMainSync(() -> {
        try {
          if (spatial) {
            Field root = activity.getClass().getDeclaredField("panelRoot");
            root.setAccessible(true);
            Field entity = activity.getClass().getDeclaredField("panelEntity");
            entity.setAccessible(true);
            if (entity.get(activity) != null) found[0] = (View) root.get(activity);
          } else found[0] = activity.findViewById(R.id.lite_panel_root);
          if (found[0] != null && (found[0].getWidth() <= 0 || !found[0].isAttachedToWindow())) found[0] = null;
        } catch (Throwable failure) { error[0] = failure; }
      });
      if (error[0] != null) throw new AssertionError("Panel inspection failed", error[0]);
      if (found[0] != null) return found[0];
      SystemClock.sleep(100);
    }
    throw new AssertionError("Native panel/scene did not become ready: " + activity.getClass().getSimpleName());
  }

  private static void assertExclusiveTransition(Instrumentation test, Activity outgoing, int outgoingTaskId, Activity incoming) {
    if (outgoingTaskId < 0 || incoming.getTaskId() < 0 || outgoingTaskId == incoming.getTaskId()) throw new AssertionError("Hybrid hosts share one task or lack a task");
    long deadline = SystemClock.elapsedRealtime() + 15000;
    boolean[] destroyed = new boolean[1];
    while (SystemClock.elapsedRealtime() < deadline) {
      test.runOnMainSync(() -> destroyed[0] = outgoing.isDestroyed());
      if (destroyed[0]) break;
      SystemClock.sleep(100);
    }
    if (!destroyed[0]) throw new AssertionError("Outgoing hybrid Activity was not destroyed");
    // A transient onCreate before finishAndRemoveTask takes effect is insufficient.
    SystemClock.sleep(1000);
    test.runOnMainSync(() -> {
      if (incoming.isDestroyed() || incoming.isFinishing()) throw new AssertionError("Incoming task did not survive outgoing removal");
      if (outgoingTaskId == incoming.getTaskId()) throw new AssertionError("Hybrid task isolation changed");
    });
  }

  private static void click(Instrumentation test, View root, int id) {
    test.runOnMainSync(() -> {
      View control = root.findViewById(id);
      if (control == null || !control.isEnabled() || !control.performClick()) throw new AssertionError("Semantic control unavailable");
    });
  }

  private static void assertSearch(Instrumentation test, View root, String expected) {
    test.runOnMainSync(() -> {
      EditText search = root.findViewById(R.id.search);
      if (search == null || !expected.equals(search.getText().toString())) throw new AssertionError("Search state was not restored");
    });
  }
}
