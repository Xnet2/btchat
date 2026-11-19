package il.btchat;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import il.btchat.Model.Chat;
import il.btchat.Model.Msg;

/**
 * שירות שרץ ברקע ומחכה שהמחשב יתחבר בבלוטות'.
 * כל הודעה בין הנגן למחשב היא שורת JSON.
 */
public final class LinkService extends Service {
    static final UUID SERVICE_UUID = UUID.fromString("6f1c2a8e-3b7d-4e59-a1c4-9d8e7f6a5b32");
    static final int OFF = 0, WAITING = 1, CONNECTED = 2;

    interface Listener { void onLinkChanged(); }

    /** תשובה לבקשת טעינת הודעות (פתיחת צ'אט או הודעות קודמות). */
    interface LoadCallback { void loaded(String gid, boolean older, boolean more, String err); }

    static volatile LinkService self;
    static volatile Listener listener;
    static volatile LoadCallback loadCallback;

    /** תשובה לבקשת הצ'אט האישי עם משתמש (g ריק = אין צ'אט כזה). */
    interface DmCallback { void dm(String user, String gid, String err); }
    static volatile DmCallback dmCallback;

    static boolean findDm(String chatGid, String user) {
        return send(json("t", "dm", "g", chatGid, "u", user));
    }
    static volatile int state = WAITING;
    static volatile String peer = "";

    private Handler main;
    private volatile boolean running;
    private Thread acceptThread;
    private BluetoothServerSocket server;
    private volatile BluetoothSocket conn;
    private volatile OutputStream out;
    private final Object writeLock = new Object();
    private volatile long lastRecv;

    static void start(Context c) {
        Intent i = new Intent(c, LinkService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    static boolean connected() { return self != null && state == CONNECTED; }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        main = new Handler(Looper.getMainLooper());
        Th.load(this);
        Notifier.channels(this);
        startForeground(Notifier.ONGOING_ID, Notifier.ongoing(this, statusText()));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "btchat-accept");
        acceptThread.start();
        registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        main.postDelayed(watchdog, 30000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        self = null;
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) { }
        main.removeCallbacksAndMessages(null);
        closeConn();
        closeServer();
        if (acceptThread != null) acceptThread.interrupt();
        Store.get(this).saveNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- מצב

    String statusText() {
        if (state == CONNECTED) {
            if (!Store.googleOk) return "מחובר למחשב · " + (Store.googleMsg.length() > 0 ? Store.googleMsg : "גוגל צ'אט לא מחובר");
            return "מחובר" + (peer.length() > 0 ? " · " + peer : "");
        }
        if (state == OFF) return "הבלוטות' כבוי";
        return "ממתין לחיבור מהמחשב";
    }

    void refreshOngoing() {
        try { Notifier.updateOngoing(this, statusText()); } catch (Exception ignored) { }
    }

    private void linkChanged() {
        main.post(() -> {
            refreshOngoing();
            Listener l = listener;
            if (l != null) l.onLinkChanged();
        });
    }

    private void setState(int s) {
        if (state == s) return;
        state = s;
        linkChanged();
    }

    // ---------------------------------------------------------------- בקשות מהממשק

    static boolean open(String gid) {
        return send(json("t", "open", "g", gid));
    }

    static boolean older(String gid, long before) {
        return send(json("t", "older", "g", gid, "before", before));
    }

    /** שולח הודעה (נשמרת מיד בנגן כ"שולח…"). */
    static Msg sendMessage(Context c, String gid, String text, String thread) {
        return sendMessage(c, gid, text, thread, null, null, null);
    }

    static Msg sendMessage(Context c, String gid, String text, String thread, Msg quote) {
        return sendMessage(c, gid, text, thread, quote, null, null);
    }

    /**
     * quote (לא חובה) = ההודעה שמצטטים בתשובה. fmt = עיצוב הטקסט [[התחלה, אורך, סוג]].
     * image (לא חובה) = קובץ JPEG מקומי לשליחה עם ההודעה.
     */
    static Msg sendMessage(Context c, String gid, String text, String thread, Msg quote, JSONArray fmt, java.io.File image) {
        Msg m = Store.get(c).addPending(gid, text, thread);
        m.f = fmt != null && fmt.length() > 0 ? fmt : null;
        String qid = "", qth = "";
        long qt = 0;
        if (quote != null && quote.id.length() > 0) {
            qid = quote.id; qth = quote.th; qt = quote.time;
            try { m.q = new JSONObject().put("s", quote.s).put("text", quote.preview()).put("id", quote.id).put("t", quote.time).put("th", quote.th); } catch (Exception ignored) { }
        }
        boolean ok;
        if (image != null) {
            m.local = image.getPath();
            Store.get(c).touched(gid);
            String data;
            try {
                byte[] b = new byte[(int) image.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(image)) { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } }
                data = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                Store.get(c).sent(gid, m.lid, false, null, "לא ניתן לקרוא את התמונה");
                return m;
            }
            ok = send(json("t", "upload", "g", gid, "text", text, "th", thread == null ? "" : thread, "lid", m.lid, "qid", qid, "qth", qth, "qt", qt,
                    "f", m.f == null ? new JSONArray() : m.f, "name", "image.jpg", "ct", "image/jpeg", "data", data));
        } else {
            ok = send(json("t", "send", "g", gid, "text", text, "th", thread == null ? "" : thread, "lid", m.lid, "qid", qid, "qth", qth, "qt", qt,
                    "f", m.f == null ? new JSONArray() : m.f));
        }
        if (!ok) Store.get(c).sent(gid, m.lid, false, null, "הנגן לא מחובר למחשב");
        return m;
    }

