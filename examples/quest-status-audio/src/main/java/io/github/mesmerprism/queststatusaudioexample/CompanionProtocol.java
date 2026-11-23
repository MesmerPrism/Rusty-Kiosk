package io.github.mesmerprism.queststatusaudioexample;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** One wearer-start session. All operations run on the GATT owner's thread. */
final class CompanionProtocol {
    static final int ID_LENGTH = 16;
    static final int IDLE = 0;
    static final int PLAYING = 1;
    static final int STOPPED = 2;
    static final int COMPLETED = 3;
    static final int UNAVAILABLE = 4;

    private static final int AUTH = 0;
    private static final int STATUS = 1;
    private static final int PLAY = 2;
    private static final int STOP = 3;
    private static final int LOOKUP = 4;
    private static final int OK = 0;
    private static final int REJECTED = 1;
    private static final int UNKNOWN = 2;
    private static final int FAILED = 3;
    private static final int MAX_AUTH_FAILURES = 5;
    private static final int MAX_EFFECTS = 64;
    private static final byte[] ZERO_ID = new byte[ID_LENGTH];

    interface Playback {
        int read();
        boolean play();
        boolean stop();
    }

    private record RequestId(long upper, long lower) {
        static RequestId from(byte[] bytes) {
            ByteBuffer input = ByteBuffer.wrap(bytes);
            return new RequestId(input.getLong(), input.getLong());
        }
    }

    private final byte[] code;
    private final byte[] epoch;
    private final Playback playback;
    private final Map<RequestId, byte[]> effects = new HashMap<>();
    private boolean authenticated;
    private int authFailures;
    private byte[] reply;

    CompanionProtocol(byte[] code, byte[] epoch, Playback playback) {
        if (code.length != ID_LENGTH || epoch.length != ID_LENGTH) {
            throw new IllegalArgumentException("Invalid session identifier length");
        }
        this.code = code.clone();
        this.epoch = epoch.clone();
        this.playback = playback;
    }

    void resetPeer() {
        authenticated = false;
        reply = null;
    }

    void end() {
        resetPeer();
        authFailures = MAX_AUTH_FAILURES;
        Arrays.fill(code, (byte) 0);
        effects.clear();
    }

    byte[] read() {
        return reply == null ? null : reply.clone();
    }

    byte[] readSessionStatus(Supplier<CompanionStatus> sample) {
        return authenticated ? sample.get().encode(epoch) : null;
    }

    boolean write(byte[] packet) {
        if (packet.length != 2 + ID_LENGTH || packet[0] != 1) return false;
        int opcode = packet[1] & 0xff;
        if (opcode > LOOKUP || (opcode != AUTH && !authenticated)) return false;
        byte[] id = Arrays.copyOfRange(packet, 2, packet.length);
        if (opcode == AUTH) {
            authenticated = authFailures < MAX_AUTH_FAILURES && MessageDigest.isEqual(id, code);
            Arrays.fill(id, (byte) 0);
            if (!authenticated && authFailures < MAX_AUTH_FAILURES) ++authFailures;
            reply = frame(AUTH, authenticated ? OK : REJECTED, UNAVAILABLE,
                    authenticated ? epoch : ZERO_ID);
            return true;
        }
        if (Arrays.equals(id, ZERO_ID)) return false;
        if (opcode == STATUS) {
            reply = frame(STATUS, OK, playback.read(), id);
            return true;
        }

        RequestId key = RequestId.from(id);
        byte[] original = effects.get(key);
        if (opcode == LOOKUP) {
            reply = original == null ? frame(LOOKUP, UNKNOWN, playback.read(), id) : original;
        } else if (original != null) {
            reply = original[1] == opcode ? original : frame(opcode, REJECTED, playback.read(), id);
        } else if (effects.size() >= MAX_EFFECTS) {
            reply = frame(opcode, REJECTED, playback.read(), id);
        } else {
            byte[] uncertain = frame(opcode, UNKNOWN, UNAVAILABLE, id);
            effects.put(key, uncertain);
            try {
                boolean applied = opcode == PLAY ? playback.play() : playback.stop();
                reply = frame(opcode, applied ? OK : FAILED, playback.read(), id);
            } catch (RuntimeException effectUnknown) {
                reply = uncertain;
            }
            effects.put(key, reply);
        }
        return true;
    }

    private static byte[] frame(int opcode, int result, int state, byte[] id) {
        byte[] frame = new byte[4 + ID_LENGTH];
        frame[0] = 1;
        frame[1] = (byte) opcode;
        frame[2] = (byte) result;
        frame[3] = (byte) state;
        System.arraycopy(id, 0, frame, 4, ID_LENGTH);
        return frame;
    }
}
