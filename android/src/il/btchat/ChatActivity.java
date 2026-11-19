package il.btchat;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import il.btchat.Model.Chat;
import il.btchat.Model.Msg;

/** מסך צ'אט (או שרשור בתוך מרחב): בועות הודעה, תגובות, תמונות ושליחה. */
public final class ChatActivity extends Activity implements Store.Listener, LinkService.Listener, LinkService.LoadCallback {
    static volatile String visibleGid;
    private static final String[] QUICK = {"👍", "❤️", "😂", "🙏", "💯", "😮", "😢", "🔥", "✅"};
    private Msg quoting;                 // ההודעה שמצטטים בתשובה (אחרי החלקה)
    private LinearLayout quoteBar;
    private TextView quoteBarName, quoteBarText;
    private long unreadSince = -1;       // מאיזה זמן להציג את הקו "הודעות שלא נקראו"
    private Glyph qIcon, fmtBtn;
    private Msg editing;                 // הודעה שלי בעריכה
    private java.io.File attachment;     // תמונה מצורפת לשליחה
    private LinearLayout attachBar, fmtBar;
    private View fmtDivider;
    private ImageView attachThumb;
    private final TextView[] fmtBtns = new TextView[Rich.TYPES.length];
    private boolean fmtOpen;
    private final java.util.Map<String, Integer> threadUnread = new java.util.HashMap<>();

    private String gid, thread;
    private Chat chat;
    private boolean createdDark, loadingOlder, opening, atTop;
    private final List<Object> items = new ArrayList<>();   // Long = כותרת יום, Msg = הודעה, "top" = שורת טעינה
    private Adapter adapter;
    private ListView list;
    private EditText input;
    private FrameLayout sendBtn;
    private Glyph sendIcon, bell;
    private TextView titleView, subView, status;
    private AvatarView headAv;
    private final Handler h = new Handler();
    private int bubbleMax;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Th.load(this);
        createdDark = Th.dark;
        Th.window(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        gid = getIntent().getStringExtra("g");
        thread = getIntent().getStringExtra("th");
        if (gid == null) { finish(); return; }
        chat = Store.get(this).chat(gid);
        unreadSince = chat != null && chat.hasUnread() && thread == null ? chat.lastRead : Long.MAX_VALUE;
        bubbleMax = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);

        LinearLayout root = Th.col(this);
        root.setBackgroundColor(Th.BG);

        // סרגל עליון
        LinearLayout bar = Th.row(this);
        bar.setPadding(Th.dp(this, 4), Th.dp(this, 4), Th.dp(this, 4), Th.dp(this, 4));
        bar.setMinimumHeight(Th.dp(this, 64));
        bar.addView(Th.iconButton(this, Glyph.BACK, Th.TEXT, v -> finish()));
        if (thread == null) {
            headAv = new AvatarView(this);
            LinearLayout.LayoutParams ap = Th.lp(Th.dp(this, 36), Th.dp(this, 36));
            ap.setMargins(Th.dp(this, 4), 0, Th.dp(this, 4), 0);
            bar.addView(headAv, ap);
        }
        LinearLayout tcol = Th.col(this);
        tcol.setPadding(Th.dp(this, 8), 0, Th.dp(this, 8), 0);
        titleView = Th.text(this, "", 18, Th.TEXT, false);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        tcol.addView(titleView);
        subView = Th.text(this, "", 12.5f, Th.SUB, false);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        tcol.addView(subView);
        bar.addView(tcol, Th.lp(0, -2, 1));
        bell = Th.iconButton(this, Glyph.MORE, Th.SUB, v -> chatMenu());
        bar.addView(bell);
        root.addView(bar);
        View line = new View(this);
        line.setBackgroundColor(Th.LINE);
        root.addView(line, Th.lp(-1, 1));

