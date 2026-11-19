package il.btchat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;

/**
 * הגל החי בזמן הקלטה: כל דגימה של עוצמת הקול נכנסת מימין ונגללת שמאלה, בתנועה חלקה.
 * בשקט — נקודות קטנות, כמו בגוגל צ'אט.
 */
final class LiveWave extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final ArrayList<Float> levels = new ArrayList<>();
    private long lastAt;
    private static final long STEP_MS = 60;

    LiveWave(Context c) { super(c); }

    void clear() { levels.clear(); invalidate(); }

    /** amplitude: הערך מ-MediaRecorder.getMaxAmplitude (0..32767). */
    void add(int amplitude) {
        float v = (float) Math.sqrt(Math.max(0, amplitude) / 32767f) * 1.6f;
        levels.add(Math.min(1f, v));
        if (levels.size() > 400) levels.remove(0);
        lastAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cy = h / 2f;
        float step = Th.dp(getContext(), 3.6f), barW = Th.dp(getContext(), 2);
        // הזזה חלקה בין דגימות: הגל זז קצת שמאלה עד שהדגימה הבאה מגיעה
        float frac = Math.min(1f, (SystemClock.uptimeMillis() - lastAt) / (float) STEP_MS);
        float shift = frac * step;
        p.setColor(Th.PRIMARY);
        int n = levels.size();
        for (int i = 0; i < n; i++) {
            float x = w - (n - i) * step - shift + step;
            if (x < -step) continue;
            float v = levels.get(i);
            if (v < 0.12f) {
                c.drawCircle(x + barW / 2, cy, Th.dp(getContext(), 1), p);
            } else {
                float bh = Math.max(Th.dp(getContext(), 5), v * h * 0.8f);
                r.set(x, cy - bh / 2, x + barW, cy + bh / 2);
                c.drawRoundRect(r, barW / 2, barW / 2, p);
            }
        }
        if (n > 0) postInvalidateOnAnimation();
    }
}
