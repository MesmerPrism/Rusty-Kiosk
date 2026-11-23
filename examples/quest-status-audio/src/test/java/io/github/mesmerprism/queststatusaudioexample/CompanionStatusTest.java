package io.github.mesmerprism.queststatusaudioexample;

import static android.os.BatteryManager.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CompanionStatusTest {
    @Test public void scalesLevelAndOnlyReportsKnownChargingStates() {
        assertEquals(new CompanionStatus(50, 1, 10),
                CompanionStatus.fromBatteryReading(6, 12, BATTERY_STATUS_DISCHARGING, 0, 10_000));
        assertEquals(new CompanionStatus(67, 2, 10),
                CompanionStatus.fromBatteryReading(2, 3, BATTERY_STATUS_CHARGING, BATTERY_PLUGGED_USB, 10_000));
        assertEquals(new CompanionStatus(100, 3, 10),
                CompanionStatus.fromBatteryReading(1, 1, BATTERY_STATUS_FULL, BATTERY_PLUGGED_AC, 10_000));
        assertEquals(new CompanionStatus(60, 0, 10),
                CompanionStatus.fromBatteryReading(60, 100, BATTERY_STATUS_NOT_CHARGING, BATTERY_PLUGGED_AC, 10_000));
    }

    @Test public void unavailableOrInvalidBatteryFieldsStayUnknown() {
        assertEquals(new CompanionStatus(255, 0, 45),
                CompanionStatus.fromBatteryReading(-1, -1, BATTERY_STATUS_UNKNOWN, -1, 45_000));
        assertEquals(255, CompanionStatus.fromBatteryReading(5, 0, BATTERY_STATUS_CHARGING, 1, 1000).battery());
        assertEquals(255, CompanionStatus.fromBatteryReading(6, 5, BATTERY_STATUS_CHARGING, 1, 1000).battery());
        assertEquals(0, CompanionStatus.fromBatteryReading(25, 100, BATTERY_STATUS_DISCHARGING, -1, 1000).charging());
    }

    @Test public void durationIsBoundedAndOnlyZeroAtExpiry() {
        assertEquals(900, remainingSeconds(Long.MAX_VALUE));
        assertEquals(900, remainingSeconds(900_000));
        assertEquals(2, remainingSeconds(1001));
        assertEquals(1, remainingSeconds(1));
        assertEquals(0, remainingSeconds(0));
        assertEquals(0, remainingSeconds(-1));
    }

    private static int remainingSeconds(long remainingMs) {
        return CompanionStatus.fromBatteryReading(-1, -1, BATTERY_STATUS_UNKNOWN, -1, remainingMs).remainingSeconds();
    }
}
