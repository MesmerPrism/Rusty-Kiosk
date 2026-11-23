package io.github.mesmerprism.queststatusaudioexample;

import android.os.BatteryManager;
import java.nio.ByteBuffer;

/** One read-only battery and wearer-start session snapshot. */
record CompanionStatus(int battery, int charging, int remainingSeconds) {
    static final int MAX_REMAINING_SECONDS = 900;
    private static final int UNKNOWN_BATTERY = 255;
    private static final int UNKNOWN_CHARGING = 0;
    private static final int ON_BATTERY = 1;
    private static final int CHARGING = 2;
    private static final int FULL = 3;

    static CompanionStatus fromBatteryReading(int level, int scale, int status, int plugged, long remainingMs) {
        int battery = scale > 0 && level >= 0 && level <= scale
                ? (int) Math.round(100.0 * level / scale) : UNKNOWN_BATTERY;
        int charging = switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING -> CHARGING;
            case BatteryManager.BATTERY_STATUS_FULL -> FULL;
            case BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                plugged == 0 ? ON_BATTERY : UNKNOWN_CHARGING;
            default -> UNKNOWN_CHARGING;
        };
        long boundedMs = Math.max(0, Math.min(MAX_REMAINING_SECONDS * 1000L, remainingMs));
        // A still-live session can have less than one second left. Zero means expired.
        return new CompanionStatus(battery, charging, (int) ((boundedMs + 999) / 1000));
    }

    byte[] encode(byte[] epoch) {
        return ByteBuffer.allocate(5 + CompanionProtocol.ID_LENGTH)
                .put((byte) 1)
                .put((byte) battery)
                .put((byte) charging)
                .putShort((short) remainingSeconds)
                .put(epoch)
                .array();
    }
}
