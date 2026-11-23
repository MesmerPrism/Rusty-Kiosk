package io.github.mesmerprism.queststatusaudioexample;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import java.util.function.Consumer;

/** Owns only the app's short, bundled clip and audio focus request. */
final class TestClipPlayback implements CompanionProtocol.Playback {
    private final Context context;
    private final AudioManager audio;
    private final Handler main;
    private final Consumer<Boolean> setForegroundPlayback;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private int state = CompanionProtocol.IDLE;

    TestClipPlayback(Context context, Handler main, Consumer<Boolean> setForegroundPlayback) {
        this.context = context;
        this.audio = context.getSystemService(AudioManager.class);
        this.main = main;
        this.setForegroundPlayback = setForegroundPlayback;
    }

    @Override public int read() {
        if (state != CompanionProtocol.PLAYING) return state;
        try {
            return player != null && player.isPlaying() ? state : CompanionProtocol.UNAVAILABLE;
        } catch (IllegalStateException unavailable) {
            return CompanionProtocol.UNAVAILABLE;
        }
    }

    @Override public boolean play() {
        if (read() == CompanionProtocol.PLAYING) return true;
        release();
        if (audio == null) return false;

        try {
            MediaPlayer next = MediaPlayer.create(context, R.raw.test_clip, attributes,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (next == null) return false;
            player = next;

            AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change -> {
                        if (player == next && (change == AudioManager.AUDIOFOCUS_LOSS ||
                                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                            stop();
                        }
                    }, main)
                    .build();
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                release();
                return false;
            }
            focus = request;
            next.setOnCompletionListener(completed -> {
                if (player == completed) settle(CompanionProtocol.COMPLETED);
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) settle(CompanionProtocol.UNAVAILABLE);
                return true;
            });
            next.setVolume(0.45f, 0.45f);
            setForegroundPlayback.accept(true);
            next.start();
            if (next.isPlaying()) {
                state = CompanionProtocol.PLAYING;
                return true;
            }
        } catch (IllegalStateException | SecurityException unavailable) {
            // Settle below even when Android fails during the side effect.
        }
        settle(CompanionProtocol.UNAVAILABLE);
        return false;
    }

    @Override public boolean stop() {
        boolean observed = player == null;
        try {
            if (player != null) {
                player.stop();
                observed = !player.isPlaying();
            }
        } catch (IllegalStateException unavailable) {
            observed = false;
        }
        settle(observed ? CompanionProtocol.STOPPED : CompanionProtocol.UNAVAILABLE);
        return observed;
    }

    void close() {
        release();
    }

    private void settle(int result) {
        state = result;
        release();
        try { setForegroundPlayback.accept(false); }
        catch (IllegalStateException | SecurityException ignored) { }
    }

    private void release() {
        MediaPlayer releasing = player;
        player = null;
        AudioFocusRequest oldFocus = focus;
        focus = null;
        try {
            if (releasing != null) releasing.release();
        } finally {
            if (oldFocus != null) audio.abandonAudioFocusRequest(oldFocus);
        }
    }
}
