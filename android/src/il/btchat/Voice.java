package il.btchat;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * נגן אחד להודעות קוליות (מתנגנת הודעה אחת בכל רגע).
 * המיקום מחושב לפי השעון בין עדכוני הנגן, כדי שההתקדמות תזוז חלק ולא בקפיצות.
 */
final class Voice {
    private Voice() { }

    private static MediaPlayer mp;
    static volatile String playing = "";   // מפתח ההודעה שמתנגנת עכשיו (או מושהית)
    static volatile String pending = "";   // הודעה שביקשנו לנגן ועוד לא הגיעה מהמחשב
    static volatile boolean paused;
    static final Set<String> played = new HashSet<>();   // הודעות שכבר נוגנו עד הסוף (נשארות כחולות)
    private static long baseMs, baseAt;
    private static int duration;

    static String key(String msgId, int i) { return msgId + "#" + i; }

    static boolean isPlaying(String key) { return key.equals(playing) && mp != null && !paused; }

    static int positionMs(String key) {
        if (!key.equals(playing) || mp == null) return 0;
        long pos = paused ? baseMs : baseMs + (SystemClock.uptimeMillis() - baseAt);
        return (int) Math.max(0, Math.min(duration > 0 ? duration : pos, pos));
    }

    /** 0..1 — כמה מההודעה כבר התנגן. */
    static float progress(String key) {
        if (key.equals(playing) && mp != null && duration > 0) return Math.min(1f, positionMs(key) / (float) duration);
        return played.contains(key) ? 1f : 0f;
    }

    private static void sync() {
        try { baseMs = mp.getCurrentPosition(); } catch (Exception e) { baseMs = 0; }
        baseAt = SystemClock.uptimeMillis();
    }

    /** לחיצה על נגן: התחלה / השהיה / המשך. */
    static void toggle(Context c, String key, File f) {
        if (key.equals(playing) && mp != null) {
            try {
                if (paused) { mp.start(); paused = false; sync(); }
                else { sync(); mp.pause(); paused = true; }
            } catch (Exception e) { stop(); }
            return;
        }
        play(c, key, f);
    }

    static void play(Context c, String key, File f) {
        stop();
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(f.getPath());
            p.prepare();
            p.setOnCompletionListener(x -> { played.add(playing); stop(); });
            duration = p.getDuration();
            p.start();
            mp = p;
            playing = key;
            paused = false;
            played.remove(key);
            sync();
        } catch (Exception e) {
            stop();
            android.widget.Toast.makeText(c, "לא ניתן לנגן את ההודעה", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static void seek(String key, float frac) {
        if (!key.equals(playing) || mp == null) return;
        try {
            mp.seekTo((int) (duration * Math.max(0f, Math.min(1f, frac))));
            baseMs = (long) (duration * Math.max(0f, Math.min(1f, frac)));
            baseAt = SystemClock.uptimeMillis();
        } catch (Exception ignored) { }
    }

    static void stop() {
        MediaPlayer p = mp;
        mp = null;
        playing = "";
        paused = false;
        if (p != null) try { p.release(); } catch (Exception ignored) { }
    }
}
