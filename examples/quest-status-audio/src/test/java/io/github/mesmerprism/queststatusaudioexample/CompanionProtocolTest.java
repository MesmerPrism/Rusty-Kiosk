package io.github.mesmerprism.queststatusaudioexample;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public final class CompanionProtocolTest {
    private final byte[] code = fill(0x12);
    private final byte[] epoch = fill(0x34);
    private final FakePlayback playback = new FakePlayback();
    private final CompanionProtocol protocol = new CompanionProtocol(code, epoch, playback);

    @Test public void authenticatesOnlyExactPacketsAndGatesReads() {
        assertNull(protocol.read());
        assertFalse(protocol.write(packet(1, id(1))));
        assertFalse(protocol.write(packet(2, id(2))));
        assertFalse(protocol.write(Arrays.copyOf(packet(0, code), 17)));
        byte[] wrongVersion = packet(0, code);
        wrongVersion[0] = 2;
        assertFalse(protocol.write(wrongVersion));
        assertEquals(0, playback.plays);
        assertTrue(protocol.write(packet(0, fill(0x79))));
        assertArrayEquals(reply(0, 1, 4, new byte[16]), protocol.read());
        authenticate();
        assertFalse(protocol.write(packet(2, new byte[16])));
        assertTrue(protocol.write(packet(1, id(1))));
        assertArrayEquals(reply(1, 0, 0, id(1)), protocol.read());
        assertFalse(protocol.write(packet(5, id(1))));
    }

    @Test public void reconnectRequiresAuthAndCanLookUpWithoutReplaying() {
        authenticate();
        assertTrue(protocol.write(packet(2, id(2))));
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.read());
        protocol.resetPeer();
        assertNull(protocol.read());
        assertFalse(protocol.write(packet(4, id(2))));
        authenticate();
        assertTrue(protocol.write(packet(4, id(2))));
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.read());
        assertTrue(protocol.write(packet(2, id(2))));
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.read());
        assertTrue(protocol.write(packet(3, id(2))));
        assertArrayEquals(reply(3, 1, 1, id(2)), protocol.read());
        assertEquals(1, playback.plays);
        assertEquals(0, playback.stops);
        assertTrue(protocol.write(packet(4, id(9))));
        assertArrayEquals(reply(4, 2, 1, id(9)), protocol.read());
    }

    @Test public void keepsFailureBudgetAcrossConnectionsUntilNewSession() {
        for (int i = 0; i < 5; ++i) {
            assertTrue(protocol.write(packet(0, fill(0x78))));
            assertArrayEquals(reply(0, 1, 4, new byte[16]), protocol.read());
            protocol.resetPeer();
        }
        assertTrue(protocol.write(packet(0, code)));
        assertArrayEquals(reply(0, 1, 4, new byte[16]), protocol.read());
        assertFalse(protocol.write(packet(3, id(3))));
        assertEquals(0, playback.stops);
    }

    @Test public void reportsOriginalEffectAndCurrentPlaybackSeparately() {
        authenticate();
        playback.canPlay = false;
        protocol.write(packet(2, id(1)));
        assertArrayEquals(reply(2, 3, 0, id(1)), protocol.read());
        playback.canPlay = true;
        protocol.write(packet(2, id(1)));
        assertArrayEquals(reply(2, 3, 0, id(1)), protocol.read());
        protocol.write(packet(2, id(2)));
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.read());
        playback.state = 3;
        protocol.write(packet(1, id(3)));
        assertArrayEquals(reply(1, 0, 3, id(3)), protocol.read());
        protocol.write(packet(4, id(2)));
        assertArrayEquals(reply(2, 0, 1, id(2)), protocol.read());
        protocol.write(packet(3, id(4)));
        assertArrayEquals(reply(3, 0, 2, id(4)), protocol.read());
        assertEquals(2, playback.plays);
        assertEquals(1, playback.stops);
    }

    @Test public void rejectsNewEffectsRatherThanForgettingOldIds() {
        authenticate();
        for (int i = 1; i <= 64; ++i) {
            assertTrue(protocol.write(packet(2, id(i))));
            assertEquals(0, protocol.read()[2]);
        }
        assertTrue(protocol.write(packet(2, id(65))));
        assertArrayEquals(reply(2, 1, 1, id(65)), protocol.read());
        assertTrue(protocol.write(packet(2, id(1))));
        assertArrayEquals(reply(2, 0, 1, id(1)), protocol.read());
        assertEquals(64, playback.plays);
        assertTrue(protocol.write(packet(1, id(66))));
        assertArrayEquals(reply(1, 0, 1, id(66)), protocol.read());
    }

    @Test public void effectThrowCannotReplayTheSameRequest() {
        authenticate();
        playback.throwAfterPlay = true;
        assertTrue(protocol.write(packet(2, id(1))));
        assertArrayEquals(reply(2, 2, 4, id(1)), protocol.read());
        playback.throwAfterPlay = false;
        assertTrue(protocol.write(packet(2, id(1))));
        assertArrayEquals(reply(2, 2, 4, id(1)), protocol.read());
        assertEquals(1, playback.plays);
    }

    private void authenticate() {
        assertTrue(protocol.write(packet(0, code)));
        assertArrayEquals(reply(0, 0, 4, epoch), protocol.read());
    }

    private static byte[] packet(int opcode, byte[] id) {
        byte[] packet = new byte[18];
        packet[0] = 1;
        packet[1] = (byte) opcode;
        System.arraycopy(id, 0, packet, 2, 16);
        return packet;
    }

    private static byte[] reply(int opcode, int result, int state, byte[] id) {
        byte[] packet = new byte[20];
        packet[0] = 1;
        packet[1] = (byte) opcode;
        packet[2] = (byte) result;
        packet[3] = (byte) state;
        System.arraycopy(id, 0, packet, 4, 16);
        return packet;
    }

    private static byte[] fill(int value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, (byte) value);
        return bytes;
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
