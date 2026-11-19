package il.btchat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import il.btchat.Model.Chat;
import il.btchat.player.R;

/** המסך הראשי, כמו בגוגל צ'אט: מסך הבית, צ'אטים אישיים ומרחבים, עם סרגל ניווט צף. */
public final class MainActivity extends Activity implements Store.Listener, LinkService.Listener {
    private static final String[] TITLES = {"מסך הבית", "צ'אטים אישיים", "מרחבים"};
    private int tab;
    private boolean unreadOnly;
    private String query = "";
    private boolean createdDark;
    private final List<Chat> shown = new ArrayList<>();
    private Adapter adapter;
    private ListView list;
    private LinearLayout banner, empty, nav;
    private TextView bannerText, title, unreadChip;
    private EditText search;
    private Glyph clearSearch;
    private AvatarView selfAv;
    private View accDot;
    private final TextView[] badges = new TextView[3];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Th.load(this);
        createdDark = Th.dark;
        Th.window(this);
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(Th.HOME_BG);
        LinkService.start(this);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Th.HOME_BG);
        LinearLayout root = Th.col(this);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // שורה עליונה: תפריט, חיפוש באמצע, התמונה שלך (שני הצדדים באותו רוחב, כדי שהחיפוש יהיה באמצע)
        LinearLayout top = Th.row(this);
        top.setPadding(Th.dp(this, 8), Th.dp(this, 8), Th.dp(this, 8), Th.dp(this, 4));
        top.addView(Th.iconButton(this, Glyph.MENU, Th.SUB, v -> openSettings()));
        LinearLayout pill = Th.row(this);
        pill.setBackgroundDrawable(Th.round(Th.CARD, Th.dp(this, 28)));
        pill.setPadding(Th.dp(this, 16), 0, Th.dp(this, 4), 0);
        search = new EditText(this);
        search.setHint("חיפוש בצ'אט");
        search.setHintTextColor(Th.SUB);
        search.setTextColor(Th.TEXT);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setBackgroundDrawable(null);
        search.setPadding(0, 0, 0, 0);
        search.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= 17) search.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        pill.addView(search, Th.lp(0, Th.dp(this, 52), 1));
        clearSearch = Th.iconButton(this, Glyph.CLOSE, Th.SUB, v -> { search.setText(""); hideKeyboard(); });
        clearSearch.setVisibility(View.GONE);
        pill.addView(clearSearch);
        // בתוך הגלולה: ריפוד שווה משני הצדדים, כך שהטקסט באמצע
        pill.setPadding(Th.dp(this, 4), 0, Th.dp(this, 4), 0);
        View spacer = new View(this);
        pill.addView(spacer, 0, Th.lp(Th.dp(this, 48), 1));
        clearSearch.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams pp = Th.lp(0, Th.dp(this, 56), 1);
        pp.setMargins(Th.dp(this, 6), 0, Th.dp(this, 6), 0);
        top.addView(pill, pp);
        FrameLayout avBox = new FrameLayout(this);
        avBox.setBackgroundDrawable(Th.ripple(Th.dp(this, 24)));
        selfAv = new AvatarView(this);
        avBox.addView(selfAv, new FrameLayout.LayoutParams(Th.dp(this, 34), Th.dp(this, 34), Gravity.CENTER));
        accDot = Th.dot(this, Th.RED, 10);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Th.dp(this, 10), Th.dp(this, 10), Gravity.TOP | Gravity.LEFT);
        dl.leftMargin = Th.dp(this, 7); dl.topMargin = Th.dp(this, 7);
        avBox.addView(accDot, dl);
        avBox.setOnClickListener(v -> accountsMenu());
        top.addView(avBox, Th.lp(Th.dp(this, 48), Th.dp(this, 48)));
        root.addView(top);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                clearSearch.setVisibility(query.length() > 0 ? View.VISIBLE : View.INVISIBLE);
                reload();
            }
        });

        // כותרת גדולה, ומשמאל מתג "לא נקראו"
        LinearLayout head = Th.row(this);
        head.setPadding(Th.dp(this, 20), Th.dp(this, 10), Th.dp(this, 16), Th.dp(this, 10));
        title = Th.text(this, TITLES[0], 24, Th.TEXT, false);
        head.addView(title, Th.lp(0, -2, 1));
        unreadChip = Th.center(Th.text(this, "לא נקראו", 14, Th.SUB, true));
        unreadChip.setPadding(Th.dp(this, 14), 0, Th.dp(this, 14), 0);
        unreadChip.setOnClickListener(v -> { unreadOnly = !unreadOnly; bindChip(); reload(); });
        head.addView(unreadChip, Th.lp(-2, Th.dp(this, 34)));
        root.addView(head);
        bindChip();

        // פס מצב: לא מחובר למחשב / גוגל צ'אט לא מחובר
        banner = Th.row(this);
        banner.setPadding(Th.dp(this, 16), Th.dp(this, 10), Th.dp(this, 16), Th.dp(this, 10));
        banner.setBackgroundDrawable(Th.round(Th.CARD, Th.dp(this, 16)));
        banner.addView(new Glyph(this, Glyph.BT, Th.AMBER), Th.lp(Th.dp(this, 20), Th.dp(this, 20)));
        bannerText = Th.text(this, "", 13.5f, Th.SUB, false);
        LinearLayout.LayoutParams btp = Th.lp(0, -2, 1);
        btp.setMargins(Th.dp(this, 12), 0, Th.dp(this, 12), 0);
        banner.addView(bannerText, btp);
        banner.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams bp = Th.lp(-1, -2);
        bp.setMargins(Th.dp(this, 12), 0, Th.dp(this, 12), Th.dp(this, 8));
        root.addView(banner, bp);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setClipToPadding(false);
        list.setPadding(Th.dp(this, 12), 0, Th.dp(this, 12), Th.dp(this, 96));
        list.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Chat c = shown.get(pos);
            startActivity(new Intent(this, ChatActivity.class).putExtra("g", c.gid));
            overridePendingTransition(R.anim.in_left, R.anim.stay);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> { chatMenu(shown.get(pos)); return true; });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) { if (s != SCROLL_STATE_IDLE) hideKeyboard(); }
            public void onScroll(AbsListView v, int a, int b2, int c) { }
        });
        root.addView(list, Th.lp(-1, 0, 1));

        empty = Th.col(this);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Th.dp(this, 32), 0, Th.dp(this, 32), Th.dp(this, 100));
        root.addView(empty, Th.lp(-1, 0, 1));

        // סרגל ניווט צף
        nav = Th.row(this);
        nav.setPadding(Th.dp(this, 8), Th.dp(this, 8), Th.dp(this, 8), Th.dp(this, 8));
        nav.setBackgroundDrawable(Th.round(Th.NAV, Th.dp(this, 32)));
        if (Build.VERSION.SDK_INT >= 21) nav.setElevation(Th.dp(this, 6));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        np.bottomMargin = Th.dp(this, 16);
        frame.addView(nav, np);
        buildNav();
        setContentView(frame);
    }

    private void bindChip() {
        unreadChip.setTextColor(unreadOnly ? (Th.dark ? 0xFFC2E7FF : 0xFF001D35) : Th.SUB);
        unreadChip.setBackgroundDrawable(unreadOnly ? Th.round(Th.dark ? 0xFF004A77 : 0xFFC2E7FF, Th.dp(this, 17))
                : Th.round(Th.CARD, Th.dp(this, 17), Th.OUTLINE, Th.dp(this, 1)));
    }

    private void buildNav() {
        nav.removeAllViews();
        int[][] icons = {{Glyph.HOME, Glyph.HOME_FILL}, {Glyph.CHATS, Glyph.CHATS_FILL}, {Glyph.SPACES, Glyph.SPACES}};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            boolean on = i == tab;
            cell.setBackgroundDrawable(on ? Th.round(Th.NAV_SEL, Th.dp(this, 24)) : Th.ripple(Th.dp(this, 24)));
            Glyph g = new Glyph(this, icons[i][on ? 1 : 0], on ? Th.ON_PRIMARY_CONT : Th.SUB);
            int pad = Th.dp(this, 13);
            g.setPadding(pad, pad, pad, pad);
            cell.addView(g, new FrameLayout.LayoutParams(Th.dp(this, 64), Th.dp(this, 48), Gravity.CENTER));
            TextView badge = Th.center(Th.text(this, "", 10.5f, 0xFFFFFFFF, true));
            badge.setPadding(Th.dp(this, 5), 0, Th.dp(this, 5), 0);
            badge.setMinWidth(Th.dp(this, 18));
            badge.setBackgroundDrawable(Th.round(Th.dark ? 0xFF5F6368 : 0xFF444746, Th.dp(this, 9)));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, Th.dp(this, 18), Gravity.TOP | Gravity.RIGHT);
            bl.topMargin = Th.dp(this, 2);
            bl.rightMargin = Th.dp(this, 8);
            cell.addView(badge, bl);
            badges[i] = badge;
            cell.setOnClickListener(v -> {
                if (tab == idx) { list.setSelection(0); return; }
                tab = idx;
                title.setText(TITLES[tab]);
                buildNav();
                reload();
            });
            LinearLayout.LayoutParams cp = Th.lp(Th.dp(this, 64), Th.dp(this, 48));
            cp.setMargins(Th.dp(this, 2), 0, Th.dp(this, 2), 0);
            nav.addView(cell, cp);
        }
        updateBadges();
    }

    /** מספר הצ'אטים שלא נקראו בכל לשונית (בבית — כלום, כמו בגוגל צ'אט: שם רואים הכל). */
    private void updateBadges() {
        int dms = 0, spaces = 0;
        for (Chat c : Store.get(this).chatList(Store.current(this))) {
            if (!c.hasUnread() || Prefs.muted(this, c.gid)) continue;
            if (c.dm) dms++; else spaces++;
        }
        int[] counts = {0, dms, spaces};
        for (int i = 0; i < 3; i++) {
            if (badges[i] == null) continue;
            badges[i].setVisibility(counts[i] > 0 && i != tab ? View.VISIBLE : View.GONE);
            badges[i].setText(counts[i] > 99 ? "99+" : String.valueOf(counts[i]));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Th.load(this);
        if (Th.dark != createdDark) { recreate(); return; }
        Store.listener = this;
        LinkService.listener = this;
        reload();
        onLinkChanged();
        bindSelf();
    }

    private void bindSelf() {
        Store st = Store.get(this);
        String cur = Store.current(this);
        org.json.JSONObject acc = Store.account(this, cur);
        String name = acc != null ? acc.optString("n") : st.userName(cur);
        String av = acc != null && acc.optString("av").length() > 0 ? acc.optString("av") : st.userAvatar(cur);
        selfAv.set(name, av, false);
        // נקודה אדומה על התמונה: יש הודעות שלא נקראו בחשבון אחר
        boolean other = false;
        org.json.JSONArray accs = Store.accounts(this);
        for (int i = 0; i < accs.length(); i++) {
            String k = accs.optJSONObject(i) != null ? accs.optJSONObject(i).optString("k") : "";
            if (k.length() > 0 && !k.equals(cur) && st.unreadCount(k) > 0) other = true;
        }
        accDot.setVisibility(other ? View.VISIBLE : View.GONE);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
        overridePendingTransition(R.anim.in_right, R.anim.stay);
    }

    /** לחיצה על התמונה: בחירת חשבון. */
    private void accountsMenu() {
        Store st = Store.get(this);
        org.json.JSONArray accs = Store.accounts(this);
        String cur = Store.current(this);
        Sheet s = Sheet.create(this, accs.length() > 1 ? "החלפת חשבון" : "החשבון");
        for (int i = 0; i < accs.length(); i++) {
            org.json.JSONObject a = accs.optJSONObject(i);
            if (a == null) continue;
            String k = a.optString("k");
            LinearLayout row = Th.row(this);
            row.setPadding(Th.dp(this, 24), Th.dp(this, 10), Th.dp(this, 20), Th.dp(this, 10));
            row.setBackgroundDrawable(Th.ripple(0));
            AvatarView av = new AvatarView(this);
            av.set(a.optString("n"), a.optString("av"), false);
            row.addView(av, Th.lp(Th.dp(this, 40), Th.dp(this, 40)));
            LinearLayout col = Th.col(this);
            col.setPadding(Th.dp(this, 16), 0, Th.dp(this, 16), 0);
            col.addView(Th.text(this, a.optString("n"), 16, Th.TEXT, k.equals(cur)));
            col.addView(Th.text(this, a.optString("e") + (a.optBoolean("ok", true) ? "" : " · לא מחובר"), 13.5f, Th.SUB, false));
            row.addView(col, Th.lp(0, -2, 1));
            int n = st.unreadCount(k);
            if (n > 0) {
                TextView b = Th.center(Th.text(this, String.valueOf(n), 11.5f, Th.ON_PRIMARY, true));
                b.setMinWidth(Th.dp(this, 22));
                b.setPadding(Th.dp(this, 6), 0, Th.dp(this, 6), 0);
                b.setBackgroundDrawable(Th.round(Th.PRIMARY, Th.dp(this, 11)));
                row.addView(b, Th.lp(-2, Th.dp(this, 22)));
            }
            if (k.equals(cur)) row.addView(new Glyph(this, Glyph.CHECK, Th.PRIMARY), Th.lp(Th.dp(this, 24), Th.dp(this, 24)));
            final Sheet fs = s;
            row.setOnClickListener(v -> {
                fs.dismiss();
                if (k.equals(Store.current(this))) return;
                Store.setCurrent(this, k);
                list.setSelection(0);
                reload();
                bindSelf();
            });
            s.view(row);
        }
        if (accs.length() == 0) s.text("החשבון יופיע כאן אחרי החיבור הראשון למחשב.");
        s.text("להוספת חשבון: בתוכנה ביטיצ'אט במחשב ← \"הוספת חשבון\".");
        s.show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Store.listener == this) Store.listener = null;
        if (LinkService.listener == this) LinkService.listener = null;
    }

    @Override
    public void changed(String gid) {
        if ("*img".equals(gid)) { adapter.notifyDataSetChanged(); selfAv.refresh(); return; }
        reload();
        if (gid == null) bindSelf();
    }

    @Override
    public void onLinkChanged() {
        String msg = null;
        if (LinkService.state == LinkService.OFF) msg = "הבלוטות' כבוי";
        else if (LinkService.state != LinkService.CONNECTED) msg = "הנגן לא מחובר למחשב — מוצגות ההודעות השמורות";
        else if (!Store.googleOk) msg = Store.googleMsg.length() > 0 ? Store.googleMsg : "המחשב מתחבר לגוגל צ'אט…";
        banner.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) bannerText.setText(msg);
        updateEmpty();
        bindSelf();
    }

    private void reload() {
        Store st = Store.get(this);
        shown.clear();
        String q = query.toLowerCase();
        for (Chat c : st.chatList(Store.current(this))) {
            if (unreadOnly && !c.hasUnread()) continue;
            if (tab == 1 && !c.dm) continue;
            if (tab == 2 && c.dm) continue;
            if (q.length() > 0) {
                String hay = (st.title(c) + " " + (c.snippet != null ? c.snippet.preview() : "")).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            shown.add(c);
        }
        adapter.notifyDataSetChanged();
        updateEmpty();
        updateBadges();
    }

    private void updateEmpty() {
        boolean none = shown.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (!none) return;
        empty.removeAllViews();
        Glyph g = new Glyph(this, query.length() > 0 ? Glyph.SEARCH : Glyph.CHATS, Th.HINT);
        g.setWeight(0.7f);
        empty.addView(g, Th.lp(Th.dp(this, 72), Th.dp(this, 72)));
        String t, sub;
        if (query.length() > 0) { t = "לא נמצאו צ'אטים"; sub = ""; }
        else if (unreadOnly) { t = "אין הודעות שלא נקראו"; sub = ""; }
        else if (!Store.get(this).chats.isEmpty()) { t = tab == 2 ? "אין מרחבים" : "אין צ'אטים"; sub = ""; }
        else if (LinkService.connected()) { t = "טוען את הצ'אטים…"; sub = "זה לוקח כמה שניות בחיבור הראשון"; }
        else {
            t = "עוד אין כאן צ'אטים";
            sub = "מפעילים במחשב את התוכנה ביטיצ'אט, מתחברים בה לגוגל צ'אט, והנגן יתחבר אליה לבד בבלוטות'.";
        }
        TextView tv = Th.center(Th.text(this, t, 18, Th.TEXT, true));
        tv.setPadding(0, Th.dp(this, 16), 0, Th.dp(this, 8));
        empty.addView(tv, Th.lp(-1, -2));
        if (sub.length() > 0) {
            TextView s = Th.center(Th.text(this, sub, 14, Th.SUB, false));
            s.setLineSpacing(0, 1.2f);
            empty.addView(s, Th.lp(-1, -2));
        }
    }

    private void chatMenu(Chat c) {
        Store st = Store.get(this);
        boolean muted = Prefs.muted(this, c.gid);
        Sheet s = Sheet.create(this, st.title(c));
        if (c.hasUnread()) s.item(Glyph.DONE_ALL, "סימון כנקרא", () -> { ReplyReceiver.markRead(this, c.gid); Notifier.cancel(this, c.gid); });
        s.item(muted ? Glyph.BELL : Glyph.BELL_OFF, muted ? "ביטול ההשתקה" : "השתקת ההתראות", () -> {
            Prefs.setMuted(this, c.gid, !muted);
            if (!muted) Notifier.cancel(this, c.gid);
            reload();
        });
        s.show();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    // ---------------------------------------------------------------- כרטיס ברשימה

    private final class Row extends LinearLayout {
        final AvatarView av;
        final TextView name, time, snippet;
        final View dot;
        final Glyph mute;
        final LinearLayout r2;

        Row() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            Th.rtl(this);
            setPadding(Th.dp(getContext(), 16), Th.dp(getContext(), 12), Th.dp(getContext(), 16), Th.dp(getContext(), 12));
            av = new AvatarView(getContext());
            addView(av, Th.lp(Th.dp(getContext(), 40), Th.dp(getContext(), 40)));
            LinearLayout col = Th.col(getContext());
            LayoutParams cp = Th.lp(0, -2, 1);
            cp.setMargins(Th.dp(getContext(), 16), 0, Th.dp(getContext(), 4), 0);
            if (Build.VERSION.SDK_INT >= 17) { cp.setMarginStart(Th.dp(getContext(), 16)); cp.setMarginEnd(Th.dp(getContext(), 4)); }
            addView(col, cp);
            LinearLayout r1 = Th.row(getContext());
            name = Th.text(getContext(), "", 16, Th.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            r1.addView(name, Th.lp(0, -2, 1));
            mute = new Glyph(getContext(), Glyph.BELL_OFF, Th.HINT);
            LayoutParams mp = Th.lp(Th.dp(getContext(), 16), Th.dp(getContext(), 16));
            mp.setMargins(Th.dp(getContext(), 6), 0, Th.dp(getContext(), 6), 0);
            r1.addView(mute, mp);
            dot = Th.dot(getContext(), Th.TEXT, 7);
            LayoutParams dp = Th.lp(Th.dp(getContext(), 7), Th.dp(getContext(), 7));
            dp.setMargins(Th.dp(getContext(), 6), 0, Th.dp(getContext(), 6), 0);
            r1.addView(dot, dp);
            time = Th.text(getContext(), "", 12.5f, Th.SUB, false);
            r1.addView(time);
            col.addView(r1);
            r2 = Th.row(getContext());
            snippet = Th.text(getContext(), "", 14, Th.SUB, false);
            snippet.setSingleLine(true);
            snippet.setEllipsize(TextUtils.TruncateAt.END);
            r2.addView(snippet, Th.lp(0, -2, 1));
            LayoutParams r2p = Th.lp(-1, -2);
            r2p.topMargin = Th.dp(getContext(), 2);
            col.addView(r2, r2p);
        }

        void bind(int pos) {
            Chat c = shown.get(pos);
            Store st = Store.get(getContext());
            String t = st.title(c);
            av.set(t, st.chatAvatar(c), !c.dm);
            boolean unread = c.hasUnread();
            boolean home = tab == 0;
            name.setText(t);
            name.setTypeface(unread ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.DEFAULT);
            time.setText(home ? Fmt.listTime(c.lastTime()) : "");
            time.setVisibility(home ? View.VISIBLE : View.GONE);
            time.setTextColor(unread ? Th.TEXT : Th.SUB);
            time.setTypeface(unread ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            dot.setVisibility(unread ? View.VISIBLE : View.GONE);
            mute.setVisibility(Prefs.muted(getContext(), c.gid) ? View.VISIBLE : View.GONE);

            // שורת התקציר — רק במסך הבית (בלשוניות האחרות רק שמות, כמו בגוגל צ'אט)
            String sn = "";
            boolean italic = false;
            if (home && c.snippet != null) {
                sn = c.snippet.preview();
                italic = c.snippet.previewItalic();
                String who = c.snippet.me ? "אני" : !c.dm ? st.userName(c.snippet.s) : "";
                if (who.length() > 0) sn = (c.snippet.me ? who : who.split(" ")[0]) + ": " + sn;
            }
            r2.setVisibility(sn.length() > 0 ? View.VISIBLE : View.GONE);
            snippet.setText(sn);
            snippet.setTextColor(unread ? Th.TEXT : Th.SUB);
            snippet.setTypeface(italic ? Typeface.defaultFromStyle(Typeface.ITALIC)
                    : unread ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);

            // כרטיסים מחוברים: פינות גדולות בקצוות הרשימה, קטנות באמצע
            float big = Th.dp(getContext(), 20), small = Th.dp(getContext(), 4);
            float top = pos == 0 ? big : small, bottom = pos == shown.size() - 1 ? big : small;
            setBackgroundDrawable(Th.press(Th.corners(Th.CARD, top, top, bottom, bottom),
                    Th.corners(Th.dark ? 0xFF2D2F31 : 0xFFE9EEF6, top, top, bottom, bottom)));
            setMinimumHeight(Th.dp(getContext(), home ? 72 : 60));
        }
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int p) { return shown.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int p, View v, ViewGroup parent) {
            // הכרטיס עטוף במסגרת, בשביל רווח קטן בין הכרטיסים
            FrameLayout wrap;
            Row r;
            if (v instanceof FrameLayout && v.getTag() instanceof Row) {
                wrap = (FrameLayout) v;
                r = (Row) v.getTag();
            } else {
                wrap = new FrameLayout(MainActivity.this);
                r = new Row();
                r.setDuplicateParentStateEnabled(true);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
                lp.bottomMargin = Th.dp(MainActivity.this, 2);
                wrap.addView(r, lp);
                wrap.setTag(r);
            }
            r.bind(p);
            return wrap;
        }
    }
}
