package io.github.mesmerprism.queststatusaudioexample;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.security.SecureRandom;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns a finite wearer-start session and its authenticated app operations. */
public final class CompanionService extends Service {
    static final String START = "io.github.mesmerprism.queststatusaudioexample.action.START";
    private static final String STOP = "io.github.mesmerprism.queststatusaudioexample.action.STOP";
    private static final String CHANNEL = "bluetooth_session";
    private static final int NOTIFICATION_ID = 1;
    private static final long SESSION_MS = CompanionStatus.MAX_REMAINING_SECONDS * 1000L;
    private static final long STARTUP_TIMEOUT_MS = 8000;

    record PairingPrompt(String id, String code, boolean headsetConfirmed) {}

    record Snapshot(long expiresAt, int message) {
        boolean active() { return expiresAt > SystemClock.elapsedRealtime(); }
    }

    private static Snapshot snapshot = new Snapshot(0, R.string.session_inactive);
    private static CompanionService current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Runnable expiry = () -> end(R.string.session_expired);
    private final Runnable startupTimeout = this::finishWithoutDiscovery;
    private long expiresAt;
    private CompanionGatt gatt;
    private CompanionGateway gateway;
    private CompanionProtocol protocol;
    private TestClipPlayback playback;

    static Snapshot snapshot() {
        if (snapshot.expiresAt() != 0 && !snapshot.active()) {
            return new Snapshot(0, R.string.session_expired);
        }
        return snapshot;
    }

    static PairingPrompt pendingPairing() {
        if (current == null || current.gateway == null || !snapshot().active()) return null;
        JSONObject pending = current.gateway.pendingPrompt();
        return pending == null ? null : new PairingPrompt(pending.optString("pairing_id"),
                pending.optString("code"), pending.optBoolean("headset_confirmed"));
    }

    static boolean decidePairing(PairingPrompt shown, boolean allow) {
        PairingPrompt live = pendingPairing();
        if (shown == null || live == null || !shown.equals(live)) return false;
        try {
            current.gateway.decide(live.id(), allow);
            return true;
        } catch (RuntimeException stale) {
            return false;
        }
    }

    static boolean hasEnrollment() {
        return current != null && current.gateway != null && current.gateway.hasEnrollment();
    }

    static boolean forgetBrowsers() {
        if (current == null || current.gateway == null || !snapshot().active()) return false;
        try {
            current.gateway.revokeAll();
            return true;
        } catch (Exception failed) {
            return false;
        }
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
        } else if (gateway == null || SystemClock.elapsedRealtime() >= expiresAt) {
            clearSession();
            startSession();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (snapshot.expiresAt() != 0) snapshot = new Snapshot(0, R.string.session_inactive);
        clearSession();
        super.onDestroy();
    }

    private void startSession() {
        if (!hasBluetoothPermissions(this)) {
            end(R.string.permission_needed);
            return;
        }

        expiresAt = SystemClock.elapsedRealtime() + SESSION_MS;
        snapshot = new Snapshot(expiresAt, R.string.session_starting);
        try {
            updateForeground(false);
        } catch (IllegalStateException | SecurityException unavailable) {
            end(R.string.bluetooth_failed);
            return;
        }

        playback = new TestClipPlayback(this, main, this::updateForeground);
        protocol = new CompanionProtocol(randomId(), playback);
        try {
            gateway = new CompanionGateway(this, main, this::usable, this::exchange);
            if (!gateway.start()) {
                end(R.string.network_failed);
                return;
            }
        } catch (Exception networkFailed) {
            end(R.string.network_failed);
            return;
        }
        current = this;
        gatt = new CompanionGatt(main, gateway::discovery,
                () -> {
                    main.removeCallbacks(startupTimeout);
                    snapshot = new Snapshot(expiresAt, R.string.session_ready);
                },
                this::finishWithoutDiscovery);
        main.postDelayed(expiry, SESSION_MS);
        if (!gatt.start(this)) {
            finishWithoutDiscovery();
            return;
        }
        main.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS);
    }

    private void finishWithoutDiscovery() {
        main.removeCallbacks(startupTimeout);
        if (gatt != null) gatt.close();
        gatt = null;
        if (gateway != null) snapshot = new Snapshot(expiresAt, R.string.discovery_unavailable);
    }

    private JSONObject exchange(String action, JSONObject payload) throws JSONException {
        if (!usable() || protocol == null) throw new SecurityException("Companion session ended");
        String id = payload.optString("id");
        if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid request ID");
        int opcode = switch (action) {
            case "status" -> CompanionProtocol.STATUS;
            case "receipt" -> CompanionProtocol.LOOKUP;
            case "command" -> {
                if (!(payload.opt("opcode") instanceof Integer value)
                        || value != CompanionProtocol.PLAY && value != CompanionProtocol.STOP) {
                    throw new IllegalArgumentException("Unsupported companion command");
                }
                yield value;
            }
            default -> throw new IllegalArgumentException("Unsupported companion operation");
        };
        if (payload.length() != ("command".equals(action) ? 2 : 1)) {
            throw new IllegalArgumentException("Unexpected companion request fields");
        }
        byte[] input = new byte[CompanionProtocol.ID_LENGTH];
        for (int position = 0; position < input.length; position++) {
            int offset = position * 2;
            input[position] = (byte) ((Character.digit(id.charAt(offset), 16) << 4)
                    | Character.digit(id.charAt(offset + 1), 16));
        }
        JSONObject response = new JSONObject().put("result", hex(protocol.exchange(opcode, input)));
        if (opcode == CompanionProtocol.STATUS) response.put("details", hex(protocol.details(readSessionStatus())));
        return response;
    }

    private CompanionStatus readSessionStatus() {
        Intent battery;
        try {
            // A null receiver queries the platform snapshot without subscribing to updates.
            battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (IllegalStateException | SecurityException unavailable) {
            battery = null;
        }
        long remainingMs = expiresAt - SystemClock.elapsedRealtime();
        if (battery == null || !battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            return CompanionStatus.fromBatteryReading(-1, -1, BatteryManager.BATTERY_STATUS_UNKNOWN, -1, remainingMs);
        }
        return CompanionStatus.fromBatteryReading(
                battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1), remainingMs);
    }

    private boolean usable() {
        return SystemClock.elapsedRealtime() < expiresAt && hasBluetoothPermissions(this);
    }

    private void end(int message) {
        snapshot = new Snapshot(0, message);
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
        if (current == this) current = null;
        if (gatt != null) gatt.close();
        if (gateway != null) gateway.close();
        if (protocol != null) protocol.end();
        if (playback != null) playback.close();
        gatt = null;
        gateway = null;
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
