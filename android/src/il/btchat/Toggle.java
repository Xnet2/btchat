package il.btchat;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** מתג בסגנון Material 3 (נראה אותו דבר בכל גרסת אנדרואיד). */
final class Toggle extends View {
    interface OnChange { void changed(boolean on); }

    private boolean on;
    private float pos;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private OnChange listener;

    Toggle(Context c, boolean initial) {
        super(c);
        on = initial;
        pos = on ? 1f : 0f;
        setClickable(true);
        setOnClickListener(v -> setOn(!on, true));
    }

    void setListener(OnChange l) { listener = l; }
    boolean isOn() { return on; }

    void setOn(boolean value, boolean notify) {
        if (value == on) return;
        on = value;
        ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
        a.setDuration(160);
        a.addUpdateListener(an -> { pos = (Float) an.getAnimatedValue(); invalidate(); });
        a.start();
        if (notify && listener != null) listener.changed(on);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Th.dp(getContext(), 52), Th.dp(getContext(), 32));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float stroke = Th.dp(getContext(), 2);
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        // מסילה: מלאה בצבע הראשי כשפועל, מסגרת כשכבוי
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(Th.SURFACE2, Th.PRIMARY, pos));
        c.drawRoundRect(rect, h / 2, h / 2, paint);
        if (pos < 1f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(Th.alpha(Th.HINT, (int) (255 * (1 - pos))));
            c.drawRoundRect(rect, h / 2, h / 2, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        float rOff = h * 0.25f, rOn = h * 0.38f;
        float r = rOff + (rOn - rOff) * pos;
        float pad = h / 2;
        // במצב "פועל" העיגול זז שמאלה (כמו במתגים בעברית)
        float cx = (w - pad) - pos * (w - 2 * pad);
        paint.setColor(blend(Th.HINT, Th.ON_PRIMARY, pos));
        c.drawCircle(cx, h / 2, r, paint);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
