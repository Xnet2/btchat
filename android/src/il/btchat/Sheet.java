package il.btchat;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** גיליון תחתון בסגנון Material 3: כותרת, תוכן חופשי ושורות פעולה. */
final class Sheet {
    private final Activity a;
    private final Dialog d;
    private final LinearLayout body;

    private Sheet(Activity a, String title) {
        this.a = a;
        d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = Th.col(a);
        float r = Th.dp(a, 28);
        root.setBackgroundDrawable(Th.corners(Th.dark ? 0xFF1E1F20 : 0xFFF7F9FC, r, r, 0, 0));
        root.setPadding(0, Th.dp(a, 10), 0, Th.dp(a, 12));
        View handle = new View(a);
        handle.setBackgroundDrawable(Th.round(Th.OUTLINE, Th.dp(a, 2)));
        LinearLayout.LayoutParams hp = Th.lp(Th.dp(a, 32), Th.dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Th.dp(a, 10);
        root.addView(handle, hp);
        if (title != null) {
            TextView t = Th.text(a, title, 16, Th.TEXT, true);
            t.setPadding(Th.dp(a, 24), Th.dp(a, 4), Th.dp(a, 24), Th.dp(a, 10));
            root.addView(t);
        }
        body = Th.col(a);
        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        root.addView(sv);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.32f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    static Sheet create(Activity a, String title) { return new Sheet(a, title); }

    Sheet view(View v) {
        body.addView(v);
        return this;
    }

    Sheet text(String s) {
        TextView t = Th.text(a, s, 14, Th.SUB, false);
        t.setPadding(Th.dp(a, 24), 0, Th.dp(a, 24), Th.dp(a, 12));
        t.setLineSpacing(0, 1.15f);
        body.addView(t);
        return this;
    }

    Sheet item(int glyph, String label, Runnable r) { return item(glyph, label, Th.TEXT, false, r); }

    Sheet item(int glyph, String label, int color, boolean checked, Runnable r) {
        LinearLayout row = Th.row(a);
        row.setPadding(Th.dp(a, 24), 0, Th.dp(a, 16), 0);
        row.setMinimumHeight(Th.dp(a, 52));
        row.setBackgroundDrawable(Th.ripple(0));
        if (glyph >= 0) {
            Glyph g = new Glyph(a, glyph, color == Th.TEXT ? Th.SUB : color);
            LinearLayout.LayoutParams gp = Th.lp(Th.dp(a, 24), Th.dp(a, 24));
            gp.setMargins(Th.dp(a, 16), 0, 0, 0);
            if (android.os.Build.VERSION.SDK_INT >= 17) gp.setMarginEnd(Th.dp(a, 16));
            row.addView(g, gp);
        }
        TextView t = Th.text(a, label, 15.5f, color, false);
        row.addView(t, Th.lp(0, -2, 1));
        if (checked) row.addView(new Glyph(a, Glyph.CHECK, Th.PRIMARY), Th.lp(Th.dp(a, 24), Th.dp(a, 24)));
        row.setOnClickListener(v -> { d.dismiss(); if (r != null) r.run(); });
        body.addView(row);
        return this;
    }

    void show() { d.show(); }

    void dismiss() { d.dismiss(); }
}
