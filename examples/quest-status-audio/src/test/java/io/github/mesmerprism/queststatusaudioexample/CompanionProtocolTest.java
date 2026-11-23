package io.github.mesmerprism.queststatusaudioexample;

import static android.os.BatteryManager.BATTERY_PLUGGED_USB;
import static android.os.BatteryManager.BATTERY_STATUS_CHARGING;
import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public final class CompanionProtocolTest {
    private final byte[] epoch = id(42);
    private final FakePlayback playback = new FakePlayback();
    private final CompanionProtocol protocol = new CompanionProtocol(epoch, playback);

    @Test public void rejectsInvalidOperationsBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> protocol.exchange(0, id(1)));
        assertThrows(IllegalArgumentException.class, () -> protocol.exchange(5, id(1)));
        assertThrows(IllegalArgumentException.class, () -> protocol.exchange(2, id(0)));
        assertThrows(IllegalArgumentException.class, () -> protocol.exchange(2, new byte[15]));
        assertEquals(0, playback.plays);
    }

    @Test public void looksUpTheOriginalResultWithoutReplayingIt() {
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.exchange(2, id(2)));
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.exchange(4, id(2)));
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.exchange(2, id(2)));
        assertArrayEquals(reply(3, 1, 1, id(2)), protocol.exchange(3, id(2)));
        assertEquals(1, playback.plays);
        assertEquals(0, playback.stops);
        assertArrayEquals(reply(4, 2, 1, id(9)), protocol.exchange(4, id(9)));
        assertArrayEquals(reply(3, 0, 2, id(3)), protocol.exchange(3, id(3)));
        assertEquals(1, playback.stops);
    }

    @Test public void retainsAnUnknownResultAfterAnEffectThrows() {
        playback.throwAfterPlay = true;
        assertArrayEquals(reply(2, 2, 4, id(1)), protocol.exchange(2, id(1)));
        playback.throwAfterPlay = false;
        assertArrayEquals(reply(2, 2, 4, id(1)), protocol.exchange(4, id(1)));
        assertArrayEquals(reply(2, 2, 4, id(1)), protocol.exchange(2, id(1)));
        assertEquals(1, playback.plays);
    }

    @Test public void rejectsNewEffectsRatherThanForgettingOldIds() {
        for (int i = 1; i <= 64; ++i) {
            assertEquals(0, protocol.exchange(2, id(i))[2]);
        }
        assertArrayEquals(reply(2, 1, 1, id(65)), protocol.exchange(2, id(65)));
        assertArrayEquals(reply(2, 0, 1, id(1)), protocol.exchange(4, id(1)));
        assertArrayEquals(reply(1, 0, 1, id(66)), protocol.exchange(1, id(66)));
        assertEquals(64, playback.plays);
    }

    @Test public void reportsFailedPlaybackAndCanStartAnotherRequest() {
        playback.canPlay = false;
        assertArrayEquals(reply(2, 3, 0, id(1)), protocol.exchange(2, id(1)));
        playback.canPlay = true;
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.exchange(2, id(2)));
    }

    @Test public void statusKeepsTheSameSessionMarkerUntilTheOwnerClosesIt() {
        CompanionStatus status = CompanionStatus.fromBatteryReading(
                87, 100, BATTERY_STATUS_CHARGING, BATTERY_PLUGGED_USB, 300_000);
        byte[] frame = Arrays.copyOf(new byte[] {1, 87, 2, 1, 44}, 21);
        System.arraycopy(epoch, 0, frame, 5, epoch.length);
        assertArrayEquals(frame, protocol.details(status));
        assertArrayEquals(reply(1, 0, 0, id(1)), protocol.exchange(1, id(1)));
        protocol.end();
        assertThrows(IllegalStateException.class, () -> protocol.details(status));
        assertThrows(IllegalStateException.class, () -> protocol.exchange(4, id(1)));
    }

    private static byte[] reply(int opcode, int result, int state, byte[] id) {
        byte[] packet = Arrays.copyOf(new byte[] {1, (byte) opcode, (byte) result, (byte) state}, 20);
        System.arraycopy(id, 0, packet, 4, id.length);
        return packet;
    }

    private static byte[] id(int value) {
        byte[] bytes = new byte[16];
        bytes[15] = (byte) value;
        return bytes;
    }

    private static final class FakePlayback implements CompanionProtocol.Playback {
        int state, plays, stops;
        boolean canPlay = true, throwAfterPlay;

        @Override public int read() { return state; }

        @Override public boolean play() {
            ++plays;
            if (canPlay) state = 1;
            if (throwAfterPlay) throw new IllegalStateException("Effect became unknown");
            return canPlay;
        }

        @Override public boolean stop() {
            ++stops;
            state = 2;
            return true;
        }
    }
}