        status = Th.center(Th.text(this, "", 13, Th.SUB, false));
        status.setPadding(Th.dp(this, 16), Th.dp(this, 6), Th.dp(this, 16), Th.dp(this, 6));
        status.setBackgroundColor(Th.SURFACE);
        root.addView(status, Th.lp(-1, -2));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setStackFromBottom(true);
        list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
        list.setBackgroundColor(Th.BG);
        list.setClipToPadding(false);
        list.setPadding(0, Th.dp(this, 8), 0, Th.dp(this, 8));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) { }
            public void onScroll(AbsListView v, int first, int visible, int total) {
                boolean top = first == 0 && total > visible;
                if (top && !atTop) maybeLoadOlder();
                atTop = top;
            }
        });
        root.addView(list, Th.lp(-1, 0, 1));

        // פס ציטוט / עריכה: מעל שורת הכתיבה
        quoteBar = Th.row(this);
        quoteBar.setPadding(Th.dp(this, 12), Th.dp(this, 6), Th.dp(this, 4), Th.dp(this, 2));
        LinearLayout qcard = Th.row(this);
        qcard.setBackgroundDrawable(Th.round(Th.SURFACE, Th.dp(this, 14)));
        qcard.setPadding(Th.dp(this, 12), Th.dp(this, 8), Th.dp(this, 12), Th.dp(this, 8));
        qIcon = new Glyph(this, Glyph.REPLY, Th.PRIMARY);
        qcard.addView(qIcon, Th.lp(Th.dp(this, 22), Th.dp(this, 22)));
        LinearLayout qcol = Th.col(this);
        qcol.setPadding(Th.dp(this, 10), 0, Th.dp(this, 10), 0);
        quoteBarName = Th.text(this, "", 13, Th.PRIMARY, true);
        quoteBarText = Th.text(this, "", 13.5f, Th.SUB, false);
        quoteBarText.setSingleLine(true);
        quoteBarText.setEllipsize(TextUtils.TruncateAt.END);
        qcol.addView(quoteBarName);
        qcol.addView(quoteBarText);
        qcard.addView(qcol, Th.lp(0, -2, 1));
        quoteBar.addView(qcard, Th.lp(0, -2, 1));
        quoteBar.addView(Th.iconButton(this, Glyph.CLOSE, Th.SUB, v -> {
            if (editing != null) { setEditing(null); input.setText(""); } else setQuote(null);
        }));
        quoteBar.setVisibility(View.GONE);
        root.addView(quoteBar, Th.lp(-1, -2));

        // תמונה מצורפת (לפני שליחה)
        attachBar = Th.row(this);
        attachBar.setPadding(Th.dp(this, 12), Th.dp(this, 6), Th.dp(this, 4), Th.dp(this, 2));
        attachThumb = new ImageView(this);
        attachThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        attachBar.addView(attachThumb, Th.lp(Th.dp(this, 56), Th.dp(this, 56)));
        TextView at = Th.text(this, "תמונה מצורפת · אפשר להוסיף טקסט", 13.5f, Th.SUB, false);
        at.setPadding(Th.dp(this, 12), 0, Th.dp(this, 12), 0);
        attachBar.addView(at, Th.lp(0, -2, 1));
        attachBar.addView(Th.iconButton(this, Glyph.CLOSE, Th.SUB, v -> setAttachment(null)));
        attachBar.setVisibility(View.GONE);
        root.addView(attachBar, Th.lp(-1, -2));

        // שורת כתיבה, כמו בגוגל צ'אט: פלוס, בועת טקסט (סרגל העיצוב נפתח בתוכה), ושליחה
        LinearLayout comp = Th.row(this);
        comp.setGravity(Gravity.BOTTOM);
        comp.setPadding(Th.dp(this, 8), Th.dp(this, 6), Th.dp(this, 8), Th.dp(this, 10));

        LinearLayout pill = Th.col(this);
        pill.setBackgroundDrawable(Th.round(Th.SURFACE, Th.dp(this, 24)));

        // סרגל העיצוב: מודגש, נטוי, קו תחתון, קו חוצה, קוד — בתוך הבועה, מעל הטקסט
        fmtBar = Th.row(this);
        fmtBar.setPadding(Th.dp(this, 8), Th.dp(this, 6), Th.dp(this, 4), Th.dp(this, 2));
        String[] labels = {"B", "I", "U", "S", "</>"};
        for (int i = 0; i < Rich.TYPES.length; i++) {
            final int type = Rich.TYPES[i];
            TextView b2 = new TextView(this);
            b2.setText(labels[i]);
            b2.setTextSize(type == Rich.MONO ? 13.5f : 17);
            b2.setTextColor(Th.SUB);
            b2.setGravity(Gravity.CENTER);
            b2.setIncludeFontPadding(false);
            if (type == Rich.BOLD) b2.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            else if (type == Rich.ITALIC) b2.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.ITALIC));
            else if (type == Rich.MONO) b2.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD));
            else b2.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            if (type == Rich.UNDER) b2.setPaintFlags(b2.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            if (type == Rich.STRIKE) b2.setPaintFlags(b2.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            b2.setOnClickListener(v -> {
                Rich.toggle(input.getText(), input.getSelectionStart(), input.getSelectionEnd(), type);
                updateFmt();
            });
            fmtBtns[i] = b2;
            LinearLayout.LayoutParams fp = Th.lp(Th.dp(this, 36), Th.dp(this, 36));
            fp.setMargins(Th.dp(this, 2), 0, Th.dp(this, 2), 0);
            fmtBar.addView(b2, fp);
        }
        fmtBar.addView(new View(this), Th.lp(0, 1, 1));
        Glyph fClose = new Glyph(this, Glyph.CLOSE, Th.SUB);
        int fc = Th.dp(this, 9);
        fClose.setPadding(fc, fc, fc, fc);
        fClose.setBackgroundDrawable(Th.ripple(Th.dp(this, 18)));
        fClose.setOnClickListener(v -> setFmtOpen(false));
        fmtBar.addView(fClose, Th.lp(Th.dp(this, 36), Th.dp(this, 36)));
        fmtBar.setVisibility(View.GONE);
        pill.addView(fmtBar, Th.lp(-1, -2));
        fmtDivider = new View(this);
        fmtDivider.setBackgroundColor(Th.OUTLINE);
        LinearLayout.LayoutParams dvp = Th.lp(-1, 1);
        dvp.setMargins(Th.dp(this, 14), Th.dp(this, 2), Th.dp(this, 14), 0);
        fmtDivider.setVisibility(View.GONE);
        pill.addView(fmtDivider, dvp);

        LinearLayout inRow = Th.row(this);
        inRow.setGravity(Gravity.BOTTOM);
        inRow.setPadding(0, 0, Th.dp(this, 4), 0);
        input = new ComposeText();
        input.setHint(thread != null ? "תשובה בשרשור" : "הודעה");
        input.setHintTextColor(Th.SUB);
        input.setTextColor(Th.TEXT);
        input.setTextSize(16);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackgroundDrawable(null);
        input.setPadding(Th.dp(this, 16), Th.dp(this, 12), Th.dp(this, 8), Th.dp(this, 12));
        input.setMinHeight(Th.dp(this, 48));
        Th.autoDir(input);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        if (Prefs.enterSends(this)) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setImeOptions(EditorInfo.IME_ACTION_SEND);
            input.setOnEditorActionListener((v, act, ev) -> {
                if (act == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) { send(); return true; }
                return false;
            });
        }
        // בתוך הבועה (כמו בגוגל צ'אט): הטקסט, עיצוב, סמיילי
        inRow.addView(input, Th.lp(0, -2, 1));
        fmtBtn = smallIcon(Glyph.FORMAT, v -> setFmtOpen(!fmtOpen));
        inRow.addView(fmtBtn);
        inRow.addView(smallIcon(Glyph.EMOJI, v -> emojiPicker()));
        pill.addView(inRow, Th.lp(-1, -2));
        this.inRow = inRow;
        recRow = Th.row(this);
        recRow.setPadding(Th.dp(this, 16), 0, Th.dp(this, 4), 0);
        recRow.setMinimumHeight(Th.dp(this, 48));
        recDot = Th.dot(this, 0xFFE53935, 10);
        recRow.addView(recDot);
        recTime = Th.text(this, "0:00", 16, Th.TEXT, false);
        recTime.setPadding(Th.dp(this, 10), 0, Th.dp(this, 10), 0);
        recRow.addView(recTime);
        recWave = new LiveWave(this);
        recRow.addView(recWave, Th.lp(0, Th.dp(this, 36), 1));
        Glyph recCancel = smallIcon(Glyph.DELETE, v -> stopRecording(false));
        ((LinearLayout.LayoutParams) recCancel.getLayoutParams()).bottomMargin = 0;
        recRow.addView(recCancel);
        recRow.setVisibility(View.GONE);
        pill.addView(recRow, Th.lp(-1, -2));
        comp.addView(pill, Th.lp(0, -2, 1));

        // מחוץ לבועה: תמונה, ומיקרופון שהופך לחץ שליחה כשיש טקסט
        Glyph img = smallIcon(Glyph.IMAGE, v -> pickImage());
        ((LinearLayout.LayoutParams) img.getLayoutParams()).setMargins(Th.dp(this, 2), 0, 0, Th.dp(this, 4));
        comp.addView(img);
        sendBtn = new FrameLayout(this);
        sendIcon = new Glyph(this, Glyph.MIC, Th.SUB);
        int sp = Th.dp(this, 10);
        sendIcon.setPadding(sp, sp, sp, sp);
        sendBtn.addView(sendIcon, new FrameLayout.LayoutParams(-1, -1));
        sendBtn.setBackgroundDrawable(Th.ripple(Th.dp(this, 22)));
        sendBtn.setOnClickListener(v -> {
            if (recorder != null) stopRecording(true);
            else if (hasContent()) send();
            else startRecording();
        });
        LinearLayout.LayoutParams sbp = Th.lp(Th.dp(this, 44), Th.dp(this, 44));
        sbp.setMargins(0, 0, Th.dp(this, 2), Th.dp(this, 2));
        comp.addView(sendBtn, sbp);
        root.addView(comp);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) { updateSend(); updateFmt(); }
        });
        updateSend();
        setContentView(root);
        bindHeader();
    }

    /** שורת הכתיבה: מעדכנת את מצב כפתורי העיצוב כשהסמן זז. */
    private final class ComposeText extends EditText {
        ComposeText() { super(ChatActivity.this); }

        @Override
        protected void onSelectionChanged(int s, int e) {
            super.onSelectionChanged(s, e);
            if (fmtBtns != null && fmtBtns[0] != null) updateFmt();
        }
    }

    private void updateFmt() {
        if (fmtBtn == null || input == null) return;
        Editable e = input.getText();
        int s = input.getSelectionStart(), en = input.getSelectionEnd();
        if (s < 0) s = en = e.length();
        for (int i = 0; i < Rich.TYPES.length; i++) {
            boolean on = Rich.active(e, Math.min(s, en), Math.max(s, en), Rich.TYPES[i]);
            fmtBtns[i].setTextColor(on ? Th.ON_PRIMARY_CONT : Th.SUB);
            fmtBtns[i].setBackgroundDrawable(on ? Th.round(Th.PRIMARY_CONT, Th.dp(this, 18)) : Th.ripple(Th.dp(this, 18)));
        }
        fmtBtn.setColor(fmtOpen ? Th.PRIMARY : Th.SUB);
        fmtBtn.setBackgroundDrawable(fmtOpen ? Th.round(Th.alpha(Th.PRIMARY, 0x22), Th.dp(this, 20)) : Th.ripple(Th.dp(this, 20)));
    }

    private void setFmtOpen(boolean open) {
        fmtOpen = open;
        fmtBar.setVisibility(open ? View.VISIBLE : View.GONE);
        fmtDivider.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) input.requestFocus();
        updateFmt();
    }

    /** אייקון קטן ונקי בתוך בועת הטקסט (40dp, עיגול לחיצה). */
    private Glyph smallIcon(int kind, View.OnClickListener click) {
        Glyph g = new Glyph(this, kind, Th.SUB);
        int p = Th.dp(this, 10);
        g.setPadding(p, p, p, p);
        g.setWeight(0.9f);
        g.setBackgroundDrawable(Th.ripple(Th.dp(this, 20)));
        g.setOnClickListener(click);
        LinearLayout.LayoutParams lp = Th.lp(Th.dp(this, 40), Th.dp(this, 40));
        lp.bottomMargin = Th.dp(this, 4);
        g.setLayoutParams(lp);
        return g;
    }

    private boolean hasContent() {
        return input.getText().toString().trim().length() > 0 || (attachment != null && editing == null);
    }

    /** מיקרופון כשאין מה לשלוח; חץ שליחה כחול (בלי עיגול) כשיש טקסט או תמונה; וי בעריכה. */
    private void updateSend() {
        boolean has = hasContent() || recorder != null;
        sendIcon.setKind(editing != null ? Glyph.CHECK : has ? Glyph.SEND : Glyph.MIC);
        sendIcon.setColor(has || editing != null ? Th.PRIMARY : Th.SUB);
    }

    private static final String[] EMOJIS = {"😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😉", "😎", "🤔", "😅", "😢", "😭", "😮", "😡", "🙄",
            "👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "🤝", "❤️", "💙", "💯", "🔥", "✅", "❌", "⭐", "🎉",
            "😴", "🤗", "😇", "🙂", "😬", "🤩", "👀", "🌹"};

    /** בחירת אימוג'י: נכנס למקום הסמן. הגיליון נשאר פתוח כדי לבחור כמה. */
    private void emojiPicker() {
        Sheet s = Sheet.create(this, null);
        Flow grid = new Flow(this);
        Th.rtl(grid);
        grid.setPadding(Th.dp(this, 16), 0, Th.dp(this, 16), Th.dp(this, 8));
        for (String e : EMOJIS) {
            TextView t = Th.center(Th.text(this, e, 24, Th.TEXT, false));
            t.setBackgroundDrawable(Th.ripple(Th.dp(this, 22)));
            t.setOnClickListener(v -> {
                int st = Math.max(0, input.getSelectionStart()), en = Math.max(0, input.getSelectionEnd());
                input.getText().replace(Math.min(st, en), Math.max(st, en), e);
            });
            grid.addView(t, new ViewGroup.LayoutParams(Th.dp(this, 44), Th.dp(this, 44)));
        }
        s.view(grid);
        s.show();
    }

    /** הכפתור "+": צירוף תמונה ועיצוב טקסט. */
    private void plusMenu() {
        Sheet s = Sheet.create(this, null);
        s.item(Glyph.IMAGE, "תמונה מהנגן", this::pickImage);
        s.item(Glyph.FORMAT, fmtOpen ? "הסתרת סרגל העיצוב" : "עיצוב טקסט", () -> setFmtOpen(!fmtOpen));
        s.show();
    }

    private static final int PICK = 7;

    private void pickImage() {
        if (editing != null) { Toast.makeText(this, "אי אפשר לצרף תמונה בעריכה", Toast.LENGTH_SHORT).show(); return; }
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 8);
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            if (!getPackageManager().queryIntentActivities(i, 0).isEmpty()) { startActivityForResult(Intent.createChooser(i, "בחירת תמונה"), PICK); return; }
            Intent p = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            if (!getPackageManager().queryIntentActivities(p, 0).isEmpty()) { startActivityForResult(p, PICK); return; }
        } catch (Exception ignored) { }
        Toast.makeText(this, "אין בנגן אפליקציה לבחירת תמונות (גלריה או מנהל קבצים)", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        Toast.makeText(this, "מכין את התמונה…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.io.File out = prepareImage(uri);
            runOnUiThread(() -> {
                if (out == null) Toast.makeText(this, "לא ניתן לפתוח את התמונה", Toast.LENGTH_LONG).show();
                else setAttachment(out);
            });
        }, "btchat-image").start();
    }

    /** מקטין את התמונה (עד 1600 פיקסלים) ושומר JPEG — כדי שהמעבר בבלוטות' יהיה מהיר. */
    private java.io.File prepareImage(android.net.Uri uri) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { b = android.graphics.BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return null;
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 1600) {
                float sc = 1600f / max;
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
                if (s != b) b.recycle();
                b = s;
            }
            java.io.File dir = new java.io.File(getFilesDir(), "out");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, System.currentTimeMillis() + ".jpg");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 85, fo); }
            b.recycle();
            return f;
        } catch (Throwable e) {
            return null;
        }
    }

    private void setAttachment(java.io.File f) {
        attachment = f;
        attachBar.setVisibility(f == null ? View.GONE : View.VISIBLE);
        if (f != null) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 8;
            attachThumb.setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.getPath(), o));
        } else attachThumb.setImageDrawable(null);
        updateSend();
    }

    /** עריכת הודעה שלי: הטקסט (עם העיצוב) עובר לשורת הכתיבה. null = ביטול. */
    private void setEditing(Msg m) {
        editing = m;
        if (m != null) {
            quoting = null;
            setAttachment(null);
            qIcon.setKind(Glyph.EDIT);
            quoteBarName.setText("עריכת הודעה");
            quoteBarText.setText(m.preview());
            quoteBar.setVisibility(View.VISIBLE);
            input.setText(Rich.editable(m.text, m.f));
            input.setSelection(input.length());
            input.requestFocus();
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, 0);
        } else {
            quoteBar.setVisibility(quoting != null ? View.VISIBLE : View.GONE);
        }
        updateSend();
    }

    private void bindHeader() {
        Store st = Store.get(this);
        chat = st.chat(gid);
        String name = chat != null ? st.title(chat) : "";
        if (thread != null) {
            titleView.setText("שרשור");
            subView.setText(name);
            subView.setVisibility(View.VISIBLE);
        } else {
            titleView.setText(name);
            if (headAv != null && chat != null) headAv.set(name, st.chatAvatar(chat), !chat.dm);
            String sub = chat == null || chat.dm ? "" : "מרחב";
            subView.setText(sub);
            subView.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void toggleMute() {
        boolean muted = !Prefs.muted(this, gid);
        Prefs.setMuted(this, gid, muted);
        bindHeader();
        Toast.makeText(this, muted ? "ההתראות מהצ'אט הזה הושתקו" : "ההתראות מהצ'אט הזה פעילות", Toast.LENGTH_SHORT).show();
    }

    /** התפריט שבפינה: השתקה וטעינה מחדש. */
    private void chatMenu() {
        boolean muted = Prefs.muted(this, gid);
        Sheet s = Sheet.create(this, null);
        s.item(muted ? Glyph.BELL : Glyph.BELL_OFF, muted ? "ביטול ההשתקה" : "השתקת ההתראות", this::toggleMute);
        s.item(Glyph.REFRESH, "רענון ההודעות", () -> {
            if (LinkService.connected()) { opening = true; LinkService.open(gid); adapter.notifyDataSetChanged(); }
            else Toast.makeText(this, "הנגן לא מחובר למחשב", Toast.LENGTH_SHORT).show();
        });
        s.show();
    }

    /** ציטוט בתשובה: מציג את ההודעה מעל שורת הכתיבה. null = ביטול. */
    private void setQuote(Msg m) {
        if (m != null && editing != null) { editing = null; input.setText(""); }
        quoting = m;
        quoteBar.setVisibility(m == null ? View.GONE : View.VISIBLE);
        updateSend();
        if (m == null) return;
        qIcon.setKind(Glyph.REPLY);
        String who = m.me ? "את/ה" : Store.get(this).userName(m.s);
        quoteBarName.setText("ציטוט: " + (who.length() > 0 ? who : "הודעה"));
        quoteBarText.setText(m.preview());
        input.requestFocus();
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(input, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Th.load(this);
        if (Th.dark != createdDark) { recreate(); return; }
        visibleGid = gid;
        if (chat != null && chat.acc().length() > 0) Store.setCurrent(this, chat.acc());   // חזרה לרשימה תציג את החשבון של הצ'אט
        Store.listener = this;
        LinkService.listener = this;
        LinkService.loadCallback = this;
        Notifier.cancel(this, gid);
        reload(true);
        onLinkChanged();
        if (LinkService.connected()) { opening = true; LinkService.open(gid); }
        markReadSoon();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gid != null && gid.equals(visibleGid)) visibleGid = null;
        if (Store.listener == this) Store.listener = null;
        if (LinkService.listener == this) LinkService.listener = null;
        if (LinkService.loadCallback == this) LinkService.loadCallback = null;
        if (recorder != null) stopRecording(false);
        Voice.stop();
    }

    // ---------------------------------------------------------------- הקלטת הודעה קולית

    private LinearLayout inRow, recRow;
    private View recDot;
    private TextView recTime;
    private LiveWave recWave;
    private android.media.MediaRecorder recorder;
    private java.io.File recFile;
    private long recStart;
    private final java.util.ArrayList<Integer> recLevels = new java.util.ArrayList<>();
    private static final long REC_MAX = 5 * 60 * 1000;

    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) return;
            long el = System.currentTimeMillis() - recStart;
            recTime.setText(VoiceView.fmt(el));
            try { int amp = recorder.getMaxAmplitude(); recLevels.add(amp); recWave.add(amp); } catch (Exception ignored) { }
            recDot.setAlpha((el / 500) % 2 == 0 ? 1f : 0.3f);
            if (el >= REC_MAX) { stopRecording(true); return; }
            h.postDelayed(this, 60);
        }
    };

    private void startRecording() {
        if (editing != null) { Toast.makeText(this, "אי אפשר להקליט בעריכה", Toast.LENGTH_SHORT).show(); return; }
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 9);
            return;
        }
        Voice.stop();
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "out");
            dir.mkdirs();
            recFile = new java.io.File(dir, "voice_" + System.currentTimeMillis() + ".m4a");
            android.media.MediaRecorder r = new android.media.MediaRecorder();
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(64000);
            r.setOutputFile(recFile.getPath());
            r.prepare();
            r.start();
            recorder = r;
        } catch (Exception e) {
            recorder = null;
            Toast.makeText(this, "לא ניתן להקליט — אין מיקרופון זמין בנגן", Toast.LENGTH_LONG).show();
            return;
        }
        recStart = System.currentTimeMillis();
        recLevels.clear();
        recWave.clear();
        inRow.setVisibility(View.GONE);
        recRow.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        h.post(recTick);
        updateSend();
    }

    /** send=true: עוצרים ושולחים. false: ביטול (הקובץ נמחק). */
    private void stopRecording(boolean send) {
        android.media.MediaRecorder r = recorder;
        recorder = null;
        h.removeCallbacks(recTick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        inRow.setVisibility(View.VISIBLE);
        recRow.setVisibility(View.GONE);
        long dur = System.currentTimeMillis() - recStart;
        boolean ok = true;
        try { if (r != null) r.stop(); } catch (Exception e) { ok = false; }
        try { if (r != null) r.release(); } catch (Exception ignored) { }
        updateSend();
        if (!send || !ok || dur < 800) {
            if (recFile != null) recFile.delete();
            if (send) Toast.makeText(this, "ההקלטה קצרה מדי", Toast.LENGTH_SHORT).show();
            return;
        }
        LinkService.sendVoice(this, gid, recFile, dur, waveOf(recLevels), thread, quoting);
        setQuote(null);
        list.setSelection(items.size() - 1);
        if (!LinkService.connected()) Toast.makeText(this, "הנגן לא מחובר למחשב — ההודעה תסומן כלא נשלחה", Toast.LENGTH_SHORT).show();
    }

    /** גל קול: עד 40 עמודות, בסולם 0–31 (כמו בהקלטות של גוגל צ'אט). */
    private static JSONArray waveOf(java.util.List<Integer> levels) {
        JSONArray out = new JSONArray();
        int n = Math.min(40, Math.max(1, levels.size()));
        int max = 1;
        for (int v : levels) max = Math.max(max, v);
        for (int i = 0; i < n; i++) {
            int from = i * levels.size() / n, to = Math.max(from + 1, (i + 1) * levels.size() / n);
            int peak = 0;
            for (int j = from; j < to && j < levels.size(); j++) peak = Math.max(peak, levels.get(j));
            out.put(Math.round(31f * (float) Math.sqrt(peak / (float) max)));
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 9 && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording();
        else if (req == 9) Toast.makeText(this, "צריך הרשאת מיקרופון כדי להקליט", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void changed(String g) {
        if ("*img".equals(g)) { adapter.notifyDataSetChanged(); if (headAv != null) headAv.refresh(); return; }
        if (g == null) { bindHeader(); return; }
        if (!g.equals(gid)) return;
        reload(false);
        markReadSoon();
    }

    @Override
    public void onLinkChanged() {
        String msg = null;
        if (LinkService.state == LinkService.OFF) msg = "הבלוטות' כבוי";
        else if (LinkService.state != LinkService.CONNECTED) msg = "הנגן לא מחובר למחשב";
        else if (!Store.googleOk) msg = Store.googleMsg.length() > 0 ? Store.googleMsg : "המחשב מתחבר לגוגל צ'אט…";
        status.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) status.setText(msg);
        if (msg == null && !opening) { opening = true; LinkService.open(gid); }
    }

    @Override
    public void loaded(String g, boolean older, boolean more, String err) {
        if (!g.equals(gid)) return;
        if (older) loadingOlder = false; else opening = false;
        if (err != null && err.length() > 0) Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void maybeLoadOlder() {
        Chat c = Store.get(this).chat(gid);
        if (loadingOlder || c == null || c.noMore || !LinkService.connected()) return;
        long oldest = Long.MAX_VALUE;
        for (Msg m : Store.get(this).snapshot(gid)) if (m.id.length() > 0 && m.time < oldest) oldest = m.time;
        if (oldest == Long.MAX_VALUE) return;
        loadingOlder = true;
        LinkService.older(gid, oldest);
        adapter.notifyDataSetChanged();
    }

    private final Runnable markRead = () -> {
        Chat c = Store.get(this).chat(gid);
        if (c == null || !gid.equals(visibleGid)) return;
        long last = 0;
        for (Msg m : Store.get(this).snapshot(gid)) if (m.state == Msg.OK && m.time > last) last = m.time;
        last = Math.max(last, c.sort);
        if (c.hasUnread() || last > c.lastRead) {
            if (last > c.readSent || c.unread > 0) ReplyReceiver.markRead(this, gid);
        }
    };

    private void markReadSoon() {
        h.removeCallbacks(markRead);
        h.postDelayed(markRead, 700);
    }

    // ---------------------------------------------------------------- רשימת ההודעות

    private void reload(boolean first) {
        List<Msg> all = Store.get(this).snapshot(gid);
        boolean atBottom = list.getLastVisiblePosition() >= items.size() - 2;
        items.clear();
        items.add("top");
        long lastDay = 0;
        boolean newShown = false;
        // כמה תשובות שלא נקראו יש בכל שרשור (לסיכום שמתחת להודעה)
        threadUnread.clear();
        for (Msg m : all)
            if (m.in && !m.me && m.time > unreadSince) {
                Integer c = threadUnread.get(m.th);
                threadUnread.put(m.th, c == null ? 1 : c + 1);
            }
        for (Msg m : all) {
            if (thread == null ? m.in : !thread.equals(m.th)) continue;
            if (lastDay == 0 || !Fmt.sameDay(lastDay, m.time)) { items.add(m.time); lastDay = m.time; }
            if (!newShown && !m.me && !m.sys && m.id.length() > 0 && m.time > unreadSince) { items.add("new"); newShown = true; }
            items.add(m);
        }
        adapter.notifyDataSetChanged();
        if (first || atBottom) list.setSelection(items.size() - 1);
    }

    /** האם ההודעה פותחת קבוצה (שולח אחר / הפסקה של יותר מ-5 דקות / יום חדש). */
    private boolean startsGroup(int pos) {
        Msg m = (Msg) items.get(pos);
        Object prev = pos > 0 ? items.get(pos - 1) : null;
        if (!(prev instanceof Msg)) return true;
        Msg p = (Msg) prev;
        return p.me != m.me || !p.s.equals(m.s) || m.time - p.time > 5L * 60 * 1000000 || p.sys || m.sys;
    }

    private boolean endsGroup(int pos) {
        if (pos + 1 >= items.size() || !(items.get(pos + 1) instanceof Msg)) return true;
        return startsGroup(pos + 1);
    }

    private void send() {
        Editable e = input.getText();
        String raw = e.toString();
        int lead = 0;
        while (lead < raw.length() && Character.isWhitespace(raw.charAt(lead))) lead++;
        String text = raw.trim();
        JSONArray f = Rich.extract(e, lead, text.length());
        if (editing != null) {
            if (text.length() == 0) { Toast.makeText(this, "אי אפשר לשמור הודעה ריקה", Toast.LENGTH_SHORT).show(); return; }
            if (!LinkService.edit(this, editing, text, f)) { Toast.makeText(this, "הנגן לא מחובר למחשב", Toast.LENGTH_SHORT).show(); return; }
            setEditing(null);
            input.setText("");
            return;
        }
        if (text.length() == 0 && attachment == null) return;
        java.io.File img = attachment;
        input.setText("");
        Rich.clearEmpty(input.getText());
        setAttachment(null);
        LinkService.sendMessage(this, gid, text, thread, quoting, f, img);
        setQuote(null);
        list.setSelection(items.size() - 1);
        if (!LinkService.connected()) Toast.makeText(this, "הנגן לא מחובר למחשב — ההודעה תסומן כלא נשלחה", Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(Msg m) {
        Sheet s = Sheet.create(this, "למחוק את ההודעה?");
        s.text("ההודעה תימחק לכל המשתתפים בצ'אט.");
        s.item(Glyph.DELETE, "מחיקה", Th.RED, false, () -> {
            if (!LinkService.delete(this, m)) { Toast.makeText(this, "הנגן לא מחובר למחשב", Toast.LENGTH_SHORT).show(); return; }
            Store.get(this).markDeleted(gid, m.id);
        });
        s.item(-1, "ביטול", null);
        s.show();
    }

    // ---------------------------------------------------------------- פעולות על הודעה

    private void msgMenu(Msg m) {
        Sheet s = Sheet.create(this, null);
        if (m.id.length() > 0 && !m.del) {
            LinearLayout row = Th.row(this);
            row.setGravity(Gravity.CENTER);
            row.setPadding(Th.dp(this, 12), 0, Th.dp(this, 12), Th.dp(this, 8));
            final Sheet fs = s;
            for (String e : QUICK) {
                TextView t = Th.center(Th.text(this, e, 21, Th.TEXT, false));
                boolean mine = myReaction(m, e);
                t.setBackgroundDrawable(Th.press(Th.round(mine ? Th.CHIP_ME : Th.SURFACE2, Th.dp(this, 20)), Th.round(Th.OUTLINE, Th.dp(this, 20))));
                t.setOnClickListener(v -> { fs.dismiss(); react(m, e); });
                LinearLayout.LayoutParams p = Th.lp(Th.dp(this, 40), Th.dp(this, 40));
                p.setMargins(Th.dp(this, 3), 0, Th.dp(this, 3), 0);
                row.addView(t, p);
            }
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            Th.rtl(hs);
            hs.addView(row);
            s.view(hs);
        }
        // כמו בתפריט של גוגל צ'אט: עריכה, תשובה בשרשור, ציטוט, העתקה, מחיקה
        boolean real = m.id.length() > 0 && !m.sys && !m.del;
        if (real && m.me && m.text.length() > 0) s.item(Glyph.EDIT, "עריכה", () -> setEditing(m));
        if (real && thread == null && chat != null && !chat.dm)
            s.item(Glyph.THREAD, "הוספת תשובה לשרשור", () -> openThread(m));
        if (real) s.item(Glyph.REPLY, "ציטוט בתשובה", () -> setQuote(m));
        if (m.text.length() > 0) s.item(Glyph.COPY, "העתקת הטקסט", () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "הטקסט הועתק", Toast.LENGTH_SHORT).show();
        });
        if (real && m.me) s.item(Glyph.DELETE, "מחיקה", Th.RED, false, () -> confirmDelete(m));
        if (m.state == Msg.FAILED) {
            s.item(Glyph.REFRESH, "שליחה חוזרת", () -> LinkService.resend(this, m));
            s.item(Glyph.CLOSE, "מחיקה מהנגן", Th.RED, false, () -> Store.get(this).removeMsg(gid, m));
        }
        s.show();
    }

    /** לחיצה על ציטוט: קופצים להודעה המקורית (אם היא כבר נטענה). */
    private void jumpTo(String id) {
        if (id == null || id.length() == 0) return;
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (o instanceof Msg && id.equals(((Msg) o).id)) { list.setSelectionFromTop(i, Th.dp(this, 80)); return; }
        }
        Toast.makeText(this, "ההודעה המקורית לא נטענה עדיין", Toast.LENGTH_SHORT).show();
    }

    /** הודעה שכולה אימוג'י, עד 3 — מוצגת בגדול. */
    static boolean jumbo(String s) {
        s = s.trim();
        if (s.length() == 0 || s.length() > 40) return false;
        int count = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || (cp >= 0x1F3FB && cp <= 0x1F3FF) || Character.isWhitespace(cp)) continue;
            boolean emoji = (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B00 && cp <= 0x2BFF)
                    || (cp >= 0x2190 && cp <= 0x21FF) || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
            if (!emoji) return false;
            count++;
        }
        return count > 0 && count <= 3;
    }

    /** לחיצה על תמונת פרופיל בקבוצה: הצ'אט האישי שלי עם אותו אדם. */
    private void openDm(String user) {
        if (chat == null || user.length() == 0 || user.equals(chat.acc())) return;
        Store st = Store.get(this);
        for (Chat c : st.chatList(chat.acc()))
            if (c.dm && c.users.length == 1 && c.users[0].equals(user)) { openChat(c.gid); return; }
        if (!LinkService.connected()) { Toast.makeText(this, "הנגן לא מחובר למחשב", Toast.LENGTH_SHORT).show(); return; }
        LinkService.dmCallback = (u, g, err) -> {
            if (!u.equals(user)) return;
            LinkService.dmCallback = null;
            if (g.length() > 0) openChat(g);
            else if (err != null && err.length() > 0) Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, "עוד אין צ'אט אישי עם " + Store.get(this).userName(user) + ". אפשר להתחיל אותו בגוגל צ'אט.", Toast.LENGTH_LONG).show();
        };
        LinkService.findDm(gid, user);
        Toast.makeText(this, "מחפש את הצ'אט עם " + st.userName(user) + "…", Toast.LENGTH_SHORT).show();
    }

    private void openChat(String g) {
        startActivity(new Intent(this, ChatActivity.class).putExtra("g", g));
        overridePendingTransition(il.btchat.player.R.anim.in_left, il.btchat.player.R.anim.stay);
    }

    private void openThread(Msg m) {
        startActivity(new Intent(this, ChatActivity.class).putExtra("g", gid).putExtra("th", m.th.length() > 0 ? m.th : m.id));
        overridePendingTransition(il.btchat.player.R.anim.in_left, il.btchat.player.R.anim.stay);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(il.btchat.player.R.anim.stay, il.btchat.player.R.anim.out_left);
    }

    private static boolean myReaction(Msg m, String e) {
        if (m.r == null) return false;
        for (int i = 0; i < m.r.length(); i++) {
            JSONObject o = m.r.optJSONObject(i);
            if (o != null && e.equals(o.optString("e")) && o.optBoolean("m")) return true;
        }
        return false;
    }

    /** מוסיף או מסיר תגובה, ומעדכן מיד בתצוגה. */
    private void react(Msg m, String e) {
        if (!LinkService.connected()) { Toast.makeText(this, "הנגן לא מחובר למחשב", Toast.LENGTH_SHORT).show(); return; }
        boolean remove = myReaction(m, e);
        try {
            JSONArray r = m.r == null ? new JSONArray() : m.r;
            JSONArray out = new JSONArray();
            boolean found = false;
            for (int i = 0; i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                if (e.equals(o.optString("e"))) {
                    found = true;
                    int n = o.optInt("n") + (remove ? -1 : 1);
                    if (n > 0) out.put(new JSONObject().put("e", e).put("n", n).put("m", !remove));
                } else out.put(o);
            }
            if (!found && !remove) out.put(new JSONObject().put("e", e).put("n", 1).put("m", true));
            m.r = out;
        } catch (Exception ignored) { }
        LinkService.react(gid, m, e, remove);
        adapter.notifyDataSetChanged();
    }

    private void showImage(Msg m, int i) {
        java.io.File f = Pics.imageFile(this, m.id, i);
        Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath());
        if (b == null) return;
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        iv.setOnClickListener(v -> d.dismiss());
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(iv);
        d.show();
    }

    // ---------------------------------------------------------------- תצוגת הודעה

    private final class MsgRow extends LinearLayout {
        final AvatarView av;
        final View avSpace;
        final LinearLayout col, bubble, images;
        LinearLayout voices;
        final float[] frame = new float[4];   // פינות מסגרת התמונה: שמאל-עליון, ימין-עליון, ימין-תחתון, שמאל-תחתון

        /** פינות התמונה בתוך המסגרת: כמו המסגרת, פחות הריפוד (פינה ישרה נשארת ישרה). */
        RoundBmp round(Bitmap b) {
            float pad = Th.dp(getContext(), 4);
            return new RoundBmp(b, Math.max(0, frame[0] - pad), Math.max(0, frame[1] - pad), Math.max(0, frame[2] - pad), Math.max(0, frame[3] - pad));
        }
        final TextView header, text, note, foot, threadChip;
        final LinearLayout threadRow, threadAvs;
        final LinearLayout quote;
        final TextView qName, qText;
        final Flow reacts;
        Msg msg;

        MsgRow() {
            super(ChatActivity.this);
            setOrientation(HORIZONTAL);
            Th.rtl(this);
            av = new AvatarView(getContext());
            LayoutParams ap = Th.lp(Th.dp(getContext(), 32), Th.dp(getContext(), 32));
            ap.topMargin = Th.dp(getContext(), 20);
            addView(av, ap);
            avSpace = new View(getContext());
            addView(avSpace, Th.lp(Th.dp(getContext(), 32), 1));
            col = Th.col(getContext());
            LayoutParams cp = Th.lp(0, -2, 1);
            cp.setMargins(Th.dp(getContext(), 8), 0, Th.dp(getContext(), 8), 0);
            addView(col, cp);

            header = Th.text(getContext(), "", 12.5f, Th.SUB, false);
            header.setPadding(Th.dp(getContext(), 4), Th.dp(getContext(), 8), Th.dp(getContext(), 4), Th.dp(getContext(), 3));
            col.addView(header, Th.lp(-2, -2));

            bubble = new Bubble();
            bubble.setPadding(Th.dp(getContext(), 12), Th.dp(getContext(), 8), Th.dp(getContext(), 12), Th.dp(getContext(), 8));
            col.addView(bubble, Th.lp(-2, -2));

            // ציטוט: כרטיס פנימי בתוך הבועה, כמו בגוגל צ'אט
            // כרטיס לבן ברוחב הבועה: למעלה סימן ציטוט + שם (אפור, קטן), מתחת הטקסט המצוטט בגודל רגיל
            quote = Th.row(getContext());
            quote.setBackgroundDrawable(Th.round(Th.QUOTE_BG, Th.dp(getContext(), 12)));
            LinearLayout qcol = Th.col(getContext());
            qcol.setPadding(Th.dp(getContext(), 12), Th.dp(getContext(), 8), Th.dp(getContext(), 12), Th.dp(getContext(), 10));
            LinearLayout qhead = Th.row(getContext());
            Glyph qmark = new Glyph(getContext(), Glyph.QUOTE, Th.HINT);
            qhead.addView(qmark, Th.lp(Th.dp(getContext(), 16), Th.dp(getContext(), 16)));
            qName = Th.text(getContext(), "", 12.5f, Th.SUB, false);
            qName.setPadding(Th.dp(getContext(), 6), 0, Th.dp(getContext(), 6), 0);
            qhead.addView(qName);
            qText = Th.text(getContext(), "", 15, Th.TEXT, false);
            Th.autoDir(qText);
            qText.setMaxLines(3);
            qText.setEllipsize(TextUtils.TruncateAt.END);
            qText.setPadding(0, Th.dp(getContext(), 4), 0, 0);
            qText.setMaxWidth(bubbleMax - Th.dp(getContext(), 48));
            qcol.addView(qhead);
            qcol.addView(qText, Th.lp(-2, -2));
            quote.addView(qcol, Th.lp(-2, -2));
            // ברוחב כל הבועה: LinearLayout ב-match_parent נמדד לפי התוכן, ואז נמתח לרוחב הבועה
            LayoutParams qp = Th.lp(-2, -2);
            qp.bottomMargin = Th.dp(getContext(), 8);
            bubble.addView(quote, qp);
            ((Bubble) bubble).stretch = quote;
            voices = Th.col(getContext());
            bubble.addView(voices, Th.lp(-2, -2));

            // תמונות: מסגרת משלהן בצבע הבועה, מתחת לטקסט (כמו בגוגל צ'אט)
            images = Th.col(getContext());
            int fp = Th.dp(getContext(), 4);
            images.setPadding(fp, fp, fp, fp);
            LayoutParams ip = Th.lp(-2, -2);
            ip.topMargin = Th.dp(getContext(), 3);
            col.addView(images, col.indexOfChild(bubble) + 1, ip);

            text = Th.text(getContext(), "", 15.5f, Th.TEXT, false);
            Th.autoDir(text);
            text.setLineSpacing(0, 1.12f);
            text.setMaxWidth(bubbleMax - Th.dp(getContext(), 24));
            if (android.os.Build.VERSION.SDK_INT >= 11) text.setTextIsSelectable(false);
            bubble.addView(text, Th.lp(-2, -2));

            note = Th.text(getContext(), "", 13.5f, Th.SUB, false);
            note.setMaxWidth(bubbleMax - Th.dp(getContext(), 24));
            bubble.addView(note, Th.lp(-2, -2));

            foot = Th.text(getContext(), "", 11.5f, Th.SUB, false);
            foot.setPadding(0, Th.dp(getContext(), 3), 0, 0);
            bubble.addView(foot, Th.lp(-2, -2));

            // התגובות: ברוחב התוכן, כך שהן נצמדות לצד של הבועה (שלי — שמאל, של אחרים — ימין)
            reacts = new Flow(getContext());
            Th.rtl(reacts);
            LayoutParams rp = Th.lp(-2, -2);
            rp.topMargin = Th.dp(getContext(), 4);
            col.addView(reacts, rp);

            // סיכום שרשור: תמונות של מי שענה + טקסט
            threadRow = Th.row(getContext());
            threadRow.setPadding(Th.dp(getContext(), 4), Th.dp(getContext(), 6), Th.dp(getContext(), 6), Th.dp(getContext(), 6));
            threadRow.setBackgroundDrawable(Th.ripple(Th.dp(getContext(), 8)));
            threadAvs = Th.row(getContext());
            threadRow.addView(threadAvs);
            threadChip = Th.text(getContext(), "", 13.5f, Th.SUB, false);
            threadChip.setPadding(Th.dp(getContext(), 8), 0, Th.dp(getContext(), 8), 0);
            threadRow.addView(threadChip, Th.lp(-2, -2));
            col.addView(threadRow, Th.lp(-2, -2));

            bubble.setOnLongClickListener(v -> { if (msg != null) msgMenu(msg); return true; });
            bubble.setOnClickListener(v -> {
                if (msg != null && msg.state == Msg.FAILED) LinkService.resend(ChatActivity.this, msg);
            });
            threadRow.setOnClickListener(v -> { if (msg != null) openThread(msg); });
            av.setOnClickListener(v -> { if (msg != null) openDm(msg.s); });
            quote.setOnClickListener(v -> { if (msg != null && msg.q != null) jumpTo(msg.q.optString("id")); });
            setWillNotDraw(false);
            slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            trigger = Th.dp(getContext(), 56);
        }

        // ---------- החלקה הצידה = ציטוט בתשובה

        private float downX, downY, dragX;
        private boolean dragging, armed;
        private final int slop, trigger;

        private boolean swipeable() { return msg != null && !msg.sys && !msg.del && msg.id.length() > 0; }

        private boolean startDrag(float dx, float dy) {
            if (dragging || !swipeable()) return dragging;
            if (Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                dragging = true;
                armed = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
            return dragging;
        }

        private void setDrag(float x) {
            dragX = x;
            av.setTranslationX(x);
            avSpace.setTranslationX(x);
            col.setTranslationX(x);
            boolean nowArmed = Math.abs(x) >= trigger;
            if (nowArmed && !armed && dragging) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            armed = nowArmed;
            invalidate();
        }

        private void release(boolean commit) {
            if (commit && armed && msg != null) setQuote(msg);
            dragging = false;
            armed = false;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(dragX, 0);
            a.setDuration(180);
            a.addUpdateListener(an -> setDrag((Float) an.getAnimatedValue()));
            a.start();
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); dragging = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (startDrag(e.getX() - downX, e.getY() - downY)) return true;
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); dragging = false;
                    return swipeable();
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (startDrag(dx, e.getY() - downY)) {
                        float max = Th.dp(getContext(), 80);
                        setDrag(Math.signum(dx) * Math.min(max, Math.abs(dx) * 0.6f));
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (dragging) release(true);
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (dragging) release(false);
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas c) {
            super.dispatchDraw(c);
            if (dragX == 0) return;
            // אייקון הציטוט מופיע בצד שהתפנה
            float prog = Math.min(1f, Math.abs(dragX) / trigger);
            float size = Th.dp(getContext(), 22) * (0.6f + 0.4f * prog);
            float half = Math.max(Th.dp(getContext(), 22), Math.abs(dragX) / 2f);
            float cx = dragX > 0 ? half : getWidth() - half;
            float cy = col.getTop() + col.getHeight() / 2f;
            android.graphics.Paint bgp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            bgp.setColor(Th.alpha(armed ? Th.PRIMARY_CONT : Th.SURFACE2, (int) (255 * prog)));
            c.drawCircle(cx, cy, Th.dp(getContext(), 17), bgp);
            Glyph.draw(c, Glyph.REPLY, cx, cy, size, Th.alpha(armed ? Th.ON_PRIMARY_CONT : Th.SUB, (int) (255 * prog)));
        }

        void bind(int pos) {
            Msg m = (Msg) items.get(pos);
            msg = m;
            Store st = Store.get(getContext());
            boolean space = chat != null && !chat.dm;
            boolean first = startsGroup(pos), last = endsGroup(pos);
            boolean other = !m.me;

            // תמונת השולח (רק במרחבים, בהודעה הראשונה בקבוצה)
            boolean showAv = space && other;
            av.setVisibility(showAv && first ? View.VISIBLE : View.GONE);
            avSpace.setVisibility(showAv && !first ? View.VISIBLE : View.GONE);
            if (showAv && first) av.set(st.userName(m.s), st.userAvatar(m.s), false);
            col.setGravity(m.me ? Gravity.END : Gravity.START);
            setPadding(Th.dp(getContext(), 8), 0, Th.dp(getContext(), 8), last ? Th.dp(getContext(), 6) : Th.dp(getContext(), 2));

            if (m.sys) {
                // הודעת מערכת: טקסט קטן במרכז
                header.setVisibility(View.GONE);
                av.setVisibility(View.GONE);
                avSpace.setVisibility(View.GONE);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                bubble.setBackgroundDrawable(null);
                quote.setVisibility(View.GONE);
                images.removeAllViews();
                images.setVisibility(View.GONE);
                bubble.setVisibility(View.VISIBLE);
                voices.removeAllViews();
                voices.setVisibility(View.GONE);
                String who = st.userName(m.s);
                text.setVisibility(View.VISIBLE);
                text.setText((who.length() > 0 ? who + " · " : "") + (m.note.length() > 0 ? m.note : m.text));
                text.setTextColor(Th.SUB);
                text.setTextSize(13);
                note.setVisibility(View.GONE);
                foot.setVisibility(View.GONE);
                reacts.setVisibility(View.GONE);
                threadRow.setVisibility(View.GONE);
                return;
            }
            text.setTextSize(15.5f);

            if (first) {
                String time = Fmt.rel(m.time);
                String who = other && (space || thread != null) ? st.userName(m.s) : "";
                header.setText(who.length() > 0 ? who + "  ·  " + time : time);
                header.setVisibility(View.VISIBLE);
            } else header.setVisibility(View.GONE);

            if (m.del) {
                // הודעה שנמחקה: רק סימון אפור, בלי בועה
                bubble.setBackgroundDrawable(null);
                quote.setVisibility(View.GONE);
                images.removeAllViews();
                images.setVisibility(View.GONE);
                bubble.setVisibility(View.VISIBLE);
                voices.removeAllViews();
                voices.setVisibility(View.GONE);
                text.setVisibility(View.VISIBLE);
                text.setText("🚫  ההודעה נמחקה");
                text.setTextColor(Th.SUB);
                text.setTypeface(android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC));
                note.setVisibility(View.GONE);
                foot.setVisibility(View.GONE);
                reacts.setVisibility(View.GONE);
                threadRow.setVisibility(View.GONE);
                return;
            }

            // בועה: פינה "מחודדת" בצד של השולח, רק בהודעה האחרונה בקבוצה
            float r = Th.dp(getContext(), 18), sr = Th.dp(getContext(), 4);
            // בזמן שליחה: תכלת בהיר יותר, וכשגוגל מאשרת — הצבע הרגיל (כמו בגוגל צ'אט)
            int bg = m.me ? (m.state == Msg.SENDING ? Th.PENDING : Th.PRIMARY_CONT) : Th.BUBBLE_IN;
            boolean rtlStart = true; // בעברית הצד של "אחרים" הוא ימין
            float tl = r, tr = r, br = r, bl = r;
            if (m.me) { if (!first) tl = sr; if (!last) bl = sr; else bl = sr; }
            else { if (!first) tr = sr; if (!last) br = sr; else br = sr; }
            if (!rtlStart) { float t = tl; tl = tr; tr = t; t = bl; bl = br; br = t; }
            if (m.state == Msg.FAILED) bubble.setBackgroundDrawable(Th.corners(Th.alpha(Th.RED, 0x22), tl, tr, br, bl));
            else bubble.setBackgroundDrawable(Th.press(Th.corners(bg, tl, tr, br, bl), Th.corners(Th.alpha(Th.TEXT, 0x22), tl, tr, br, bl)));
            text.setTextColor(m.me ? Th.ON_PRIMARY_CONT : Th.ON_BUBBLE_IN);

            // ציטוט
            if (m.q != null && (m.q.optString("text").length() > 0)) {
                quote.setVisibility(View.VISIBLE);
                String qs = m.q.optString("s");
                String qn = chat != null && qs.equals(chat.acc()) ? "את/ה" : st.userName(qs);
                qName.setText(qn.length() > 0 ? qn : "הודעה");
                qText.setText(m.q.optString("text"));
            } else quote.setVisibility(View.GONE);

            // מסגרת התמונה "ממשיכה" את ההודעה: הפינה בצד השולח שצמודה לטקסט שמעליה ישרה
            boolean textAbove = m.text.length() > 0 || quote.getVisibility() == View.VISIBLE || m.note.length() > 0 || m.state == Msg.FAILED || m.ed;
            float ftl = r, ftr = r, fbr = r, fbl = r;
            if (m.me) { ftl = textAbove || !first ? sr : r; fbl = sr; }
            else { ftr = textAbove || !first ? sr : r; fbr = sr; }
            frame[0] = ftl; frame[1] = ftr; frame[2] = fbr; frame[3] = fbl;

            bindImages(m);
            bindVoices(m);

            String body = m.text;
            text.setVisibility(body.length() > 0 ? View.VISIBLE : View.GONE);
            text.setTypeface(android.graphics.Typeface.DEFAULT);
            text.setText(Rich.render(body, m.f, Th.alpha(Th.TEXT, 0x18)));
            text.setTextSize(jumbo(body) ? 34 : 15.5f);   // רק אימוג'י (עד 3): גדול, כמו בגוגל צ'אט
            note.setVisibility(m.note.length() > 0 ? View.VISIBLE : View.GONE);
            note.setText(m.note);

            String f = "";
            if (m.state == Msg.FAILED) f = "לא נשלח" + (m.err.length() > 0 ? " (" + m.err + ")" : "") + " · לחיצה לשליחה חוזרת";
            else if (m.ed) f = "נערך";
            foot.setVisibility(f.length() > 0 ? View.VISIBLE : View.GONE);
            foot.setText(f);
            foot.setTextColor(m.state == Msg.FAILED ? Th.RED : Th.SUB);

            // תמונה בלי טקסט: רק המסגרת של התמונה, בלי בועה ריקה מעליה
            boolean hasBubble = body.length() > 0 || quote.getVisibility() == View.VISIBLE || m.note.length() > 0 || f.length() > 0 || voices.getChildCount() > 0;
            bubble.setVisibility(hasBubble || images.getChildCount() == 0 ? View.VISIBLE : View.GONE);
            images.setBackgroundDrawable(Th.corners(m.state == Msg.FAILED ? Th.alpha(Th.RED, 0x22) : bg, frame[0], frame[1], frame[2], frame[3]));

            bindReactions(m);
            bindThread(m);
        }

        /** סיכום השרשור מתחת להודעה: תמונות של מי שענה, כמה לא נקראו, כמה תשובות ומתי. */
        void bindThread(Msg m) {
            if (thread != null || m.rc <= 0 || m.del) { threadRow.setVisibility(View.GONE); return; }
            threadRow.setVisibility(View.VISIBLE);
            Store st = Store.get(getContext());
            threadAvs.removeAllViews();
            int n = m.rp == null ? 0 : Math.min(3, m.rp.length());
            for (int i = 0; i < n; i++) {
                String uid = m.rp.optString(i);
                AvatarView a = new AvatarView(getContext());
                a.set(st.userName(uid), st.userAvatar(uid), false);
                LinearLayout.LayoutParams lp = Th.lp(Th.dp(getContext(), 20), Th.dp(getContext(), 20));
                if (i > 0) { lp.setMargins(-Th.dp(getContext(), 6), 0, -Th.dp(getContext(), 6), 0); if (android.os.Build.VERSION.SDK_INT >= 17) { lp.setMarginStart(-Th.dp(getContext(), 6)); lp.setMarginEnd(0); } }
                threadAvs.addView(a, lp);
            }
            threadAvs.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            Integer un = threadUnread.get(m.th);
            if (un != null && un > 0) {
                String u = un == 1 ? "הודעה אחת שעוד לא קראת" : un + " הודעות שעוד לא קראת";
                sb.append(u);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Th.LINK), 0, u.length(), 0);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, u.length(), 0);
                sb.append("   ");
            }
            int s0 = sb.length();
            sb.append(m.rc == 1 ? "תשובה אחת" : m.rc + " תשובות");
            if (m.rl > 0) sb.append("   ").append(Fmt.daysAgo(m.rl) <= 0 ? Fmt.rel(m.rl) : Fmt.listTime(m.rl));
            sb.setSpan(new android.text.style.ForegroundColorSpan(Th.SUB), s0, sb.length(), 0);
            threadChip.setText(sb);
        }

        /** הודעות קוליות: נגן בתוך הבועה. */
        void bindVoices(Msg m) {
            voices.removeAllViews();
            int n = m.au == null ? 0 : m.au.length();
            voices.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                JSONObject a = m.au.optJSONObject(i);
                if (a == null) continue;
                JSONArray wa = a.optJSONArray("w");
                int[] wave = new int[wa == null ? 0 : wa.length()];
                for (int j = 0; j < wave.length; j++) wave[j] = wa.optInt(j);
                java.io.File f;
                String lp = a.optString("local");
                if (m.id.length() == 0 && lp.length() > 0) f = new java.io.File(lp);
                else f = Pics.audioFile(getContext(), m.id, i);
                String lk = "aud:" + m.id + "#" + i;
                VoiceView vv = new VoiceView(getContext());
                Th.rtl(vv);
                vv.set(Voice.key(m.id.length() > 0 ? m.id : "lid:" + m.lid, i), f, wave, a.optLong("d"),
                        Pics.loading.contains(lk), Pics.failed.containsKey(lk), m.me,
                        () -> {
                            if (!LinkService.requestAudio(m, idx)) { Voice.pending = ""; Toast.makeText(getContext(), "הנגן לא מחובר למחשב", Toast.LENGTH_SHORT).show(); }
                            adapter.notifyDataSetChanged();
                        });
                vv.setOnLongClickListener(v -> { msgMenu(m); return true; });
                LinearLayout.LayoutParams lp2 = Th.lp(-2, -2);
                lp2.topMargin = i > 0 ? Th.dp(getContext(), 4) : 0;
                voices.addView(vv, lp2);
                String tr = a.optString("tr");
                if (tr.length() > 0) {
                    // התמלול של גוגל, אחרי קו מפריד (כמו בגוגל צ'אט)
                    View line = new View(getContext());
                    line.setBackgroundColor(Th.alpha(Th.TEXT, 0x22));
                    LinearLayout.LayoutParams ll = Th.lp(-1, 1);
                    ll.setMargins(0, Th.dp(getContext(), 6), 0, Th.dp(getContext(), 8));
                    voices.addView(line, ll);
                    TextView tv = Th.text(getContext(), tr, 14.5f, m.me ? Th.ON_PRIMARY_CONT : Th.ON_BUBBLE_IN, false);
                    Th.autoDir(tv);
                    tv.setMaxWidth(bubbleMax - Th.dp(getContext(), 24));
                    voices.addView(tv, Th.lp(-2, -2));
                }
            }
        }

        /** התמונות של ההודעה, במסגרת בצבע הבועה ובפינות מעוגלות (כמו בגוגל צ'אט). */
        void bindImages(Msg m) {
            images.removeAllViews();
            images.setVisibility(View.GONE);
            bubble.setVisibility(View.VISIBLE);
            int w = Math.min(bubbleMax - Th.dp(getContext(), 8), Th.dp(getContext(), 260));
            float rad = Th.dp(getContext(), 14);
            if (m.local.length() > 0 && m.id.length() == 0) {
                // תמונה שנשלחת עכשיו מהנגן
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap lb = android.graphics.BitmapFactory.decodeFile(m.local, o);
                if (lb != null) {
                    View iv = new View(getContext());
                    iv.setBackgroundDrawable(round(lb));
                    iv.setAlpha(m.state == Msg.SENDING ? 0.7f : 1f);
                    images.addView(iv, Th.lp(w, Math.min((int) (w * 1.3f), (int) (w * (lb.getHeight() / (float) lb.getWidth())))));
                    images.setVisibility(View.VISIBLE);
                }
                return;
            }
            int n = m.images();
            if (n == 0) return;
            images.setVisibility(View.VISIBLE);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                String key = m.id + "#" + i;
                Bitmap b = Pics.image(getContext(), m.id, i, w);
                LinearLayout.LayoutParams p;
                if (b != null) {
                    View iv = new View(getContext());
                    iv.setBackgroundDrawable(round(b));
                    int hgt = Math.min((int) (w * 1.3f), (int) (w * (b.getHeight() / (float) b.getWidth())));
                    iv.setOnClickListener(v -> showImage(m, idx));
                    iv.setOnLongClickListener(v -> { msgMenu(m); return true; });
                    p = Th.lp(w, hgt);
                    if (i > 0) p.topMargin = Th.dp(getContext(), 4);
                    images.addView(iv, p);
                    continue;
                }
                // עוד לא נטענה: מקום שמור באותה מסגרת, עם כפתור טעינה
                LinearLayout ph = Th.col(getContext());
                ph.setGravity(Gravity.CENTER);
                float p4 = Th.dp(getContext(), 4);
                float c0 = Math.max(0, frame[0] - p4), c1 = Math.max(0, frame[1] - p4), c2 = Math.max(0, frame[2] - p4), c3 = Math.max(0, frame[3] - p4);
                ph.setBackgroundDrawable(Th.press(Th.corners(Th.alpha(Th.TEXT, 0x0E), c0, c1, c2, c3), Th.corners(Th.alpha(Th.TEXT, 0x1E), c0, c1, c2, c3)));
                Glyph g = new Glyph(getContext(), Glyph.IMAGE, Th.SUB);
                ph.addView(g, Th.lp(Th.dp(getContext(), 34), Th.dp(getContext(), 34)));
                String label;
                if (Pics.loading.contains(key)) label = "טוען את התמונה…";
                else if (Pics.failed.containsKey(key)) label = "לא נטענה · לחיצה לניסיון נוסף";
                else label = "לחיצה לטעינת התמונה";
                TextView t = Th.center(Th.text(getContext(), label, 13, Th.SUB, false));
                t.setPadding(Th.dp(getContext(), 8), Th.dp(getContext(), 6), Th.dp(getContext(), 8), 0);
                ph.addView(t, Th.lp(-1, -2));
                ph.setOnClickListener(v -> {
                    if (!LinkService.requestImage(m, idx)) Toast.makeText(getContext(), "הנגן לא מחובר למחשב", Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                });
                ph.setOnLongClickListener(v -> { msgMenu(m); return true; });
                p = Th.lp(w, (int) (w * 0.62f));
                if (i > 0) p.topMargin = Th.dp(getContext(), 4);
                images.addView(ph, p);
            }
        }

        void bindReactions(Msg m) {
            reacts.removeAllViews();
            int n = m.r == null ? 0 : m.r.length();
            reacts.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < n; i++) {
                JSONObject o = m.r.optJSONObject(i);
                if (o == null) continue;
                String e = o.optString("e");
                boolean mine = o.optBoolean("m");
                // שבב מרובע (פינות קטנות), כמו בגוגל צ'אט: אפור — של אחרים, כחול עם מסגרת — שלי
                TextView chip = Th.center(Th.text(getContext(), e + " " + o.optInt("n"), 13.5f, mine ? (Th.dark ? Th.ON_PRIMARY_CONT : Th.PRIMARY) : Th.TEXT, mine));
                chip.setPadding(Th.dp(getContext(), 8), 0, Th.dp(getContext(), 8), 0);
                chip.setMinHeight(Th.dp(getContext(), 28));
                chip.setBackgroundDrawable(mine ? Th.round(Th.CHIP_ME, Th.dp(getContext(), 8), Th.PRIMARY, Th.dp(getContext(), 1))
                        : Th.round(Th.dark ? Th.SURFACE2 : 0xFFE9EEF6, Th.dp(getContext(), 8)));
                chip.setOnClickListener(v -> react(m, e));
                reacts.addView(chip, new ViewGroup.LayoutParams(-2, Th.dp(getContext(), 28)));
            }
            if (n > 0 && m.id.length() > 0) {
                // כפתור קטן להוספת תגובה, ליד התגובות (כמו בגוגל צ'אט)
                Glyph add = new Glyph(getContext(), Glyph.ADD_REACTION, Th.SUB);
                int p = Th.dp(getContext(), 5);
                add.setPadding(p, p, p, p);
                add.setBackgroundDrawable(Th.press(Th.round(0, Th.dp(getContext(), 8)), Th.round(Th.SURFACE2, Th.dp(getContext(), 8))));
                add.setOnClickListener(v -> msgMenu(m));
                // ראשון ברשימה = הכי ימני (הפריסה מימין לשמאל): הכפתור מימין לתגובות
                reacts.addView(add, 0, new ViewGroup.LayoutParams(Th.dp(getContext(), 28), Th.dp(getContext(), 28)));
            }
        }
    }

    /**
     * בועת ההודעה. הציטוט שבתוכה נמדד קודם לפי התוכן שלו (כך שציטוט ארוך מאריך את הבועה),
     * ואחר כך נמתח לרוחב כל הבועה.
     */
    private final class Bubble extends LinearLayout {
        View stretch;

        Bubble() {
            super(ChatActivity.this);
            setOrientation(VERTICAL);
            Th.rtl(this);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            if (stretch != null) stretch.getLayoutParams().width = LayoutParams.WRAP_CONTENT;
            super.onMeasure(ws, hs);
            if (stretch == null || stretch.getVisibility() == GONE) return;
            int inner = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
            if (stretch.getMeasuredWidth() < inner) {
                stretch.getLayoutParams().width = inner;
                super.onMeasure(ws, hs);
            }
        }
    }

    /** טקסט באמצע עם קו משני הצדדים. */
    private final class LineLabel extends TextView {
        private final android.graphics.Paint lp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        LineLabel() {
            super(ChatActivity.this);
            setTextSize(12.5f);
            setTextColor(Th.PRIMARY);
            setGravity(Gravity.CENTER);
            setPadding(0, Th.dp(getContext(), 10), 0, Th.dp(getContext(), 6));
            lp.setColor(Th.PRIMARY);
            lp.setStrokeWidth(Th.dp(getContext(), 1));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float tw = getPaint().measureText(getText().toString());
            float cy = (getHeight() + getPaddingTop() - getPaddingBottom()) / 2f;
            float gap = Th.dp(getContext(), 10), side = Th.dp(getContext(), 16);
            c.drawLine(side, cy, (getWidth() - tw) / 2f - gap, cy, lp);
            c.drawLine((getWidth() + tw) / 2f + gap, cy, getWidth() - side, cy, lp);
            super.onDraw(c);
        }
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int p) { return items.get(p); }
        public long getItemId(int p) { return p; }
        public int getViewTypeCount() { return 4; }

        public int getItemViewType(int p) {
            Object o = items.get(p);
            return o instanceof Msg ? 2 : o instanceof Long ? 1 : "new".equals(o) ? 3 : 0;
        }

        public boolean isEnabled(int p) { return false; }

        public View getView(int p, View v, ViewGroup parent) {
            Object o = items.get(p);
            if (o instanceof Msg) {
                MsgRow r = v instanceof MsgRow ? (MsgRow) v : new MsgRow();
                r.bind(p);
                return r;
            }
            if ("new".equals(o)) {
                // קו "הודעות שלא נקראו"
                LineLabel l = v instanceof LineLabel ? (LineLabel) v : new LineLabel();
                l.setText("הודעות שלא נקראו");
                return l;
            }
            TextView t = v instanceof TextView ? (TextView) v : Th.center(Th.text(ChatActivity.this, "", 12.5f, Th.SUB, true));
            if (o instanceof Long) {
                t.setText(Fmt.day((Long) o));
                t.setPadding(0, Th.dp(ChatActivity.this, 14), 0, Th.dp(ChatActivity.this, 6));
                t.setTextColor(Th.SUB);
                t.setOnClickListener(null);
                t.setClickable(false);
                return t;
            }
            // שורת הטעינה למעלה
            Chat c = Store.get(ChatActivity.this).chat(gid);
            boolean empty = items.size() <= 1;
            String s;
            if (thread != null) s = "";
            else if (loadingOlder) s = "טוען הודעות קודמות…";
            else if (empty && opening) s = "טוען הודעות…";
            else if (empty) s = LinkService.connected() ? "אין עדיין הודעות" : "ההודעות ייטענו כשהנגן יתחבר למחשב";
            else if (c != null && c.noMore) s = "תחילת השיחה";
            else s = LinkService.connected() ? "טעינת הודעות קודמות" : "";
            t.setText(s);
            t.setTextColor(!loadingOlder && c != null && !c.noMore && !empty && LinkService.connected() ? Th.LINK : Th.SUB);
            t.setPadding(0, Th.dp(ChatActivity.this, s.length() > 0 ? 12 : 0), 0, Th.dp(ChatActivity.this, s.length() > 0 ? 8 : 0));
            t.setClickable(true);
            t.setOnClickListener(x -> maybeLoadOlder());
            return t;
        }
    }
}
