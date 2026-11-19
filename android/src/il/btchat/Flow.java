package il.btchat;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

/** פריסה שעוטפת שורות (לשבבי התגובות). מימין לשמאל. */
final class Flow extends ViewGroup {
    private final int gap;

    Flow(Context c) {
        super(c);
        gap = Th.dp(c, 6);
    }

    private boolean rtl() {
        return Build.VERSION.SDK_INT < 17 || getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int max = MeasureSpec.getSize(ws);
        if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) max = Integer.MAX_VALUE;
        int x = 0, y = 0, lineH = 0, widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            // גודל קבוע שנקבע לילד (למשל כפתור 28dp) — בדיוק; אחרת לפי התוכן
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int cw = lp != null && lp.width >= 0 ? MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
            int ch = lp != null && lp.height >= 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            v.measure(cw, ch);
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && x + w > max) { y += lineH + gap; x = 0; lineH = 0; }
            x += w + gap;
            widest = Math.max(widest, x - gap);
            lineH = Math.max(lineH, h);
        }
        setMeasuredDimension(MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY ? max : widest, y + lineH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, x = 0, y = 0, lineH = 0;
        boolean rtl = rtl();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && x + w > width) { y += lineH + gap; x = 0; lineH = 0; }
            int left = rtl ? width - x - w : x;
            v.layout(left, y, left + w, y + h);
            x += w + gap;
            lineH = Math.max(lineH, h);
        }
    }
}