    /** עריכת הודעה שלי. מעדכן מיד בנגן, והמחשב מחזיר את הגרסה הסופית. */
    static boolean edit(Context c, Msg m, String text, JSONArray fmt) {
        if (!send(json("t", "edit", "g", m.gid, "th", m.th, "id", m.id, "text", text, "f", fmt == null ? new JSONArray() : fmt))) return false;
        m.text = text;
        m.f = fmt != null && fmt.length() > 0 ? fmt : null;
        m.ed = true;
        Store.get(c).touched(m.gid);
        return true;
    }

    static boolean delete(Context c, Msg m) {
        return send(json("t", "del", "g", m.gid, "th", m.th, "id", m.id));
    }

    static void resend(Context c, Msg m) {
        Store st = Store.get(c);
        st.removeMsg(m.gid, m);
        Msg q = null;
        if (m.q != null && m.q.optString("id").length() > 0) {
            q = new Msg();
            q.id = m.q.optString("id"); q.th = m.q.optString("th"); q.s = m.q.optString("s");
            q.text = m.q.optString("text"); q.time = m.q.optLong("t");
        }
        java.io.File img = m.local.length() > 0 ? new java.io.File(m.local) : null;
        sendMessage(c, m.gid, m.text, m.in ? m.th : null, q, m.f, img != null && img.exists() ? img : null);
    }

    static boolean react(String gid, Msg m, String emoji, boolean remove) {
        return send(json("t", "react", "g", gid, "th", m.th, "id", m.id, "e", emoji, "rm", remove));
    }

    static boolean read(String gid, long ts) {
        return send(json("t", "read", "g", gid, "ts", ts));
    }

    static boolean requestImage(Msg m, int i) {
        if (!connected() || m.a == null) return false;
        JSONObject a = m.a.optJSONObject(i);
        if (a == null) return false;
        String key = m.id + "#" + i;
        if (Pics.loading.contains(key)) return true;
        Pics.loading.add(key);
        Pics.failed.remove(key);
        queue(json("t", "img", "id", m.id, "i", i, "k", a.optString("k"), "ct", a.optString("ct")));
        return true;
    }

    /** מבקש מהמחשב קובץ של הודעה קולית. false אם המחשב לא מחובר. */
    static boolean requestAudio(Msg m, int i) {
        if (!connected() || m.au == null) return false;
        JSONObject a = m.au.optJSONObject(i);
        if (a == null) return false;
        String key = "aud:" + m.id + "#" + i;
        if (Pics.loading.contains(key)) return true;
        Pics.loading.add(key);
        Pics.failed.remove(key);
        queue(json("t", "aud", "id", m.id, "i", i, "k", a.optString("k"), "ct", a.optString("ct")));
        return true;
    }

