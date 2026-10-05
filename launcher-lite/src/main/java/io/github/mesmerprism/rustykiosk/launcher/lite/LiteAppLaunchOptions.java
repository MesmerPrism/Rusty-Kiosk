package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.mesmerprism.rustykiosk.launcher.lite.LiteLaunchOptionsPolicy.require;

/** Read-only selected-app adapter. Call from a UI-owned worker, then render on the main thread. */
final class LiteAppLaunchOptions implements AutoCloseable {
  private final Context context;
  private final PackageManager packages;
  private final ExecutorService queries = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "lite-launch-options-query");
    thread.setDaemon(true);
    return thread;
  });
  private volatile boolean timedOut;
  private volatile boolean closed;

  LiteAppLaunchOptions(Context context) {
    this.context = context.getApplicationContext();
    this.packages = this.context.getPackageManager();
  }

  enum Status { NONE, READY, REJECTED }

  static final class State {
    final Status status;
    final String message;
    final List<LiteLaunchOptionsPolicy.Option> options;
    final LiteLaunchOptionsPolicy.Binding binding;
    State(Status status, String message, List<LiteLaunchOptionsPolicy.Option> options,
        LiteLaunchOptionsPolicy.Binding binding) {
      this.status = status;
      this.message = message;
      this.options = options;
      this.binding = binding;
    }
  }

  State discover(LiteApp app) {
    if (app == null) return none();
    try {
      require(!closed, "launch-options-closed");
      LiteLaunchOptionsPolicy.Binding before = resolveBinding(app);
      if (before == null) return none();
      List<LiteLaunchOptionsPolicy.Option> options = queryRows(app.packageName);
      require(before.equals(resolveBinding(app)), "launch-options-binding-changed-during-query");
      return new State(Status.READY, options.isEmpty() ? "No app-provided launch options."
          : "App-provided launch options", LiteLaunchOptionsPolicy.validateRows(options), before);
    } catch (Exception failure) {
      return rejected();
    }
  }

  /** Fresh exact row and complete binding must match the row presented to the wearer. */
  State resolveForLaunch(LiteApp app, State expected, LiteLaunchOptionsPolicy.Option option) {
    State fresh = discover(app);
    if (expected == null || expected.status != Status.READY || fresh.status != Status.READY
        || !LiteLaunchOptionsPolicy.accepts(expected.binding, option, fresh.binding, fresh.options)) {
      return rejected();
    }
    return fresh;
  }

  /** Revalidate at dispatch after any Wi-Fi wait; adds only the fixed opaque-ID extra. */
  Intent validatedIntent(LiteApp app, State expected, LiteLaunchOptionsPolicy.Option option) {
    State fresh = resolveForLaunch(app, expected, option);
    require(fresh.status == Status.READY, "launch-option-dispatch-rejected");
    require(fresh.binding.identity.packageName.equals(app.packageName)
        && fresh.binding.identity.activityName.equals(app.activityName)
        && fresh.binding.identity.category.equals(app.category), "launch-option-front-door-changed");
    return new Intent(Intent.ACTION_MAIN).addCategory(app.category)
        .setComponent(new ComponentName(app.packageName, app.activityName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        .putExtra(LiteLaunchOptionsPolicy.EXTRA_OPTION_ID, option.optionId);
  }

  @SuppressWarnings("deprecation")
  private LiteLaunchOptionsPolicy.Binding resolveBinding(LiteApp app) throws Exception {
    LiteApp current = null;
    for (LiteApp candidate : LiteCatalog.load(packages, context.getPackageName())) {
      if (candidate.key().equals(app.key())) { current = candidate; break; }
    }
    require(current != null && current.category.equals(app.category), "launch-options-front-door-missing");
    ApplicationInfo application = packages.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA);
    Bundle metadata = application.metaData;
    String schema = metadata == null ? null : metadata.getString("rusty.quest.app_launch_options.schema");
    String authority = metadata == null ? null : metadata.getString("rusty.quest.app_launch_options.provider_authority");
    String owner = metadata == null ? null : metadata.getString("rusty.quest.app_launch_options.owner_activity");
    if (!LiteLaunchOptionsPolicy.metadata(app.packageName, app.activityName, schema, authority, owner)) return null;
    ProviderInfo provider = packages.resolveContentProvider(authority, 0);
    require(provider != null && app.packageName.equals(provider.packageName)
        && authority.equals(provider.authority), "launch-options-provider-owner-invalid");
    require(provider.enabled && provider.exported && !provider.grantUriPermissions
        && provider.applicationInfo != null && provider.applicationInfo.enabled
        && provider.applicationInfo.uid == application.uid && provider.name != null,
        "launch-options-provider-surface-invalid");
    LiteLaunchOptionsPolicy.exclusiveUid(app.packageName, packages.getPackagesForUid(application.uid));
    ActivityInfo activity = packages.getActivityInfo(new ComponentName(app.packageName, app.activityName), 0);
    require(app.packageName.equals(activity.packageName) && app.activityName.equals(activity.name)
        && activity.enabled && activity.exported && application.enabled
        && activity.applicationInfo != null && activity.applicationInfo.uid == application.uid,
        "launch-options-activity-surface-invalid");
    PackageInfo installation = packages.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
    require(installation.applicationInfo != null && installation.applicationInfo.uid == application.uid
        && installation.signingInfo != null && !installation.signingInfo.hasMultipleSigners()
        && installation.lastUpdateTime > 0, "launch-options-installation-invalid");
    Signature[] currentSigners = installation.signingInfo.getApkContentsSigners();
    require(currentSigners != null && currentSigners.length == 1, "launch-options-current-signer-invalid");
    Signature[] lineage = installation.signingInfo.getSigningCertificateHistory();
    require(lineage != null && lineage.length > 0, "launch-options-signing-identity-unavailable");
    List<byte[]> certificates = new ArrayList<>();
    for (Signature signer : lineage) {
      require(signer != null, "launch-options-signing-identity-unavailable");
      certificates.add(signer.toByteArray());
    }
    LiteInstalledIdentity identity = new LiteInstalledIdentity(app.packageName, app.activityName,
        app.category, installation.getLongVersionCode(), installation.lastUpdateTime, application.uid,
        LiteLaunchOptionsPolicy.signingIdentity(installation.signingInfo.hasMultipleSigners(),
            currentSigners.length, certificates));
    return new LiteLaunchOptionsPolicy.Binding(identity, authority, provider.name);
  }

  private List<LiteLaunchOptionsPolicy.Option> queryRows(String packageName) throws Exception {
    require(!timedOut && !closed, "launch-options-query-unavailable");
    CancellationSignal cancellation = new CancellationSignal();
    Future<List<LiteLaunchOptionsPolicy.Option>> future = queries.submit(() -> queryBlocking(packageName, cancellation));
    try {
      return future.get(1500, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      timedOut = true;
      cancel(future, cancellation);
      throw timeout;
    } catch (InterruptedException interrupted) {
      // A cancelled Activity query cannot leave a reusable unbounded provider worker behind.
      timedOut = true;
      cancel(future, cancellation);
      Thread.currentThread().interrupt();
      throw interrupted;
    }
  }

  private static void cancel(Future<?> future, CancellationSignal cancellation) {
    future.cancel(true);
    Thread thread = new Thread(() -> {
      try { cancellation.cancel(); } catch (RuntimeException ignored) { }
    }, "lite-launch-options-cancel");
    thread.setDaemon(true);
    thread.start();
  }

  private List<LiteLaunchOptionsPolicy.Option> queryBlocking(String pkg, CancellationSignal cancellation) {
    Uri uri = new Uri.Builder().scheme("content").authority(pkg + LiteLaunchOptionsPolicy.AUTHORITY_SUFFIX)
        .appendPath("options").build();
    try (Cursor cursor = context.getContentResolver().query(uri, LiteLaunchOptionsPolicy.PROJECTION,
        null, null, null, cancellation)) {
      require(cursor != null, "launch-options-query-null");
      require(Arrays.equals(cursor.getColumnNames(), LiteLaunchOptionsPolicy.PROJECTION), "launch-options-columns-invalid");
      List<LiteLaunchOptionsPolicy.Option> rows = new ArrayList<>();
      while (cursor.moveToNext()) {
        require(rows.size() < LiteLaunchOptionsPolicy.MAX_COUNT, "launch-option-count-invalid");
        require(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(1) == Cursor.FIELD_TYPE_STRING
            && cursor.getType(2) == Cursor.FIELD_TYPE_STRING && cursor.getType(3) == Cursor.FIELD_TYPE_STRING,
            "launch-options-column-types-invalid");
        rows.add(new LiteLaunchOptionsPolicy.Option(LiteLaunchOptionsPolicy.schemaVersion(cursor.getLong(0)), cursor.getString(1),
            cursor.getString(2), cursor.getString(3)));
      }
      return rows;
    }
  }

  private static State none() {
    return new State(Status.NONE, "", List.of(), null);
  }
  private static State rejected() {
    return new State(Status.REJECTED, "App-provided launch options could not be verified.", List.of(), null);
  }
  @Override public void close() { closed = true; queries.shutdownNow(); }
}
