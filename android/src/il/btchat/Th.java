package il.btchat;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** הצבעים (בסגנון Material 3 של גוגל צ'אט, בהיר וכהה) וכלי עזר לבניית המסכים בקוד. */
final class Th {
    static boolean dark;
    static int BG, SURFACE, SURFACE2, TEXT, SUB, HINT, LINE, OUTLINE;
    static int PRIMARY, ON_PRIMARY, PRIMARY_CONT, ON_PRIMARY_CONT, BUBBLE_IN, ON_BUBBLE_IN;
    static int GREEN, RED, AMBER, CHIP_ME, LINK;
    static int HOME_BG, CARD, NAV, NAV_SEL, QUOTE_BG, PENDING;

    /** טוען את ערכת הצבעים לפי ההגדרה (מערכת / בהיר / כהה). */
    static void load(Context c) {
        int mode = Prefs.theme(c);
        boolean sysDark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        dark = mode == 2 || (mode == 0 && sysDark);
        if (!dark) {
            BG = 0xFFFFFFFF; SURFACE = 0xFFF0F4F9; SURFACE2 = 0xFFE9EEF6;
            TEXT = 0xFF1F1F1F; SUB = 0xFF444746; HINT = 0xFF747775; LINE = 0xFFE1E3E1; OUTLINE = 0xFFC4C7C5;
            PRIMARY = 0xFF0B57D0; ON_PRIMARY = 0xFFFFFFFF; PRIMARY_CONT = 0xFFD3E3FD; ON_PRIMARY_CONT = 0xFF041E49;
            BUBBLE_IN = 0xFFF0F4F9; ON_BUBBLE_IN = 0xFF1F1F1F;
            GREEN = 0xFF146C2E; RED = 0xFFB3261E; AMBER = 0xFFB06000; CHIP_ME = 0xFFD3E3FD; LINK = 0xFF0B57D0;
            HOME_BG = 0xFFF0F4F9; CARD = 0xFFFFFFFF; NAV = 0xFFFFFFFF; NAV_SEL = 0xFFD3E3FD; QUOTE_BG = 0xFFFFFFFF; PENDING = 0xFFE9F1FE;
        } else {
            BG = 0xFF131314; SURFACE = 0xFF1E1F20; SURFACE2 = 0xFF282A2C;
            TEXT = 0xFFE3E3E3; SUB = 0xFFC4C7C5; HINT = 0xFF8E918F; LINE = 0xFF333537; OUTLINE = 0xFF444746;
            PRIMARY = 0xFFA8C7FA; ON_PRIMARY = 0xFF062E6F; PRIMARY_CONT = 0xFF0842A0; ON_PRIMARY_CONT = 0xFFD3E3FD;
            BUBBLE_IN = 0xFF282A2C; ON_BUBBLE_IN = 0xFFE3E3E3;
            GREEN = 0xFF6DD58C; RED = 0xFFF2B8B5; AMBER = 0xFFFFB870; CHIP_ME = 0xFF0842A0; LINK = 0xFFA8C7FA;
            HOME_BG = 0xFF131314; CARD = 0xFF1E1F20; NAV = 0xFF2D2F31; NAV_SEL = 0xFF004A77; QUOTE_BG = 0xFF131314; PENDING = 0xFF0B3372;
        }
    }

    /** צבעי סרגל המצב והניווט לפי הערכה. */
    @SuppressWarnings("deprecation")
    static void window(Activity a) {
        Window w = a.getWindow();
        w.getDecorView().setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Build.VERSION.SDK_INT >= 23 || dark ? BG : 0xFF757575);
            w.setNavigationBarColor(Build.VERSION.SDK_INT >= 27 || dark ? BG : 0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            int f = 0;
            if (!dark) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 27) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable round(int color, float radiusPx, int stroke, int strokePx) {
        GradientDrawable d = round(color, radiusPx);
        d.setStroke(strokePx, stroke);
        return d;
    }

    /** פינות שונות: ימין-עליון, שמאל-עליון, שמאל-תחתון, ימין-תחתון (בפיקסלים). */
    static GradientDrawable corners(int color, float tl, float tr, float br, float bl) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return d;
    }

    static Drawable press(Drawable normal, Drawable pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** רקע לחיצה שקוף עם הדגשה עדינה. */
    static Drawable ripple(float radiusPx) {
        return press(round(0, radiusPx), round(alpha(TEXT, 0x18), radiusPx));
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        rtlText(t);
        return t;
    }

    static TextView center(TextView t) {
        t.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= 17) t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return t;
    }

    static void rtl(View v) {
        if (Build.VERSION.SDK_INT >= 17) v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }

    static void rtlText(TextView t) {
        if (Build.VERSION.SDK_INT >= 17) {
            t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            t.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }
    }

    /** טקסט של הודעה: הכיוון לפי התוכן (עברית מימין, אנגלית משמאל). */
    static void autoDir(TextView t) {
        if (Build.VERSION.SDK_INT >= 17) {
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        }
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        rtl(l);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        rtl(l);
        return l;
    }

    static Glyph iconButton(Context c, int kind, int color, View.OnClickListener click) {
        Glyph g = new Glyph(c, kind, color);
        int pad = dp(c, 12);
        g.setPadding(pad, pad, pad, pad);
        g.setBackgroundDrawable(ripple(dp(c, 24)));
        g.setOnClickListener(click);
        g.setLayoutParams(lp(dp(c, 48), dp(c, 48)));
        return g;
    }

    static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackgroundDrawable(d);
        v.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }
}
