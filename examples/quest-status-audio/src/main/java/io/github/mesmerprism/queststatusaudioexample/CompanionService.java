package io.github.mesmerprism.queststatusaudioexample;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.security.SecureRandom;
import java.util.Arrays;

/** Owns a finite, wearer-start Bluetooth session even when the Activity loses focus. */
public final class CompanionService extends Service {
    static final String START = "io.github.mesmerprism.queststatusaudioexample.action.START";
    private static final String STOP = "io.github.mesmerprism.queststatusaudioexample.action.STOP";
    private static final String CHANNEL = "bluetooth_session";
    private static final int NOTIFICATION_ID = 1;
    private static final long SESSION_MS = 15 * 60 * 1000L;
    private static final long STARTUP_TIMEOUT_MS = 8000;

    record Snapshot(long expiresAt, String code, int message) {
        boolean active() { return expiresAt > SystemClock.elapsedRealtime(); }
    }

    private static Snapshot snapshot = new Snapshot(0, null, R.string.session_inactive);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Runnable expiry = () -> end(R.string.session_expired);
    private final Runnable startupTimeout = () -> end(R.string.bluetooth_failed);
    private long expiresAt;
    private CompanionGatt gatt;
    private CompanionProtocol protocol;
    private TestClipPlayback playback;

    static Snapshot snapshot() {
        if (snapshot.expiresAt() != 0 && !snapshot.active()) {
            return new Snapshot(0, null, R.string.session_expired);
        }
        return snapshot;
    }

    static boolean hasBluetoothPermissions(Context context) {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (!START.equals(action)) {
            end(R.string.session_inactive);
        } else if (gatt == null || SystemClock.elapsedRealtime() >= expiresAt) {
            clearSession();
            startSession();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (snapshot.expiresAt() != 0) snapshot = new Snapshot(0, null, R.string.session_inactive);
        clearSession();
        super.onDestroy();
    }

    private void startSession() {
        if (!hasBluetoothPermissions(this)) {
            end(R.string.permission_needed);
            return;
        }

        expiresAt = SystemClock.elapsedRealtime() + SESSION_MS;
        snapshot = new Snapshot(expiresAt, null, R.string.session_starting);
        try {
            updateForeground(false);
        } catch (IllegalStateException | SecurityException unavailable) {
            end(R.string.bluetooth_failed);
            return;
        }

        byte[] code = randomId();
        byte[] epoch = randomId();
        String visibleCode = hex(code);
        playback = new TestClipPlayback(this, main, this::updateForeground);
        protocol = new CompanionProtocol(code, epoch, playback);
        Arrays.fill(code, (byte) 0);
        gatt = new CompanionGatt(main, protocol, this::usable,
                () -> {
                    main.removeCallbacks(startupTimeout);
                    snapshot = new Snapshot(expiresAt, visibleCode, R.string.session_ready);
                },
                () -> end(R.string.bluetooth_failed));
        if (!gatt.start(this)) {
            end(R.string.bluetooth_unavailable);
            return;
        }
        main.postDelayed(expiry, SESSION_MS);
        main.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS);
    }

    private boolean usable() {
        if (SystemClock.elapsedRealtime() >= expiresAt) {
            end(R.string.session_expired);
            return false;
        }
        if (!hasBluetoothPermissions(this)) {
            end(R.string.permission_needed);
            return false;
        }
        return true;
    }

    private void end(int message) {
        snapshot = new Snapshot(0, null, message);
        try {
            clearSession();
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void clearSession() {
        main.removeCallbacks(expiry);
        main.removeCallbacks(startupTimeout);
        if (gatt != null) gatt.close();
        if (protocol != null) protocol.end();
        if (playback != null) playback.close();
        gatt = null;
        protocol = null;
        playback = null;
        expiresAt = 0;
    }

    private byte[] randomId() {
        byte[] bytes = new byte[CompanionProtocol.ID_LENGTH];
        do { random.nextBytes(bytes); } while (Arrays.equals(bytes, new byte[bytes.length]));
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    private void updateForeground(boolean playing) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.session_notification_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, CompanionActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, CompanionService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getText(R.string.stop_session), stop).build();
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.session_notification_text))
                .setContentIntent(open)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(stopAction)
                .build();
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (playing) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        startForeground(NOTIFICATION_ID, notification, type);
    }
}
