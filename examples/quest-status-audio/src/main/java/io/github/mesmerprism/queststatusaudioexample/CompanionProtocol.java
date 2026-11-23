package io.github.mesmerprism.queststatusaudioexample;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Wearer-session effect journal. Only the authenticated gateway may call it. */
final class CompanionProtocol {
    static final int ID_LENGTH = 16;
    static final int IDLE = 0;
    static final int PLAYING = 1;
    static final int STOPPED = 2;
    static final int COMPLETED = 3;
    static final int UNAVAILABLE = 4;

    static final int STATUS = 1;
    static final int PLAY = 2;
    static final int STOP = 3;
    static final int LOOKUP = 4;

    private static final int OK = 0;
    private static final int REJECTED = 1;
    private static final int UNKNOWN = 2;
    private static final int FAILED = 3;
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

    private final byte[] epoch;
    private final Playback playback;
    private final Map<RequestId, byte[]> effects = new HashMap<>();
    private boolean ended;

    CompanionProtocol(byte[] epoch, Playback playback) {
        if (epoch.length != ID_LENGTH || Arrays.equals(epoch, ZERO_ID)) {
            throw new IllegalArgumentException("Invalid session identity");
        }
        this.epoch = epoch.clone();
        this.playback = playback;
    }

    void end() {
        ended = true;
        effects.clear();
    }

    byte[] details(CompanionStatus sample) {
        requireLive();
        return sample.encode(epoch);
    }

    byte[] exchange(int opcode, byte[] id) {
        requireLive();
        if (opcode < STATUS || opcode > LOOKUP || id.length != ID_LENGTH || Arrays.equals(id, ZERO_ID)) {
            throw new IllegalArgumentException("Invalid companion operation");
        }
        if (opcode == STATUS) return frame(STATUS, OK, playback.read(), id);

        RequestId key = RequestId.from(id);
        byte[] original = effects.get(key);
        if (opcode == LOOKUP) {
            return original == null ? frame(LOOKUP, UNKNOWN, playback.read(), id) : original.clone();
        }
        if (original != null) {
            return original[1] == opcode ? original.clone() : frame(opcode, REJECTED, playback.read(), id);
        }
        if (effects.size() >= MAX_EFFECTS) return frame(opcode, REJECTED, playback.read(), id);

        byte[] uncertain = frame(opcode, UNKNOWN, UNAVAILABLE, id);
        effects.put(key, uncertain);
        try {
            boolean applied = opcode == PLAY ? playback.play() : playback.stop();
            byte[] result = frame(opcode, applied ? OK : FAILED, playback.read(), id);
            effects.put(key, result);
            return result.clone();
        } catch (RuntimeException effectUnknown) {
            return uncertain.clone();
        }
    }

    private void requireLive() {
        if (ended) throw new IllegalStateException("Companion session ended");
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
