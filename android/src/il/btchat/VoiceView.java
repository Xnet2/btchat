package il.btchat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.Locale;

/**
 * הודעה קולית, כמו בגוגל צ'אט: כפתור הפעלה כחול מימין (בלי עיגול מלא), גל קול דק באמצע
 * (בשקט — נקודות קטנות), והזמן משמאל. בזמן ניגון הגל מתמלא בכחול משמאל לימין, חלק ובלי קפיצות.
 */
final class VoiceView extends View {
    interface Loader { void load(); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private String key = "";
    private File file;
    private int[] wave = new int[0];
    private long durMs;
    private boolean loading, failed;
    private Loader loader;

    VoiceView(Context c) { super(c); }

    void set(String key, File file, int[] wave, long durMs, boolean loading, boolean failed, boolean mine, Loader loader) {
        this.key = key;
        this.file = file;
        this.wave = wave;
        this.durMs = durMs;
        this.loading = loading;
        this.failed = failed;
        this.loader = loader;
        invalidate();
    }

    private float dp(float v) { return Th.dp(getContext(), v); }

    @Override
    protected void onMeasure(int ws, int hs) {
        int max = MeasureSpec.getSize(ws);
        int want = (int) dp(250);
        setMeasuredDimension(MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? want : Math.min(want, max), (int) dp(44));
    }

    // פריסה קבועה (כמו בגוגל צ'אט בעברית): זמן משמאל, גל באמצע, כפתור מימין
    private float btnCx() { return getWidth() - dp(20); }
    private float waveLeft() { return dp(40); }
    private float waveRight() { return getWidth() - dp(44); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float x = e.getX();
        boolean onWave = x > waveLeft() - dp(4) && x < waveRight() + dp(2);
        if (onWave && key.equals(Voice.playing)) {
            Voice.seek(key, (x - waveLeft()) / Math.max(1f, waveRight() - waveLeft()));
        } else if (file != null && file.exists()) {
            Voice.toggle(getContext(), key, file);
        } else if (loader != null) {
            Voice.pending = key;
            loader.load();
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), cy = h / 2f;
        int accent = Th.PRIMARY, dim = Th.alpha(Th.SUB, 0xAA);
        boolean active = key.equals(Voice.playing);
        boolean playing = Voice.isPlaying(key);
        float prog = Voice.progress(key);
        boolean blue = active || prog > 0;

        // כפתור: משולש / השהיה בכחול; עיגול בהיר רק בזמן ניגון
        float bx = btnCx();
        p.setStyle(Paint.Style.FILL);
        if (playing) { p.setColor(Th.alpha(accent, 0x22)); c.drawCircle(bx, cy, dp(18), p); }
        p.setColor(failed ? Th.RED : accent);
        boolean waiting = loading || Voice.pending.equals(key) && (file == null || !file.exists());
        if (waiting) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float rr = dp(8);
            long t = System.currentTimeMillis() % 900;
            c.drawArc(new RectF(bx - rr, cy - rr, bx + rr, cy + rr), t * 0.4f, 270, false, p);
            postInvalidateOnAnimation();
        } else if (playing) {
            float bw = dp(3), bh = dp(13), g = dp(2.5f);
            c.drawRoundRect(new RectF(bx - g - bw, cy - bh / 2, bx - g, cy + bh / 2), dp(1), dp(1), p);
            c.drawRoundRect(new RectF(bx + g, cy - bh / 2, bx + g + bw, cy + bh / 2), dp(1), dp(1), p);
        } else {
            Path tri = new Path();
            float s = dp(7.5f);
            tri.moveTo(bx - s * 0.75f, cy - s);
            tri.lineTo(bx + s, cy);
            tri.lineTo(bx - s * 0.75f, cy + s);
            tri.close();
            c.drawPath(tri, p);
        }

        // הזמן משמאל: משך (אפור), או הזמן שעבר (כחול)
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(13));
        p.setColor(failed ? Th.RED : blue ? accent : Th.SUB);
        long shown = active ? Voice.positionMs(key) : durMs;
        c.drawText(failed ? "!" : fmt(shown), dp(2), cy + dp(4.5f), p);

        // גל קול: עמודות דקות, בשקט נקודות; קודם הכול באפור, ואז החלק שהתנגן בכחול (חיתוך רציף)
        float left = waveLeft(), right = waveRight();
        float step = dp(3.6f), barW = dp(2);
        int n = Math.max(10, (int) ((right - left) / step));
        int max = 1;
        for (int v : wave) max = Math.max(max, v);
        float cut = left + (right - left) * prog;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                if (prog <= 0) break;
                c.save();
                c.clipRect(left - dp(2), 0, cut, h);
            }
            p.setColor(pass == 0 ? dim : accent);
            for (int i = 0; i < n; i++) {
                float v = wave.length > 0 ? wave[Math.min(wave.length - 1, i * wave.length / n)] / (float) max : 0.3f;
                float x = left + i * step + (step - barW) / 2;
                if (v < 0.14f) {
                    c.drawCircle(x + barW / 2, cy, dp(1), p);
                } else {
                    float bh = Math.max(dp(5), v * h * 0.7f);
                    r.set(x, cy - bh / 2, x + barW, cy + bh / 2);
                    c.drawRoundRect(r, barW / 2, barW / 2, p);
                }
            }
            if (pass == 1) c.restore();
        }
        if (playing) postInvalidateOnAnimation();
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
