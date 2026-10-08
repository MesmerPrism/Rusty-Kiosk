package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Instrumentation-only Store asset capture. No test component is packaged in the release APK. */
public final class LiteStoreAssetInstrumentation extends Instrumentation {
  private boolean presentationOnly;
  private boolean distinctScenes;
  private String assetSearch = "Browser";
  private String publicAssetComponent;
  private static final String PUBLIC_ASSET_PACKAGE = "com.oculus.browser";

  private static final int STORE_WIDTH = 2560;
  private static final int STORE_HEIGHT = 1440;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    presentationOnly = arguments != null && "true".equals(arguments.getString("presentation_only"));
    String scenes = arguments == null ? null : arguments.getString("asset_scenes");
    if (scenes != null && !"distinct-v2".equals(scenes)) {
      throw new IllegalArgumentException("Unknown Store asset scene set");
    }
    distinctScenes = scenes != null;
    if (distinctScenes && presentationOnly) throw new IllegalArgumentException("Scene capture is not presentation validation");
    if (arguments != null && arguments.getString("asset_search") != null) assetSearch = arguments.getString("asset_search");
    start();
  }

  @Override
  public void onStart() {
    if (presentationOnly) {
      Bundle hybrid = new Bundle();
      try {
        LiteHybridInstrumentation.run(this, hybrid);
        hybrid.putString("result", "pass");
        finish(Activity.RESULT_OK, hybrid);
      } catch (Throwable error) {
        hybrid.putString("result", "fail");
        hybrid.putString("error", error.toString());
        finish(Activity.RESULT_CANCELED, hybrid);
      }
      return;
    }
    Bundle result = new Bundle();
    SharedPreferences preferences = getTargetContext().getSharedPreferences("launcher-lite-state", 0);
    Map<String, Object> original = snapshot(preferences);
    Activity activityForCleanup = null;
    boolean passed = false;
    File output = null;
    PackageInfo target = null;
    Signature signer = null;
    try {
      if (assetSearch == null || assetSearch.isBlank() || assetSearch.length() > 80) {
        throw new IllegalArgumentException("asset_search must be a bounded public Browser filter");
      }
      LiteApp publicApp = null;
      for (LiteApp candidate : LiteCatalog.load(getTargetContext().getPackageManager(), getTargetContext().getPackageName())) {
        if (PUBLIC_ASSET_PACKAGE.equals(candidate.packageName) && candidate.matches(assetSearch, Collections.emptySet())) {
          publicApp = candidate;
          break;
        }
      }
      if (publicApp == null) throw new IllegalStateException("No installed public Browser front door matches asset_search");
      publicAssetComponent = publicApp.key();
      // Artificial screenshot favorites/tags/search/Wi-Fi preferences must not persist on the wearer device.
      // This restores only the test fixture file, not any headset or system-settings baseline.
      if (!preferences.edit().clear().putString("search", assetSearch)
          .putBoolean("favorites-only", false).putString("selected-component", publicApp.key()).commit()) {
        throw new IllegalStateException("Could not initialize the public asset scene");
      }
      Intent intent =
          new Intent()
              .setClassName(
                  getTargetContext(),
                  "io.github.mesmerprism.rustykiosk.launcher.lite.RustyLauncherLiteActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      Activity activity = startActivitySync(intent);
      activityForCleanup = activity;
      waitForIdleSync();
      SystemClock.sleep(500);

      target =
          getTargetContext()
              .getPackageManager()
              .getPackageInfo(
                  getTargetContext().getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
      if ((target.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
        throw new IllegalStateException("Store assets must be captured from the non-debuggable APK");
      }
      Signature[] signers = target.signingInfo.getApkContentsSigners();
      if (signers.length != 1) {
        throw new IllegalStateException("Store target must have exactly one current signer");
      }

      output = new File(getTargetContext().getExternalFilesDir(null), distinctScenes ? "store-assets-distinct-v2" : "store-assets");
      if (distinctScenes && output.exists()) throw new IOException("Distinct capture namespace already exists");
      if (!output.exists() && !output.mkdirs()) {
        throw new IOException("Unable to create " + output);
      }
      signer = signers[0];
      deletePngs(output);

      capture(activity, output, "screenshot-01-catalog-home.png");

      runOnMain(
          activity,
          () -> {
            EditText tag = activity.findViewById(R.id.tag_editor);
            tag.setText("spatial");
            activity.findViewById(R.id.add_tag_button).performClick();
            EditText search = activity.findViewById(R.id.search);
            search.setText(assetSearch + " spatial");
            search.setSelection(search.length());
          });
      assertOnMain(
          activity,
          () -> {
            TextView status = activity.findViewById(R.id.status);
            ListView list = activity.findViewById(R.id.app_list);
            if (!status.getText().toString().startsWith("Showing ") || list.getCount() < 1) {
              throw new AssertionError("Tag-backed search did not return a launchable app");
            }
          });
      capture(activity, output, "screenshot-02-search-tags.png");

      runOnMain(
          activity,
          () -> {
            EditText search = activity.findViewById(R.id.search);
            search.setText(assetSearch);
            activity.findViewById(R.id.favorite_selected_button).performClick();
          });
      assertOnMain(
          activity,
          () -> {
            Button favorite = activity.findViewById(R.id.favorite_selected_button);
            if (!favorite.getText().toString().equals("Remove favorite")) {
              throw new AssertionError("Favorite state did not persist in the visible details");
            }
          });
      if (!distinctScenes) capture(activity, output, "screenshot-03-app-details.png");

      String[] wifiGuidance = new String[1];
      runOnMain(
          activity,
          () -> {
            WifiManager wifi = getTargetContext().getSystemService(WifiManager.class);
            boolean enabled = wifi != null && wifi.isWifiEnabled();
            RadioGroup group = activity.findViewById(R.id.wifi_group);
            group.check(enabled ? R.id.wifi_off : R.id.wifi_on);
            activity.findViewById(R.id.launch_button).performClick();
            wifiGuidance[0] = ((TextView) activity.findViewById(R.id.status)).getText().toString();
          });
      waitForIdleSync();
      if (distinctScenes) {
        captureDialog(activity, output, "screenshot-03-wifi-dialog.png", wifiGuidance[0]);
      }
      sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
      sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
      assertOnMain(
          activity,
          () -> {
            TextView status = activity.findViewById(R.id.status);
            if (!status.getText().toString().startsWith("Turn Wi-Fi ")) {
              throw new AssertionError("Wi-Fi mismatch did not fail into guidance");
            }
          });
      capture(activity, output, "screenshot-04-wifi-preflight.png");

      runOnMain(activity, () -> activity.findViewById(R.id.favorites_button).performClick());
      assertOnMain(
          activity,
          () -> {
            ListView list = activity.findViewById(R.id.app_list);
            if (list.getCount() != 1) {
              throw new AssertionError("Favorites view did not isolate the saved app");
            }
          });
      if (distinctScenes) {
        runOnMain(activity, () -> activity.findViewById(R.id.help_button).performClick());
        captureDialog(activity, output, "screenshot-05-help-about.png", activity.getString(R.string.about_body));
      } else {
        capture(activity, output, "screenshot-05-favorites.png");
      }

      result.putString("catalog_flow", "pass");
      result.putString("search_tag_flow", "pass");
      result.putString("favorite_flow", "pass");
      result.putString("wifi_preflight_flow", "pass");
      // These are five production-Activity assets, not proof of another app's foreground adoption.
      result.putString("launch_flow", "not-run-assets-only");

      result.putString("asset_directory", output.getAbsolutePath());
      result.putInt("asset_count", 5);
      passed = true;
    } catch (Throwable failure) {
      result.putString("error", failure.toString());
    } finally {
      try {
        fenceActivity(activityForCleanup);
      } catch (Throwable cleanupFailure) {
        passed = false;
        result.putString("cleanup_error", cleanupFailure.toString());
      } finally {
        try {
          restore(preferences, original);
          if (!original.equals(snapshot(preferences))) throw new IllegalStateException("Preference restoration did not match the exact snapshot");
          result.putString("preferences_restored", "exact");
          result.putInt("restored_preference_key_count", original.size());
        } catch (Throwable restoreFailure) {
          passed = false;
          result.putString("restoration_error", restoreFailure.toString());
        }
      }
    }
    if (passed) {
      try {
        File receipt = writeCaptureReceipt(output, target, signer);
        result.putString("capture_receipt", receipt.getAbsolutePath());
      } catch (Throwable receiptFailure) {
        passed = false;
        result.putString("error", receiptFailure.toString());
      }
    }
    result.putString("result", passed ? "pass" : "fail");
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private void fenceActivity(Activity activity) throws Exception {
    if (activity == null) return;
    Throwable[] failure = new Throwable[1];
    runOnMainSync(() -> {
      try {
        // The controller fence cancels worker callbacks and pending launches before restoring wearer data.
        Field field = activity.getClass().getDeclaredField("panel");
        field.setAccessible(true);
        Object controller = field.get(activity);
        if (controller != null) {
          Method release = controller.getClass().getDeclaredMethod("release");
          release.setAccessible(true);
          release.invoke(controller);
        }
        activity.finishAndRemoveTask();
      } catch (Throwable error) { failure[0] = error; }
    });
    if (failure[0] != null) throw new IllegalStateException("Could not fence the asset Activity", failure[0]);
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (SystemClock.elapsedRealtime() < deadline) {
      boolean[] destroyed = new boolean[1];
      runOnMainSync(() -> destroyed[0] = activity.isDestroyed());
      if (destroyed[0]) { waitForIdleSync(); return; }
      SystemClock.sleep(100);
    }
    throw new IllegalStateException("Asset Activity did not terminate before restoration");
  }

  private static Map<String, Object> snapshot(SharedPreferences preferences) {
    Map<String, Object> result = new HashMap<>();
    for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
      Object value = entry.getValue();
      if (!(value instanceof String || value instanceof Boolean || value instanceof Integer
          || value instanceof Long || value instanceof Float || value instanceof Set<?>)) {
        throw new IllegalStateException("Unsupported preference type");
      }
      if (value instanceof Set<?>) {
        for (Object member : (Set<?>) value) if (!(member instanceof String)) throw new IllegalStateException("Invalid preference set");
        value = new HashSet<>((Set<?>) value);
      }
      result.put(entry.getKey(), value);
    }
    return result;
  }

  private static void restore(SharedPreferences preferences, Map<String, Object> original) {
    SharedPreferences.Editor editor = preferences.edit().clear();
    for (Map.Entry<String, Object> entry : original.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value instanceof String) editor.putString(key, (String) value);
      else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
      else if (value instanceof Integer) editor.putInt(key, (Integer) value);
      else if (value instanceof Long) editor.putLong(key, (Long) value);
      else if (value instanceof Float) editor.putFloat(key, (Float) value);
      else if (value instanceof Set<?>) {
        Set<String> strings = new HashSet<>();
        for (Object member : (Set<?>) value) strings.add((String) member);
        editor.putStringSet(key, strings);
      } else throw new IllegalStateException("Unsupported snapshot type");
    }
    if (!editor.commit()) throw new IllegalStateException("Could not persist exact preference restoration");
  }

  private static void deletePngs(File output) {
    File[] files = output.listFiles((directory, name) -> name.endsWith(".png"));
    if (files == null) {
      return;
    }
    for (File file : files) {
      if (!file.delete()) {
        throw new IllegalStateException("Unable to replace " + file);
      }
    }
  }

  private File writeCaptureReceipt(File output, PackageInfo target, Signature signer)
      throws Exception {
    JSONArray screenshots = new JSONArray();
    String[] names = {
      "screenshot-01-catalog-home.png",
      "screenshot-02-search-tags.png",
      distinctScenes ? "screenshot-03-wifi-dialog.png" : "screenshot-03-app-details.png",
      "screenshot-04-wifi-preflight.png",
      distinctScenes ? "screenshot-05-help-about.png" : "screenshot-05-favorites.png"
    };
    for (String name : names) {
      File file = new File(output, name);
      screenshots.put(
          new JSONObject()
              .put("name", name)
              .put("bytes", file.length())
              .put("sha256", sha256File(file)));
    }
    File targetApk = new File(target.applicationInfo.sourceDir);
    JSONObject receipt =
        new JSONObject()
            .put("schema", distinctScenes ? "rusty.kiosk.launcher_lite.store_capture.v2" : "rusty.kiosk.launcher_lite.store_capture.v1")
            .put("result", "pass")
            .put("preferences_restored", "exact-after-controller-fence")
            .put("public_asset_package", PUBLIC_ASSET_PACKAGE)
            .put(
                "target",
                new JSONObject()
                    .put("package", target.packageName)
                    .put("version_name", target.versionName)
                    .put("version_code", target.getLongVersionCode())
                    .put("debuggable", false)
                    .put("apk_bytes", targetApk.length())
                    .put("apk_sha256", sha256File(targetApk))
                    .put("signer_sha256", sha256Bytes(signer.toByteArray())))
            .put(
                "capture",
                new JSONObject()
                    .put("width", STORE_WIDTH)
                    .put("height", STORE_HEIGHT)
                    .put("source", distinctScenes ? "production-activity-and-dialog-window-decor" : "production-activity-decor-view")
                    .put("transform", "aspect-fit-neutral-matte-no-overlay"))
            .put(
                "flows",
                new JSONObject()
                    .put("catalog", "pass")
                    .put("search_tag", "pass")
                    .put("favorite", "pass")
                    .put("wifi_preflight", "pass")
                    .put("launch", "not-run-assets-only"))
            .put("screenshots", screenshots);
    File receiptFile = new File(output, "capture-receipt.json");
    try (FileOutputStream stream = new FileOutputStream(receiptFile)) {
      stream.write(receipt.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    return receiptFile;
  }

  private static String sha256File(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (FileInputStream stream = new FileInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = stream.read(buffer)) >= 0) {
        if (read > 0) {
          digest.update(buffer, 0, read);
        }
      }
    }
    return hex(digest.digest());
  }

  private static String sha256Bytes(byte[] value) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static String hex(byte[] value) {
    StringBuilder output = new StringBuilder(value.length * 2);
    for (byte item : value) {
      output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
    }
    return output.toString();
  }

  private void capture(Activity activity, File output, String name) throws Exception {
    captureWindow(activity, output, name, null);
  }

  private void captureDialog(Activity activity, File output, String name, String expectedMessage) throws Exception {
    if (expectedMessage == null || expectedMessage.isBlank()) throw new IllegalArgumentException("Dialog message is missing");
    captureWindow(activity, output, name, expectedMessage);
  }

  private void captureWindow(Activity activity, File output, String name, String expectedMessage) throws Exception {
    waitForIdleSync();
    SystemClock.sleep(250);
    CountDownLatch latch = new CountDownLatch(1);
    Throwable[] failure = new Throwable[1];
    activity.runOnUiThread(
        () -> {
          try {
            ListView list = activity.findViewById(R.id.app_list);
            if (list == null || list.getCount() == 0) throw new IllegalStateException("Public asset selection is empty");
            for (int index = 0; index < list.getCount(); index++) {
              Object row = list.getAdapter().getItem(index);
              if (!(row instanceof LiteApp) || !PUBLIC_ASSET_PACKAGE.equals(((LiteApp) row).packageName)) {
                throw new IllegalStateException("Asset filter exposes an app outside the public Browser fixture");
              }
            }
            TextView details = activity.findViewById(R.id.detail_package);
            if (!activity.getString(R.string.package_detail, PUBLIC_ASSET_PACKAGE).equals(details.getText().toString())) {
              throw new IllegalStateException("Asset details are not the selected public Browser app");
            }
            String selectedComponent = getTargetContext().getSharedPreferences("launcher-lite-state", 0)
                .getString("selected-component", null);
            if (!publicAssetComponent.equals(selectedComponent)) {
              throw new IllegalStateException("Asset selection is not the exact public Browser front door");
            }
            View decor = activity.getWindow().getDecorView();
            if (expectedMessage != null) {
              Field panelField = activity.getClass().getDeclaredField("panel");
              panelField.setAccessible(true);
              Object panel = panelField.get(activity);
              Field dialogField = panel.getClass().getDeclaredField("activeDialog");
              dialogField.setAccessible(true);
              Object current = dialogField.get(panel);
              if (!(current instanceof AlertDialog)) throw new IllegalStateException("No current owner dialog");
              AlertDialog dialog = (AlertDialog) current;
              TextView message = dialog.findViewById(android.R.id.message);
              if (!dialog.isShowing() || dialog.getWindow() == null || message == null
                  || !expectedMessage.contentEquals(message.getText())) {
                throw new IllegalStateException("Displayed owner dialog does not match the selected scene");
              }
              // Draw this real window alone. Never synthesize an overlay/composite with the Activity.
              decor = dialog.getWindow().getDecorView();
            }
            if (decor.getWidth() <= 0 || decor.getHeight() <= 0) {
              throw new IllegalStateException("The app panel has no drawable bounds");
            }
            Bitmap image = Bitmap.createBitmap(STORE_WIDTH, STORE_HEIGHT, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(Color.rgb(9, 17, 28));
            float scale =
                Math.min(
                    (float) STORE_WIDTH / (float) decor.getWidth(),
                    (float) STORE_HEIGHT / (float) decor.getHeight());
            canvas.translate(
                (STORE_WIDTH - decor.getWidth() * scale) / 2f,
                (STORE_HEIGHT - decor.getHeight() * scale) / 2f);
            canvas.scale(scale, scale);
            decor.draw(canvas);
            File file = new File(output, name);
            try (FileOutputStream stream = new FileOutputStream(file)) {
              if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IOException("PNG compression failed for " + name);
              }
            }
            image.recycle();
          } catch (Throwable throwable) {
            failure[0] = throwable;
          } finally {
            latch.countDown();
          }
        });
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out capturing " + name);
    }
    if (failure[0] != null) {
      throw new IllegalStateException("Failed to capture " + name, failure[0]);
    }
  }

  private void runOnMain(Activity activity, Runnable action) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Throwable[] failure = new Throwable[1];
    activity.runOnUiThread(
        () -> {
          try {
            action.run();
          } catch (Throwable throwable) {
            failure[0] = throwable;
          } finally {
            latch.countDown();
          }
        });
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out driving the Store scene");
    }
    if (failure[0] != null) {
      throw new IllegalStateException("Failed to drive the Store scene", failure[0]);
    }
    waitForIdleSync();
    SystemClock.sleep(250);
  }

  private void assertOnMain(Activity activity, Runnable assertion) throws Exception {
    runOnMain(activity, assertion);
  }
}
