package il.btchat;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** ההגדרות: התראות, גוגל צ'אט, תצוגה, חיבור למחשב ואחסון. */
public final class SettingsActivity extends Activity implements LinkService.Listener {
    private boolean createdDark;
    private LinearLayout body;
    private TextView linkTitle, linkSub, googleSub;
    private View linkDot;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Th.load(this);
        createdDark = Th.dark;
        Th.window(this);
        LinearLayout root = Th.col(this);
        root.setBackgroundColor(Th.BG);
        LinearLayout bar = Th.row(this);
        bar.setPadding(Th.dp(this, 4), Th.dp(this, 4), Th.dp(this, 4), Th.dp(this, 4));
        bar.setMinimumHeight(Th.dp(this, 64));
        bar.addView(Th.iconButton(this, Glyph.BACK, Th.TEXT, v -> finish()));
        TextView title = Th.text(this, "הגדרות", 22, Th.TEXT, false);
        title.setPadding(Th.dp(this, 8), 0, Th.dp(this, 8), 0);
        bar.addView(title, Th.lp(0, -2, 1));
        root.addView(bar);
        ScrollView sv = new ScrollView(this);
        body = Th.col(this);
        body.setPadding(0, 0, 0, Th.dp(this, 24));
        sv.addView(body);
        root.addView(sv, Th.lp(-1, 0, 1));
        setContentView(root);
        build();
    }

    private void build() {
        body.removeAllViews();

        section("חיבור");
        LinearLayout link = Th.row(this);
        link.setPadding(Th.dp(this, 24), Th.dp(this, 12), Th.dp(this, 24), Th.dp(this, 12));
        linkDot = Th.dot(this, Th.AMBER, 10);
        link.addView(linkDot);
        LinearLayout lc = Th.col(this);
        lc.setPadding(Th.dp(this, 16), 0, Th.dp(this, 16), 0);
        linkTitle = Th.text(this, "", 16, Th.TEXT, false);
        linkSub = Th.text(this, "", 13.5f, Th.SUB, false);
        googleSub = Th.text(this, "", 13.5f, Th.SUB, false);
        lc.addView(linkTitle);
        lc.addView(linkSub);
        lc.addView(googleSub);
        link.addView(lc, Th.lp(0, -2, 1));
        body.addView(link);
        action(Glyph.BT, "להפוך את הנגן לגלוי לשיוך", "לשיוך ראשון עם המחשב (5 דקות)", () -> {
            try {
                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "לא ניתן לפתוח את הגדרות הבלוטות'", Toast.LENGTH_SHORT).show();
            }
        });

        section("התראות");
        toggle("התראות על הודעות חדשות", null, "notify", Prefs.notify(this));
        toggle("צליל", null, "sound", Prefs.sound(this));
        toggle("רטט", null, "vibrate", Prefs.vibrate(this));
        toggle("תוכן ההודעה בהתראה", "כשכבוי, ההתראה מציגה רק \"הודעה חדשה\"", "preview", Prefs.preview(this));
        if (Build.VERSION.SDK_INT >= 24) toggle("תשובה מתוך ההתראה", null, "reply", Prefs.replyFromNotif(this));
        TextView hint = Th.text(this, "להשתקת צ'אט מסוים: לחיצה ארוכה עליו ברשימה, או על הפעמון בתוך הצ'אט.", 13, Th.SUB, false);
        hint.setPadding(Th.dp(this, 24), Th.dp(this, 4), Th.dp(this, 24), Th.dp(this, 8));
        body.addView(hint);

        section("גוגל צ'אט");
        toggle("סימון כנקרא גם בגוגל צ'אט", "כשפותחים צ'אט בנגן, הוא מסומן כנקרא גם במחשב ובטלפון", "syncRead", Prefs.syncRead(this));
        toggle("מקש Enter שולח הודעה", "במקום מעבר שורה", "enterSends", Prefs.enterSends(this));

        section("תצוגה");
        String[] modes = {"לפי המערכת", "בהיר", "כהה"};
        action(Glyph.MOON, "ערכת נושא", modes[Prefs.theme(this)], () -> {
            Sheet s = Sheet.create(this, "ערכת נושא");
            for (int i = 0; i < 3; i++) {
                final int m = i;
                s.item(-1, modes[i], Th.TEXT, Prefs.theme(this) == i, () -> { Prefs.setTheme(this, m); recreate(); });
            }
            s.show();
        });

        section("אחסון");
        action(Glyph.IMAGE, "מחיקת התמונות השמורות", Fmt.size(Pics.cacheSize(this)), () -> {
            Pics.clearImages(this);
            Toast.makeText(this, "התמונות נמחקו", Toast.LENGTH_SHORT).show();
            build();
        });
        action(Glyph.CLOSE, "מחיקת כל ההודעות מהנגן", "הן ייטענו שוב מהמחשב", () -> {
            Sheet s = Sheet.create(this, "למחוק את כל ההודעות מהנגן?");
            s.text("ההודעות נשארות בגוגל צ'אט, ויחזרו לנגן בחיבור הבא למחשב.");
            s.item(Glyph.CLOSE, "מחיקה", Th.RED, false, () -> {
                Store.get(this).clearAll();
                Pics.clearImages(this);
                Toast.makeText(this, "ההודעות נמחקו", Toast.LENGTH_SHORT).show();
            });
            s.item(-1, "ביטול", null);
            s.show();
        });

        TextView about = Th.center(Th.text(this, "ביטיצ'אט " + version() + "\nגוגל צ'אט בנגן אופליין, דרך המחשב בבלוטות'", 12.5f, Th.HINT, false));
        about.setPadding(Th.dp(this, 24), Th.dp(this, 28), Th.dp(this, 24), 0);
        about.setLineSpacing(0, 1.2f);
        body.addView(about, Th.lp(-1, -2));
        onLinkChanged();
    }

    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    private void section(String s) {
        TextView t = Th.text(this, s, 14, Th.PRIMARY, true);
        t.setPadding(Th.dp(this, 24), Th.dp(this, 20), Th.dp(this, 24), Th.dp(this, 6));
        body.addView(t);
    }

    private void toggle(String title, String sub, String key, boolean value) {
        LinearLayout row = Th.row(this);
        row.setPadding(Th.dp(this, 24), Th.dp(this, 12), Th.dp(this, 24), Th.dp(this, 12));
        row.setMinimumHeight(Th.dp(this, 56));
        LinearLayout c = Th.col(this);
        c.addView(Th.text(this, title, 16, Th.TEXT, false));
        if (sub != null) {
            TextView s = Th.text(this, sub, 13.5f, Th.SUB, false);
            s.setPadding(0, Th.dp(this, 2), 0, 0);
            c.addView(s);
        }
        row.addView(c, Th.lp(0, -2, 1));
        Toggle t = new Toggle(this, value);
        t.setListener(on -> {
            Prefs.set(this, key, on);
            if (key.equals("sound") || key.equals("vibrate")) Notifier.channels(this);
        });
        LinearLayout.LayoutParams tp = Th.lp(-2, -2);
        tp.setMargins(Th.dp(this, 16), 0, Th.dp(this, 16), 0);
        row.addView(t, tp);
        row.setOnClickListener(v -> t.setOn(!t.isOn(), true));
        row.setBackgroundDrawable(Th.ripple(0));
        body.addView(row);
    }

    private void action(int glyph, String title, String sub, Runnable r) {
        LinearLayout row = Th.row(this);
        row.setPadding(Th.dp(this, 24), Th.dp(this, 12), Th.dp(this, 24), Th.dp(this, 12));
        row.setMinimumHeight(Th.dp(this, 56));
        row.setBackgroundDrawable(Th.ripple(0));
        Glyph g = new Glyph(this, glyph, Th.SUB);
        row.addView(g, Th.lp(Th.dp(this, 24), Th.dp(this, 24)));
        LinearLayout c = Th.col(this);
        c.setPadding(Th.dp(this, 16), 0, Th.dp(this, 16), 0);
        c.addView(Th.text(this, title, 16, Th.TEXT, false));
        if (sub != null && sub.length() > 0) c.addView(Th.text(this, sub, 13.5f, Th.SUB, false));
        row.addView(c, Th.lp(0, -2, 1));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> r.run());
        body.addView(row);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Th.load(this);
        if (Th.dark != createdDark) { recreate(); return; }
        LinkService.listener = this;
        onLinkChanged();
    }

    @Override
    public void finish() {
        super.finish();
        // נסגר לאותו צד שממנו נפתח (כפתור התפריט בצד ימין)
        overridePendingTransition(il.btchat.player.R.anim.stay, il.btchat.player.R.anim.out_right);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (LinkService.listener == this) LinkService.listener = null;
    }

    @Override
    public void onLinkChanged() {
        if (linkTitle == null) return;
        int st = LinkService.state;
        String pc = LinkService.peer.length() > 0 ? LinkService.peer : Prefs.pcName(this);
        if (st == LinkService.CONNECTED) {
            linkTitle.setText("מחובר למחשב" + (pc.length() > 0 ? ": " + pc : ""));
            linkSub.setText("בלוטות' פעיל");
            linkDot.setBackgroundDrawable(Th.round(Th.GREEN, Th.dp(this, 5)));
            googleSub.setVisibility(View.VISIBLE);
            googleSub.setText(Store.googleOk ? "גוגל צ'אט: מחובר" : "גוגל צ'אט: " + (Store.googleMsg.length() > 0 ? Store.googleMsg : "מתחבר…"));
        } else {
            linkTitle.setText(st == LinkService.OFF ? "הבלוטות' כבוי" : "ממתין לחיבור מהמחשב");
            linkSub.setText(st == LinkService.OFF ? "יש להפעיל בלוטות' בנגן" : "התוכנה ביטיצ'אט במחשב מתחברת לנגן לבד");
            linkDot.setBackgroundDrawable(Th.round(Th.AMBER, Th.dp(this, 5)));
            googleSub.setVisibility(View.GONE);
        }
    }
}
