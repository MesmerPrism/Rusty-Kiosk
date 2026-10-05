package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class LitePanelController {
  private final Activity activity;
  private final View root;
  private final LitePresentationHost presentation;
  private boolean released;
  private boolean restoring;
  private boolean resumed;
  private AlertDialog activeDialog;
  private final java.util.concurrent.ExecutorService optionsWorker =
      new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(1),
          new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
  private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final LiteAppLaunchOptions launchOptions;
  private java.util.concurrent.Future<?> optionsTask;
  private long optionsGeneration;
  private boolean optionLaunchPending;
  private boolean launchInFlight;
  private boolean presentationSwitchInFlight;
  private LinearLayout optionsContainer;
  private TextView optionsStatus;

  private static final String TAG = "RustyLauncherLite";

  private LitePreferenceStore store;
  private List<LiteApp> allApps = Collections.emptyList();
  private List<LiteApp> visibleApps = Collections.emptyList();
  private LiteApp selected;
  private LiteAppAdapter adapter;
  private EditText search;
  private EditText tagEditor;
  private ListView appList;
  private TextView status;
  private TextView detailTitle;
  private TextView detailPackage;
  private TextView detailActivity;
  private LinearLayout tagChips;
  private Button favoriteButton;
  private Button launchButton;
  private RadioGroup wifiGroup;
  private boolean favoritesOnly;
  private boolean renderingSelection;
  private LiteLaunchBinding pendingWifiLaunch;
  private boolean fullKioskAvailable;

  LitePanelController(Activity activity, View root, LitePresentationHost presentation) {
    this.activity = activity;
    this.root = root;
    this.presentation = presentation;
    store = new LitePreferenceStore(activity);
    launchOptions = new LiteAppLaunchOptions(activity);
    favoritesOnly = store.favoritesOnly();
    bindViews();
    bindActions();
    restoring = true;
    search.setText(store.search());
    search.setSelection(search.length());
    restoring = false;
    reloadCatalog();
  }

  void onResume() {
    if (released) return;
    resumed = true;
    launchInFlight = false;
    cancelOptionTask();
    refreshOptions();
    if (pendingWifiLaunch != null) {
      LiteLaunchBinding binding = pendingWifiLaunch;
      pendingWifiLaunch = null;
      LiteInstalledIdentity currentIdentity = readInstalledIdentity(binding.app);
      WifiRequirement currentRequirement = store.wifiRequirement(binding.app.key());
      if (binding.accepts(currentIdentity, currentRequirement, SystemClock.elapsedRealtime())
          && currentRequirement.isSatisfied(readWifiEnabled())) {
        startApp(binding.app);
      } else {
        status.setText(R.string.wifi_guidance);
      }
    }
    refreshFullKioskState();
  }

  void onPause() {
    resumed = false;
    cancelOptionTask();
    // Match the working hybrid Desktop: a paused but visible host may be under the native IME.
    Log.i(TAG, "event=panel-paused mode=" + presentation.mode() + " keyboardDismissed=false");
  }

  void onStop() {
    hideKeyboard();
    Log.i(TAG, "event=panel-stopped mode=" + presentation.mode() + " keyboardCleanup=true");
  }

  void onFocusLost(String source) {
    boolean cancelledLaunch = optionLaunchPending;
    if (cancelledLaunch) cancelOptionTask();
    // Horizon's native IME overlay can take window/VR focus from the immersive host.
    // Revoke pending dispatch without dismissing that overlay or clearing its editor.
    // Activity stop, presentation switch and release retain keyboard cleanup.
    Log.i(TAG, "event=panel-focus-lost mode=" + presentation.mode() + " source=" + source
        + " optionLaunchCancelled=" + cancelledLaunch + " editorFocused=" + (root.findFocus() instanceof EditText)
        + " keyboardDismissed=false");
  }

  void release() {
    if (released) return;
    released = true;
    resumed = false;
    pendingWifiLaunch = null;
    cancelOptionTask();
    optionsWorker.shutdownNow();
    launchOptions.close();
    mainHandler.removeCallbacksAndMessages(null);
    dismissDialog();
    hideKeyboard();
    Log.i(TAG, "event=panel-released mode=" + presentation.mode());
  }

  private void switchPresentation() {
    if (released || !resumed || presentationSwitchInFlight) return;
    presentationSwitchInFlight = true;
    // A settings-return launch belongs only to this host. Never transfer/replay it.
    pendingWifiLaunch = null;
    cancelOptionTask();
    dismissDialog();
    hideKeyboard();
    try {
      presentation.switchPresentation();
      Log.i(TAG, "event=presentation-switch from=" + presentation.mode());
    } catch (ActivityNotFoundException | SecurityException exception) {
      presentationSwitchInFlight = false;
      status.setText(R.string.presentation_failed);
      Log.w(TAG, "event=presentation-switch-rejected", exception);
    }
  }

  private void dismissDialog() {
    if (activeDialog != null) {
      activeDialog.dismiss();
      activeDialog = null;
    }
  }

  private void bindKeyboard(EditText field) {
    field.setFocusableInTouchMode(true);
    field.setShowSoftInputOnFocus(true);
    field.setOnFocusChangeListener((view, focused) -> { if (focused) requestKeyboard(field); });
    field.setOnClickListener(view -> requestKeyboard(field));
  }

  private android.view.inputmethod.InputMethodManager keyboardFor(View field) {
    android.view.Display display = field.getDisplay();
    Context context = display == null ? field.getContext() : field.getContext().createDisplayContext(display);
    return (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
  }

  private void requestKeyboard(EditText field) { requestKeyboard(field, 1); }

  private void requestKeyboard(EditText field, int attempt) {
    field.post(() -> {
      if (released || !resumed || !field.hasFocus() || !field.isAttachedToWindow()) return;
      android.view.inputmethod.InputMethodManager ime = keyboardFor(field);
      if (ime == null) return;
      ime.restartInput(field);
      boolean accepted = ime.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
      Log.i(TAG, "event=keyboard-requested mode=" + presentation.mode() + " accepted=" + accepted + " attempt=" + attempt);
      if (!accepted && attempt < 3) field.postDelayed(() -> requestKeyboard(field, attempt + 1), 150);
    });
  }

  private void hideKeyboard() {
    View focused = root.findFocus();
    if (focused != null) {
      android.view.inputmethod.InputMethodManager ime = keyboardFor(focused);
      if (ime != null && focused.getWindowToken() != null) ime.hideSoftInputFromWindow(focused.getWindowToken(), 0);
      focused.clearFocus();
    }
  }

  private <T extends View> T findViewById(int id) { return root.findViewById(id); }
  private String getString(int id, Object... values) { return activity.getString(id, values); }
  private int getColor(int id) { return activity.getColor(id); }
  private PackageManager getPackageManager() { return activity.getPackageManager(); }
  private String getPackageName() { return activity.getPackageName(); }
  private Context getApplicationContext() { return activity.getApplicationContext(); }
  private void startActivity(Intent intent) { activity.startActivity(intent); }

  private void bindViews() {
    search = findViewById(R.id.search);
    tagEditor = findViewById(R.id.tag_editor);
    appList = findViewById(R.id.app_list);
    status = findViewById(R.id.status);
    detailTitle = findViewById(R.id.detail_title);
    detailPackage = findViewById(R.id.detail_package);
    detailActivity = findViewById(R.id.detail_activity);
    tagChips = findViewById(R.id.tag_chips);
    favoriteButton = findViewById(R.id.favorite_selected_button);
    launchButton = findViewById(R.id.launch_button);
    wifiGroup = findViewById(R.id.wifi_group);
    optionsContainer = findViewById(R.id.launch_options_container);
    optionsStatus = findViewById(R.id.launch_options_status);
    adapter = new LiteAppAdapter(activity, store);
    appList.setAdapter(adapter);
    appList.setEmptyView(findViewById(R.id.empty_view));
  }

  private void bindActions() {
    Button mode = findViewById(R.id.presentation_button);
    mode.setText(presentation.isImmersive() ? R.string.window_mode : R.string.immersive_mode);
    mode.setOnClickListener(view -> switchPresentation());
    bindKeyboard(search);
    bindKeyboard(tagEditor);
    search.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence value, int start, int before, int count) {
            if (restoring) return;
            store.setSearch(value.toString());
            applyFilter();
          }

          @Override
          public void afterTextChanged(Editable value) {}
        });
    findViewById(R.id.refresh_button).setOnClickListener(view -> reloadCatalog());
    findViewById(R.id.help_button).setOnClickListener(view -> showHelp());
    findViewById(R.id.all_button)
        .setOnClickListener(
            view -> {
              favoritesOnly = false;
              store.setFavoritesOnly(false);
              applyFilter();
            });
    findViewById(R.id.favorites_button)
        .setOnClickListener(
            view -> {
              favoritesOnly = true;
              store.setFavoritesOnly(true);
              applyFilter();
            });
    appList.setOnItemClickListener(
        (parent, view, position, id) -> select(adapter.getItem(position)));
    favoriteButton.setOnClickListener(
        view -> {
          if (selected == null) {
            return;
          }
          store.setFavorite(selected.key(), !store.isFavorite(selected.key()));
          renderSelection();
          applyFilter();
        });
    findViewById(R.id.add_tag_button).setOnClickListener(view -> addTag());
    tagEditor.setOnEditorActionListener(
        (view, actionId, event) -> {
          addTag();
          return true;
        });
    wifiGroup.setOnCheckedChangeListener(
        (group, checkedId) -> {
          if (selected == null || renderingSelection) {
            return;
          }
          store.setWifiRequirement(selected.key(), requirementForId(checkedId));
        });
    launchButton.setOnClickListener(view -> launchSelected());
    findViewById(R.id.full_kiosk_button)
        .setOnClickListener(
            view -> {
              if (!released && resumed && !presentationSwitchInFlight && !launchInFlight && !optionLaunchPending && fullKioskAvailable) {
                Intent trustedIntent = resolveTrustedFullKiosk();
                if (trustedIntent == null) {
                  refreshFullKioskState();
                  status.setText(R.string.launch_failed);
                  return;
                }
                try {
                  pendingWifiLaunch = null;
                  cancelOptionTask();
                  hideKeyboard();
                  launchInFlight = true;
                  startActivity(trustedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException exception) {
                  launchInFlight = false;
                  status.setText(R.string.launch_failed);
                  Log.w(TAG, "event=optional-kiosk-launch-rejected", exception);
                }
              }
            });
  }

  private void reloadCatalog() {
    String selectedKey = selected == null ? store.selectedComponent() : selected.key();
    allApps = LiteCatalog.load(getPackageManager(), getPackageName());
    Set<String> installedKeys = new TreeSet<>();
    for (LiteApp app : allApps) {
      installedKeys.add(app.key());
    }
    store.pruneToCatalog(installedKeys);
    selected = findByKey(selectedKey, allApps);
    applyFilter();
    refreshFullKioskState();
    Log.i(TAG, "event=catalog-ready count=" + allApps.size());
  }

  private void applyFilter() {
    if (store == null || adapter == null) {
      return;
    }
    String query = search == null ? "" : search.getText().toString();
    List<LiteApp> filtered = new ArrayList<>();
    for (LiteApp app : allApps) {
      Set<String> tags = store.tags(app.key());
      if ((!favoritesOnly || store.isFavorite(app.key())) && app.matches(query, tags)) {
        filtered.add(app);
      }
    }
    visibleApps = filtered;
    adapter.replace(filtered);
    if (selected == null || findByKey(selected.key(), filtered) == null) {
      selected = filtered.isEmpty() ? null : filtered.get(0);
    }
    store.setSelectedComponent(selected == null ? null : selected.key());
    renderSelection();
    Boolean wifi = readWifiEnabled();
    String wifiLabel = wifi == null ? "unavailable" : (wifi ? "on" : "off");
    if (query.isBlank() && !favoritesOnly) {
      status.setText(getString(R.string.catalog_status, allApps.size(), wifiLabel));
    } else {
      status.setText(getString(R.string.filtered_status, filtered.size(), allApps.size()));
    }
  }

  private void select(LiteApp app) {
    selected = app;
    store.setSelectedComponent(app == null ? null : app.key());
    renderSelection();
  }

  private void renderSelection() {
    renderingSelection = true;
    try {
      boolean hasSelection = selected != null;
      favoriteButton.setEnabled(hasSelection);
      launchButton.setEnabled(hasSelection);
      tagEditor.setEnabled(hasSelection);
      findViewById(R.id.add_tag_button).setEnabled(hasSelection);
      wifiGroup.setEnabled(hasSelection);
      tagChips.removeAllViews();
      if (!hasSelection) {
        detailTitle.setText(R.string.select_app);
        detailPackage.setText("");
        detailActivity.setText("");
        wifiGroup.check(R.id.wifi_any);
        return;
      }
      detailTitle.setText(selected.label);
      detailPackage.setText(getString(R.string.package_detail, selected.packageName));
      detailActivity.setText(getString(R.string.activity_detail, selected.activityName));
      favoriteButton.setText(
          store.isFavorite(selected.key()) ? R.string.remove_favorite : R.string.add_favorite);
      Set<String> tags = store.tags(selected.key());
      if (tags.isEmpty()) {
        TextView empty = new TextView(activity);
        empty.setText(R.string.no_tags);
        empty.setTextColor(getColor(R.color.on_surface_muted));
        tagChips.addView(empty);
      } else {
        for (String tag : tags) {
          Button chip = new Button(activity);
          chip.setAllCaps(false);
          chip.setText(tag + "  ×");
          chip.setOnClickListener(view -> removeTag(tag));
          tagChips.addView(chip);
        }
      }
      WifiRequirement requirement = store.wifiRequirement(selected.key());
      wifiGroup.check(idForRequirement(requirement));
    } finally {
      renderingSelection = false;
      refreshOptions();
    }
  }

  private void addTag() {
    if (selected == null) {
      return;
    }
    String normalized = LiteTagPolicy.normalize(tagEditor.getText().toString());
    if (normalized == null) {
      status.setText(R.string.tag_invalid);
      return;
    }
    if (store.addTag(selected.key(), normalized)) {
      status.setText(getString(R.string.tag_added, normalized));
    }
    tagEditor.setText("");
    renderSelection();
    applyFilter();
  }

  private void removeTag(String tag) {
    if (selected == null) {
      return;
    }
    store.removeTag(selected.key(), tag);
    status.setText(getString(R.string.tag_removed, tag));
    renderSelection();
    applyFilter();
  }

  private void launchSelected() {
    if (released || !resumed || presentationSwitchInFlight || launchInFlight || optionLaunchPending || selected == null) {
      return;
    }
    WifiRequirement requirement = store.wifiRequirement(selected.key());
    if (requirement.isSatisfied(readWifiEnabled())) {
      startApp(selected);
      return;
    }
    String guidance =
        getString(
            requirement == WifiRequirement.ON ? R.string.wifi_on_needed : R.string.wifi_off_needed,
            selected.label);
    status.setText(guidance);
    LiteApp requestedApp = selected;
    dismissDialog();
    activeDialog = new AlertDialog.Builder(activity)
        .setTitle(R.string.wifi_requirement)
        .setMessage(guidance)
        .setPositiveButton(
            R.string.open_wifi_settings,
            (dialog, which) -> openWifiSettings(requestedApp, requirement))
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void openWifiSettings(LiteApp requestedApp, WifiRequirement requirement) {
    if (released || !resumed) return;
    LiteInstalledIdentity identity = readInstalledIdentity(requestedApp);
    if (identity == null) {
      status.setText(R.string.launch_failed);
      reloadCatalog();
      return;
    }
    pendingWifiLaunch =
        new LiteLaunchBinding(requestedApp, identity, requirement, SystemClock.elapsedRealtime());
    try {
      startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
      Log.i(TAG, "event=wifi-settings-opened requirement=" + requirement.storedValue);
    } catch (ActivityNotFoundException | SecurityException exception) {
      pendingWifiLaunch = null;
      status.setText(R.string.launch_failed);
      Log.w(TAG, "event=wifi-settings-rejected", exception);
    }
  }

  private void startApp(LiteApp app) {
    if (released || !resumed || presentationSwitchInFlight || launchInFlight) return;
    pendingWifiLaunch = null;
    cancelOptionTask();
    hideKeyboard();
    Intent intent =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(app.category)
            .setComponent(new ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      launchInFlight = true;
      startActivity(intent);
      Log.i(TAG, "event=app-launched package=" + app.packageName + " activity=" + app.activityName);
    } catch (ActivityNotFoundException | SecurityException exception) {
      launchInFlight = false;
      status.setText(R.string.launch_failed);
      Log.w(TAG, "event=app-launch-rejected package=" + app.packageName, exception);
      reloadCatalog();
    }
  }

  private void cancelOptionTask() {
    optionsGeneration++;
    boolean interrupt = optionLaunchPending;
    optionLaunchPending = false;
    if (optionsTask != null) { optionsTask.cancel(interrupt); optionsTask = null; }
  }

  private void refreshOptions() {
    if (released || optionsContainer == null) return;
    cancelOptionTask();
    optionsContainer.removeAllViews();
    LiteApp app = selected;
    if (app == null) { optionsStatus.setText(""); return; }
    long generation = optionsGeneration;
    optionsStatus.setText(R.string.launch_options_loading);
    optionsTask = optionsWorker.submit(() -> {
      LiteAppLaunchOptions.State result = launchOptions.discover(app);
      mainHandler.post(() -> {
        if (released || generation != optionsGeneration || selected == null || !selected.key().equals(app.key())) return;
        optionsTask = null;
        optionsStatus.setText(result.message);
        for (LiteLaunchOptionsPolicy.Option option : result.options) {
          Button button = new Button(activity);
          button.setAllCaps(false);
          button.setText(option.displayLabel);
          button.setContentDescription(option.displayLabel + ". " + option.description);
          button.setOnClickListener(view -> launchOption(app, result, option));
          optionsContainer.addView(button);
        }
      });
    });
  }

  private void launchOption(LiteApp app, LiteAppLaunchOptions.State shown, LiteLaunchOptionsPolicy.Option option) {
    if (released || !resumed || presentationSwitchInFlight || launchInFlight || optionLaunchPending || selected == null || !selected.key().equals(app.key())) return;
    WifiRequirement requirement = store.wifiRequirement(app.key());
    if (!requirement.isSatisfied(readWifiEnabled())) {
      // Option requests never become a resumable ordinary launch. The wearer retries after settings.
      status.setText(getString(requirement == WifiRequirement.ON ? R.string.wifi_on_needed : R.string.wifi_off_needed, app.label));
      pendingWifiLaunch = null;
      dismissDialog();
      activeDialog = new AlertDialog.Builder(activity).setTitle(R.string.wifi_requirement)
          .setMessage(status.getText())
          .setPositiveButton(R.string.open_wifi_settings, (dialog, which) -> {
            if (released || !resumed) return;
            try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException error) { status.setText(R.string.launch_failed); }
          }).setNegativeButton(R.string.cancel, null).show();
      return;
    }
    LiteInstalledIdentity identity = readInstalledIdentity(app);
    if (identity == null) { status.setText(R.string.launch_failed); return; }
    cancelOptionTask();
    long generation = optionsGeneration;
    optionLaunchPending = true;
    optionsTask = optionsWorker.submit(() -> {
      Intent validated = null;
      try { validated = launchOptions.validatedIntent(app, shown, option); }
      catch (RuntimeException error) { Log.w(TAG, "event=launch-option-rejected", error); }
      Intent launch = validated;
      mainHandler.post(() -> {
        if (released || generation != optionsGeneration) return;
        optionsTask = null;
        optionLaunchPending = false;
        if (launch == null || !resumed || launchInFlight || selected == null || !selected.key().equals(app.key())
            || requirement != store.wifiRequirement(app.key()) || !requirement.isSatisfied(readWifiEnabled())
            || !identity.equals(readInstalledIdentity(app))) {
          status.setText(R.string.launch_option_failed);
          return;
        }
        pendingWifiLaunch = null;
        hideKeyboard();
        try {
          launchInFlight = true;
          startActivity(launch);
          Log.i(TAG, "event=launch-option-dispatched package=" + app.packageName);
        } catch (ActivityNotFoundException | SecurityException error) {
          launchInFlight = false;
          status.setText(R.string.launch_option_failed);
        }
      });
    });
  }

  private Boolean readWifiEnabled() {
    WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    return wifi == null ? null : wifi.isWifiEnabled();
  }

  private void refreshFullKioskState() {
    fullKioskAvailable = resolveTrustedFullKiosk() != null;
    Button button = findViewById(R.id.full_kiosk_button);
    TextView message = findViewById(R.id.full_kiosk_status);
    button.setVisibility(fullKioskAvailable ? View.VISIBLE : View.GONE);
    message.setText(
        fullKioskAvailable ? R.string.full_kiosk_available : R.string.full_kiosk_unavailable);
  }

  private LiteInstalledIdentity readInstalledIdentity(LiteApp expected) {
    LiteApp current =
        findByKey(expected.key(), LiteCatalog.load(getPackageManager(), getPackageName()));
    if (current == null || !expected.category.equals(current.category)) {
      return null;
    }
    try {
      PackageInfo info =
          getPackageManager()
              .getPackageInfo(expected.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
      if (info.applicationInfo == null || info.signingInfo == null) {
        return null;
      }
      String signerFingerprint = currentSignerFingerprint(info.signingInfo);
      if (signerFingerprint == null) {
        return null;
      }
      return new LiteInstalledIdentity(
          expected.packageName,
          expected.activityName,
          expected.category,
          info.getLongVersionCode(),
          info.lastUpdateTime,
          info.applicationInfo.uid,
          signerFingerprint);
    } catch (PackageManager.NameNotFoundException exception) {
      return null;
    }
  }

  private static String currentSignerFingerprint(SigningInfo signingInfo) {
    Signature[] current = signingInfo.getApkContentsSigners();
    if (current == null || current.length == 0) {
      return null;
    }
    List<String> digests = new ArrayList<>();
    for (Signature signer : current) {
      if (signer == null) {
        return null;
      }
      digests.add(SignerDigest.sha256(signer.toByteArray()));
    }
    Collections.sort(digests);
    return String.join(",", digests);
  }

  private Intent resolveTrustedFullKiosk() {
    try {
      PackageInfo info =
          getPackageManager()
              .getPackageInfo(BuildConfig.TARGET_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
      if (!hasExpectedSigner(info.signingInfo)) {
        return null;
      }
      Intent launchIntent = getPackageManager().getLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE);
      if (launchIntent == null || launchIntent.getComponent() == null) {
        return null;
      }
      android.content.pm.ActivityInfo activity =
          getPackageManager().getActivityInfo(launchIntent.getComponent(), 0);
      if (!activity.exported
          || !activity.enabled
          || activity.applicationInfo == null
          || !activity.applicationInfo.enabled
          || !BuildConfig.TARGET_PACKAGE.equals(activity.packageName)) {
        return null;
      }
      return new Intent(launchIntent).setComponent(launchIntent.getComponent());
    } catch (PackageManager.NameNotFoundException exception) {
      return null;
    }
  }

  private boolean hasExpectedSigner(SigningInfo signingInfo) {
    if (signingInfo == null) {
      return false;
    }
    return SignerTrustPolicy.matchesExpected(
        signingInfo.hasMultipleSigners(),
        certificateBytes(signingInfo.getApkContentsSigners()),
        certificateBytes(signingInfo.getSigningCertificateHistory()),
        BuildConfig.EXPECTED_TARGET_SIGNER_SHA256);
  }

  private static byte[][] certificateBytes(Signature[] signers) {
    if (signers == null) {
      return null;
    }
    byte[][] certificates = new byte[signers.length][];
    for (int index = 0; index < signers.length; index += 1) {
      certificates[index] = signers[index] == null ? null : signers[index].toByteArray();
    }
    return certificates;
  }

  private void showHelp() {
    dismissDialog();
    activeDialog = new AlertDialog.Builder(activity)
        .setTitle(R.string.about_title)
        .setMessage(R.string.about_body)
        .setPositiveButton(R.string.close, null)
        .show();
  }

  private WifiRequirement requirementForId(int id) {
    if (id == R.id.wifi_on) {
      return WifiRequirement.ON;
    }
    if (id == R.id.wifi_off) {
      return WifiRequirement.OFF;
    }
    return WifiRequirement.ANY;
  }

  private int idForRequirement(WifiRequirement requirement) {
    if (requirement == WifiRequirement.ON) {
      return R.id.wifi_on;
    }
    if (requirement == WifiRequirement.OFF) {
      return R.id.wifi_off;
    }
    return R.id.wifi_any;
  }

  private static LiteApp findByKey(String key, List<LiteApp> apps) {
    if (key == null) {
      return null;
    }
    for (LiteApp app : apps) {
      if (key.equals(app.key())) {
        return app;
      }
    }
    return null;
  }
}
