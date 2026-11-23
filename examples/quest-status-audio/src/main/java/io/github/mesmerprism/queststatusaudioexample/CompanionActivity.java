package io.github.mesmerprism.queststatusaudioexample;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Only the wearer-facing Activity starts the Bluetooth session. */
public final class CompanionActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Button start;
    private Button stop;
    private Button approve;
    private Button reject;
    private Button forget;
    private TextView status;
    private TextView codeLabel;
    private TextView code;
    private CompanionService.PairingPrompt displayedPrompt;
    private boolean startRequested;
    private int localMessage;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            main.postDelayed(this, 300);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(20), dp(28), dp(20));
        scroll.addView(content);

        TextView title = text(content, R.string.app_name, 24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text(content, R.string.instructions, 16);
        status = text(content, R.string.session_inactive, 18);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        codeLabel = text(content, R.string.pairing_code, 16);
        code = text(content, 0, 22);
        code.setTypeface(Typeface.MONOSPACE);
        approve = new Button(this);
        approve.setText(R.string.confirm_match);
        approve.setOnClickListener(view -> decide(true));
        content.addView(approve);
        reject = new Button(this);
        reject.setText(R.string.reject_pairing);
        reject.setOnClickListener(view -> decide(false));
        content.addView(reject);
        start = new Button(this);
        start.setText(R.string.enable_session);
        start.setOnClickListener(view -> requestSession());
        content.addView(start);
        stop = new Button(this);
        stop.setText(R.string.stop_session);
        stop.setOnClickListener(view -> {
            localMessage = 0;
            startRequested = false;
            stopService(new Intent(this, CompanionService.class));
            render();
        });
        content.addView(stop);
        forget = new Button(this);
        forget.setText(R.string.forget_browsers);
        forget.setOnClickListener(view -> confirmForget());
        content.addView(forget);
        setContentView(scroll);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }

    private void decide(boolean allow) {
        localMessage = CompanionService.decidePairing(displayedPrompt, allow) ? 0 : R.string.pairing_ended;
        render();
    }

    private void confirmForget() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_browsers)
                .setMessage(R.string.forget_browsers_description)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.forget, (dialog, which) -> {
                    localMessage = CompanionService.forgetBrowsers() ? R.string.browsers_forgotten : R.string.forget_failed;
                    render();
                })
                .show();
    }

    private void requestSession() {
        localMessage = 0;
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) {
            startSession();
        } else {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST && !isFinishing()) startSession();
    }

    private void startSession() {
        if (!CompanionService.hasBluetoothPermissions(this)) {
            localMessage = R.string.permission_needed;
            render();
            return;
        }
        try {
            startRequested = true;
            startForegroundService(new Intent(this, CompanionService.class).setAction(CompanionService.START));
        } catch (IllegalStateException | SecurityException unavailable) {
            localMessage = R.string.bluetooth_failed;
            startRequested = false;
        }
        render();
    }

    private void render() {
        CompanionService.Snapshot current = CompanionService.snapshot();
        if (current.message() != R.string.session_inactive) startRequested = false;
        boolean active = current.active() || startRequested;
        int message = localMessage != 0 ? localMessage : startRequested ? R.string.session_starting : current.message();
        String statusText = getString(message);
        if (!statusText.contentEquals(status.getText())) status.setText(statusText);
        displayedPrompt = CompanionService.pendingPairing();
        boolean comparing = displayedPrompt != null;
        String digits = comparing ? displayedPrompt.code() : "";
        codeLabel.setVisibility(comparing ? View.VISIBLE : View.GONE);
        code.setVisibility(comparing ? View.VISIBLE : View.GONE);
        if (!digits.contentEquals(code.getText())) code.setText(digits);
        approve.setVisibility(comparing ? View.VISIBLE : View.GONE);
        approve.setEnabled(comparing && !displayedPrompt.headsetConfirmed());
        reject.setVisibility(comparing ? View.VISIBLE : View.GONE);
        start.setEnabled(!active);
        stop.setEnabled(active);
        forget.setVisibility(active && CompanionService.hasEnrollment() ? View.VISIBLE : View.GONE);
    }

    private TextView text(LinearLayout content, int resource, int size) {
        TextView value = new TextView(this);
        value.setTextSize(size);
        value.setPadding(0, dp(6), 0, dp(6));
        if (resource != 0) value.setText(resource);
        content.addView(value);
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
