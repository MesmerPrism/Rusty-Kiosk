package io.github.mesmerprism.rustykiosk.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public final class RustyKioskLauncherActivity extends Activity {
  private static final String TAG = "RustyKioskLauncher";
  private static final Uri INSTALL_GUIDE =
      Uri.parse("https://mesmerprism.com/Meta-Quest-File-Manager/#kiosk");
  private static final Uri RELEASES =
      Uri.parse("https://github.com/MesmerPrism/Rusty-Kiosk/releases/latest");

  private TextView statusTitle;
  private TextView statusBody;
  private TextView statusDetail;
  private Button retryButton;
  private boolean launchInFlight;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_rusty_kiosk_launcher);
    statusTitle = findViewById(R.id.status_title);
    statusBody = findViewById(R.id.status_body);
    statusDetail = findViewById(R.id.status_detail);
    retryButton = findViewById(R.id.retry_button);
    retryButton.setOnClickListener(view -> inspectAndLaunch());
    findViewById(R.id.install_guide_button)
        .setOnClickListener(view -> openUri(INSTALL_GUIDE));
    findViewById(R.id.releases_button).setOnClickListener(view -> openUri(RELEASES));
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!launchInFlight) {
      inspectAndLaunch();
    }
  }

  private void inspectAndLaunch() {
    launchInFlight = false;
    PackageInfo packageInfo;
    try {
      packageInfo =
          getPackageManager()
              .getPackageInfo(
                  BuildConfig.TARGET_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
    } catch (PackageManager.NameNotFoundException exception) {
      render(KioskLaunchPolicy.Decision.MISSING, null);
      return;
    }

    boolean trustedSigner = hasExpectedSigner(packageInfo.signingInfo);
    Intent launchIntent =
        trustedSigner
            ? getPackageManager().getLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE)
            : null;
    KioskLaunchPolicy.Decision decision =
        KioskLaunchPolicy.decide(true, trustedSigner, launchIntent != null);
    if (decision != KioskLaunchPolicy.Decision.READY) {
      render(decision, null);
      return;
    }

    launchInFlight = true;
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      Log.i(TAG, "state=trusted-launch target=" + BuildConfig.TARGET_PACKAGE);
      startActivity(launchIntent);
      finishAndRemoveTask();
    } catch (ActivityNotFoundException | SecurityException exception) {
      launchInFlight = false;
      Log.e(TAG, "state=launch-failed target=" + BuildConfig.TARGET_PACKAGE, exception);
      render(KioskLaunchPolicy.Decision.NO_FRONT_DOOR, getString(R.string.launch_failed));
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

  private void render(KioskLaunchPolicy.Decision decision, String detailOverride) {
    int title;
    int body;
    String marker;
    switch (decision) {
      case MISSING:
        title = R.string.missing_title;
        body = R.string.missing_body;
        marker = "missing";
        break;
      case SIGNER_MISMATCH:
        title = R.string.repair_title;
        body = R.string.signer_mismatch_body;
        marker = "signer-mismatch";
        break;
      case NO_FRONT_DOOR:
        title = R.string.repair_title;
        body = R.string.no_front_door_body;
        marker = "no-front-door";
        break;
      default:
        throw new IllegalArgumentException("READY must launch instead of rendering");
    }
    statusTitle.setText(title);
    statusBody.setText(body);
    statusDetail.setText(
        detailOverride == null
            ? getString(R.string.target_detail, BuildConfig.TARGET_PACKAGE)
            : detailOverride);
    retryButton.setVisibility(View.VISIBLE);
    Log.i(TAG, "state=" + marker + " target=" + BuildConfig.TARGET_PACKAGE);
  }

  private void openUri(Uri uri) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, uri));
    } catch (ActivityNotFoundException exception) {
      statusDetail.setText(getString(R.string.browser_unavailable, uri.toString()));
      Log.w(TAG, "state=browser-unavailable", exception);
    }
  }
}