    /** שליחת הודעה קולית שהוקלטה בנגן (קובץ m4a). */
    static Msg sendVoice(Context c, String gid, java.io.File f, long durMs, JSONArray wave, String thread, Msg quote) {
        Msg m = Store.get(c).addPending(gid, "", thread);
        try {
            m.au = new JSONArray().put(new JSONObject().put("d", durMs).put("w", wave).put("local", f.getPath()).put("ct", "audio/mpeg"));
        } catch (Exception ignored) { }
        String qid = "", qth = "";
        long qt = 0;
        if (quote != null && quote.id.length() > 0) {
            qid = quote.id; qth = quote.th; qt = quote.time;
            try { m.q = new JSONObject().put("s", quote.s).put("text", quote.preview()).put("id", quote.id).put("t", quote.time).put("th", quote.th); } catch (Exception ignored) { }
        }
        Store.get(c).touched(gid);
        String data;
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } }
            data = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Store.get(c).sent(gid, m.lid, false, null, "לא ניתן לקרוא את ההקלטה");
            return m;
        }
        JSONObject voice = new JSONObject();
        try { voice.put("d", durMs).put("w", wave); } catch (Exception ignored) { }
        boolean ok = send(json("t", "upload", "g", gid, "text", "", "th", thread == null ? "" : thread, "lid", m.lid, "qid", qid, "qth", qth, "qt", qt,
                "f", new JSONArray(), "name", "UserRecording_" + System.currentTimeMillis() + ".m4a", "ct", "audio/mpeg", "voice", voice, "data", data));
        if (!ok) Store.get(c).sent(gid, m.lid, false, null, "הנגן לא מחובר למחשב");
        return m;
    }

    static void requestAvatar(String url) {
        if (!connected() || url.length() == 0) return;
        String key = "av:" + url;
        if (Pics.loading.contains(key) || Pics.failed.containsKey(key)) return;
        Pics.loading.add(key);
        queue(json("t", "av", "url", url));
    }

    private static String json(Object... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static boolean send(String line) {
        LinkService s = self;
        if (s == null || state != CONNECTED) return false;
        s.write(line);
        return true;
    }

    // תור לבקשות תמונה: לכל היותר 2 בבת אחת, כדי שההודעות לא יתעכבו מאחורי תמונות
    private static final ArrayDeque<String> mediaQueue = new ArrayDeque<>();
    private static int mediaInFlight;

    private static void queue(String line) {
        synchronized (mediaQueue) { mediaQueue.add(line); }
        pump();
    }

    private static void pump() {
        LinkService s = self;
        if (s == null) return;
        synchronized (mediaQueue) {
            while (mediaInFlight < 2 && !mediaQueue.isEmpty()) {
                mediaInFlight++;
                s.write(mediaQueue.poll());
            }
        }
    }

    private static void mediaDone() {
        synchronized (mediaQueue) { mediaInFlight = Math.max(0, mediaInFlight - 1); }
        pump();
    }

    // ---------------------------------------------------------------- בלוטות'

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (st == BluetoothAdapter.STATE_ON) {
                if (acceptThread != null) acceptThread.interrupt();
            } else if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                closeConn();
                closeServer();
                setState(OFF);
            }
        }
    };

    private void acceptLoop() {
        while (running) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) {
                setState(OFF);
                sleep(4000);
                continue;
            }
            try {
                if (server == null) server = a.listenUsingInsecureRfcommWithServiceRecord("BTChat", SERVICE_UUID);
                if (conn == null) setState(WAITING);
                BluetoothSocket s = server.accept();
                if (s != null) new Thread(() -> handle(s), "btchat-conn").start();
            } catch (IOException e) {
                closeServer();
                sleep(2500);
            } catch (Exception e) {
                closeServer();
                sleep(4000);
            }
        }
    }

    private void handle(BluetoothSocket s) {
        closeConn();
        conn = s;
        try {
            out = s.getOutputStream();
            lastRecv = System.currentTimeMillis();
            String name = "";
            try { name = s.getRemoteDevice().getName(); } catch (Exception ignored) { }
            peer = name == null ? "" : name;
            Store.googleOk = false;
            synchronized (mediaQueue) { mediaQueue.clear(); mediaInFlight = 0; }
            Pics.loading.clear();
            setState(CONNECTED);
            JSONObject sync = new JSONObject();
            sync.put("t", "sync");
            sync.put("known", Store.get(this).known());
            write(sync.toString());
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"), 64 * 1024);
            String line;
            while ((line = r.readLine()) != null) {
                lastRecv = System.currentTimeMillis();
                line = line.trim();
                if (line.length() == 0) continue;
                try { onLine(new JSONObject(line)); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
            if (conn == s) {
                conn = null;
                out = null;
                synchronized (mediaQueue) { mediaQueue.clear(); mediaInFlight = 0; }
                Pics.loading.clear();
                Store.googleOk = false;
                Store.get(this).failSending("החיבור למחשב נותק");
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                setState(a != null && a.isEnabled() ? WAITING : OFF);
                linkChanged();
            }
        }
    }

    private void onLine(JSONObject o) {
        Store st = Store.get(this);
        switch (o.optString("t")) {
            case "ping":
                write("{\"t\":\"pong\"}");
                break;
            case "hello": {
                String n = o.optString("name", "");
                if (n.length() > 0) { peer = n; Prefs.setPcName(this, n); linkChanged(); }
                break;
            }
            case "status":
                Store.googleOk = o.optBoolean("ok");
                Store.googleMsg = o.optString("msg");
                JSONArray accs = o.optJSONArray("accounts");
                if (accs != null && accs.length() > 0) {
                    Store.setAccounts(this, accs);
                    java.util.Set<String> keys = new java.util.HashSet<>();
                    for (int i = 0; i < accs.length(); i++) { JSONObject a = accs.optJSONObject(i); if (a != null) keys.add(a.optString("k")); }
                    st.keepAccounts(keys);
                }
                if (o.optString("self").length() > 0) Prefs.setSelf(this, o.optString("self"));
                linkChanged();
                Store.Listener sl = Store.listener;
                if (sl != null) main.post(() -> sl.changed(null));
                break;
            case "users": {
                JSONArray a = o.optJSONArray("items");
                if (a != null) st.updateUsers(a);
                break;
            }
            case "chats": {
                JSONArray a = o.optJSONArray("items");
                if (a == null) break;
                List<String> read = st.updateChats(a);
                for (String gid : read) {
                    Chat c = st.chat(gid);
                    if (c != null && !c.hasUnread()) main.post(() -> Notifier.cancel(this, gid));
                }
                break;
            }
            case "msgs": {
                String gid = o.optString("g");
                JSONArray a = o.optJSONArray("items");
                boolean older = o.optBoolean("older"), open = o.optBoolean("open");
                String err = o.has("err") ? o.optString("err") : null;
                List<Msg> added = a != null && a.length() > 0 ? st.merge(gid, a, older, !o.optBoolean("more", true)) : new ArrayList<Msg>();
                if (older || open) {
                    boolean more = o.optBoolean("more");
                    main.post(() -> {
                        LoadCallback cb = loadCallback;
                        if (cb != null) cb.loaded(gid, older, more, err);
                    });
                }
                if (!older) notifyNew(gid, added);
                break;
            }
            case "sent": {
                String err = o.has("err") ? o.optString("err") : null;
                st.sent(o.optString("g"), o.optString("lid"), o.optBoolean("ok"), o.optJSONObject("msg"), err);
                break;
            }
            case "dm": {
                String g = o.optString("g");
                JSONObject row = o.optJSONObject("row");
                if (row != null) st.updateChats(new JSONArray().put(row));
                String u = o.optString("u"), err = o.optString("err");
                main.post(() -> {
                    DmCallback cb = dmCallback;
                    if (cb != null) cb.dm(u, g, err);
                });
                break;
            }
            case "edited":
            case "deleted": {
                String gid = o.optString("g");
                if (!o.optBoolean("ok")) {
                    String err = o.optString("err", "שגיאה");
                    boolean edit = o.optString("t").equals("edited");
                    main.post(() -> android.widget.Toast.makeText(this, (edit ? "העריכה נכשלה: " : "המחיקה נכשלה: ") + err, android.widget.Toast.LENGTH_LONG).show());
                    open(gid);   // מחזירים את ההודעה למצב האמיתי
                } else if (o.optString("t").equals("deleted")) st.markDeleted(gid, o.optString("id"));
                else if (o.optJSONObject("msg") != null) st.replaceMsg(gid, o.optJSONObject("msg"));
                break;
            }
            case "aud": {
                String id = o.optString("id");
                int i = o.optInt("i");
                String key = "aud:" + id + "#" + i;
                if (o.has("data")) {
                    Pics.saveAudio(this, id, i, o.optString("data"));
                    // אם המשתמש לחץ "נגן" וחיכה להורדה — מנגנים מיד
                    String vk = Voice.key(id, i);
                    if (vk.equals(Voice.pending)) {
                        Voice.pending = "";
                        java.io.File f = Pics.audioFile(this, id, i);
                        main.post(() -> Voice.play(this, vk, f));
                    }
                } else {
                    Pics.failed.put(key, o.optString("err", "שגיאה"));
                    if (Voice.key(id, i).equals(Voice.pending)) Voice.pending = "";
                }
                Pics.loading.remove(key);
                mediaDone();
                imagesChanged();
                break;
            }
            case "img": {
                String key = o.optString("id") + "#" + o.optInt("i");
                if (o.has("data")) Pics.saveImage(this, o.optString("id"), o.optInt("i"), o.optString("data"));
                else Pics.failed.put(key, o.optString("err", "שגיאה"));
                Pics.loading.remove(key);
                mediaDone();
                imagesChanged();
                break;
            }
            case "av": {
                String url = o.optString("url");
                if (o.has("data")) Pics.saveAvatar(this, url, o.optString("data"));
                else Pics.failed.put("av:" + url, o.optString("err"));
                Pics.loading.remove("av:" + url);
                mediaDone();
                imagesChanged();
                break;
            }
            default:
                break;
        }
    }

    private final Runnable imagesTick = () -> {
        Store.Listener l = Store.listener;
        if (l != null) l.changed("*img");
    };

    private void imagesChanged() {
        main.removeCallbacks(imagesTick);
        main.postDelayed(imagesTick, 120);
    }

    /** הודעות חדשות שלא נקראו: התראה (אם לא מושתק ואם הצ'אט לא פתוח כרגע על המסך). */
    private void notifyNew(String gid, List<Msg> added) {
        Store st = Store.get(this);
        Chat c = st.chat(gid);
        if (c == null || added.isEmpty()) return;
        List<Msg> fresh = new ArrayList<>();
        long max = 0;
        for (Msg m : added) {
            if (m.me || m.sys || m.time <= c.lastRead || m.time <= c.notified) continue;
            fresh.add(m);
            max = Math.max(max, m.time);
        }
        if (fresh.isEmpty()) return;
        st.setNotified(gid, max);
        if (gid.equals(ChatActivity.visibleGid)) return;
        if (!Prefs.notify(this) || Prefs.muted(this, gid)) return;
        main.post(() -> Notifier.post(this, c, fresh));
    }

    /** כתיבה לפי הסדר, בתהליכון אחד (לא חוסם את המסך). */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();

    void write(String json) {
        OutputStream o = out;
        if (o == null) return;
        writer.execute(() -> {
            synchronized (writeLock) {
                try {
                    o.write((json + "\n").getBytes("UTF-8"));
                    o.flush();
                } catch (IOException e) {
                    closeConn();
                }
            }
        });
    }

    private void closeConn() {
        BluetoothSocket s = conn;
        conn = null;
        out = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private void closeServer() {
        BluetoothServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    /** אם המחשב לא שלח כלום יותר מדקה — החיבור כנראה מת. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (conn != null && System.currentTimeMillis() - lastRecv > 75000) closeConn();
            main.postDelayed(this, 30000);
        }
    };

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
