package il.btchat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import il.btchat.Model.Chat;
import il.btchat.Model.Msg;
import il.btchat.Model.User;

/** כל הנתונים שבנגן: צ'אטים, משתמשים והודעות. נשמרים בקבצים ומתעדכנים מהמחשב. */
final class Store {
    interface Listener { void changed(String gid); }

    private static Store inst;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    final Map<String, Chat> chats = new HashMap<>();
    final Map<String, User> users = new HashMap<>();
    private final Map<String, List<Msg>> msgs = new HashMap<>();
    private final Set<String> dirtyMsgs = new HashSet<>();
    private boolean dirtyChats;
    static volatile Listener listener;
    static volatile boolean googleOk;
    static volatile String googleMsg = "";
    private static final int KEEP = 400;

    static synchronized Store get(Context c) {
        if (inst == null) {
            inst = new Store(c.getApplicationContext());
            inst.load();
        }
        return inst;
    }

    private Store(Context c) { app = c; }

    // ---------------------------------------------------------------- שמירה

    private File chatsFile() { return new File(app.getFilesDir(), "chats.json"); }

    private File msgsFile(String gid) {
        File d = new File(app.getFilesDir(), "msgs");
        d.mkdirs();
        return new File(d, gid.replace('/', '_').replace('|', '~') + ".json");
    }

    private static String read(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes("UTF-8"));
            out.getFD().sync();
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }

    private synchronized void load() {
        String s = read(chatsFile());
        if (s == null) return;
        try {
            JSONObject o = new JSONObject(s);
            JSONArray c = o.optJSONArray("chats");
            if (c != null) for (int i = 0; i < c.length(); i++) {
                Chat ch = Chat.from(c.getJSONObject(i));
                if (ch.gid.indexOf('|') < 0) continue;   // מגרסה ישנה (בלי חשבון) — ייטען מחדש מהמחשב
                chats.put(ch.gid, ch);
            }
            JSONArray u = o.optJSONArray("users");
            if (u != null) for (int i = 0; i < u.length(); i++) {
                User us = User.from(u.getJSONObject(i));
                users.put(us.id, us);
            }
        } catch (Exception ignored) { }
    }

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            new Thread(Store.this::saveNow, "store-save").start();
        }
    };

    private void scheduleSave() {
        main.removeCallbacks(saver);
        main.postDelayed(saver, 1500);
    }

    synchronized void saveNow() {
        try {
            if (dirtyChats) {
                JSONObject o = new JSONObject();
                JSONArray c = new JSONArray();
                for (Chat ch : chats.values()) c.put(ch.toJson());
                JSONArray u = new JSONArray();
                for (User us : users.values()) u.put(us.toJson());
                o.put("chats", c).put("users", u);
                write(chatsFile(), o.toString());
                dirtyChats = false;
            }
            for (String gid : dirtyMsgs) {
                List<Msg> l = msgs.get(gid);
                if (l == null) continue;
                JSONArray a = new JSONArray();
                for (Msg m : l) if (m.state != Msg.SENDING) a.put(m.toJson());
                write(msgsFile(gid), a.toString());
            }
            dirtyMsgs.clear();
        } catch (Exception ignored) { }
    }

    private void changed(String gid) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.changed(gid);
        });
    }

    // ---------------------------------------------------------------- צ'אטים ומשתמשים

    /** עדכון רשימת הצ'אטים מהמחשב. מחזיר צ'אטים שסומנו כנקראים (כדי לנקות התראות). */
    synchronized List<String> updateChats(JSONArray items) {
        List<String> read = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String gid = o.optString("g");
            if (gid.length() == 0) continue;
            Chat c = chats.get(gid);
            if (c == null) { c = new Chat(); c.gid = gid; chats.put(gid, c); }
            long before = c.lastRead;
            c.apply(o);
            if (c.lastRead > before || c.unread == 0) read.add(gid);
        }
        dirtyChats = true;
        scheduleSave();
        changed(null);
        return read;
    }

    synchronized void updateUsers(JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            User u = User.from(o);
            if (u.id.length() == 0) continue;
            users.put(u.id, u);
        }
        dirtyChats = true;
        scheduleSave();
        changed(null);
    }

    synchronized String userName(String id) {
        User u = users.get(id);
        return u != null && u.name.length() > 0 ? u.name : "";
    }

    synchronized String userAvatar(String id) {
        User u = users.get(id);
        return u != null ? u.av : "";
    }

    synchronized String title(Chat c) {
        if (c.name.length() > 0) return c.name;
        StringBuilder sb = new StringBuilder();
        for (String u : c.users) {
            String n = userName(u);
            if (n.length() == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.users.length > 1 ? n.split(" ")[0] : n);
        }
        return sb.length() > 0 ? sb.toString() : (c.dm ? "צ'אט" : "מרחב");
    }

    /** תמונת הצ'אט: של המרחב, או של האדם בשיחה אישית. */
    synchronized String chatAvatar(Chat c) {
        if (c.av.length() > 0) return c.av;
        if (c.users.length == 1) return userAvatar(c.users[0]);
        return "";
    }

    synchronized Chat chat(String gid) { return chats.get(gid); }

    synchronized List<Chat> chatList() {
        List<Chat> l = new ArrayList<>(chats.values());
        Collections.sort(l, (a, b) -> Long.compare(b.lastTime(), a.lastTime()));
        return l;
    }

    /** הצ'אטים של חשבון אחד. */
    synchronized List<Chat> chatList(String acc) {
        List<Chat> l = new ArrayList<>();
        for (Chat c : chatList()) if (acc == null || acc.length() == 0 || acc.equals(c.acc())) l.add(c);
        return l;
    }

    // ---------------------------------------------------------------- חשבונות

    /** החשבונות שהמחשב מחובר אליהם: [{k, n, e, av, ok}]. */
    static JSONArray accounts(Context c) {
        try { return new JSONArray(c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("accounts", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    static void setAccounts(Context c, JSONArray a) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("accounts", a.toString()).apply();
    }

    /** החשבון שמוצג עכשיו (אם לא נבחר או כבר לא קיים — הראשון). */
    static String current(Context c) {
        String cur = c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("account", "");
        JSONArray a = accounts(c);
        String first = "";
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String k = o.optString("k");
            if (first.length() == 0) first = k;
            if (k.equals(cur)) return cur;
        }
        return first.length() > 0 ? first : cur;
    }

    static void setCurrent(Context c, String k) {
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("account", k).apply();
    }

    static JSONObject account(Context c, String k) {
        JSONArray a = accounts(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optString("k").equals(k)) return o;
        }
        return null;
    }

    /** כמה צ'אטים לא נקראו בחשבון (בלי מושתקים). */
    synchronized int unreadCount(String acc) {
        int n = 0;
        for (Chat ch : chats.values()) if (acc.equals(ch.acc()) && ch.hasUnread() && !Prefs.muted(app, ch.gid)) n++;
        return n;
    }

    /** הודעה ערוכה / שנמחקה (מהמחשב). */
    synchronized void replaceMsg(String gid, JSONObject msg) {
        Msg m = Msg.from(msg);
        m.gid = gid;
        List<Msg> l = messages(gid);
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(m.id)) { l.set(i, m); break; }
        dirtyMsgs.add(gid);
        scheduleSave();
        changed(gid);
    }

    synchronized void markDeleted(String gid, String id) {
        for (Msg m : messages(gid)) if (m.id.equals(id)) { m.del = true; m.text = ""; m.a = null; m.r = null; m.f = null; m.q = null; m.note = ""; }
        Chat c = chats.get(gid);
        if (c != null && c.snippet != null && c.snippet.id.equals(id)) { c.snippet.del = true; c.snippet.text = ""; dirtyChats = true; }
        dirtyMsgs.add(gid);
        scheduleSave();
        changed(gid);
    }

    synchronized void touched(String gid) {
        dirtyMsgs.add(gid);
        scheduleSave();
        changed(gid);
    }

    synchronized void setNotified(String gid, long t) {
        Chat c = chats.get(gid);
        if (c != null && t > c.notified) { c.notified = t; dirtyChats = true; scheduleSave(); }
    }

    /** סימון מקומי כנקרא (ההודעה לגוגל נשלחת בנפרד). */
    synchronized void markReadLocal(String gid, long t) {
        Chat c = chats.get(gid);
        if (c == null) return;
        c.lastRead = Math.max(c.lastRead, t);
        c.unread = 0;
        c.readSent = Math.max(c.readSent, t);
        dirtyChats = true;
        scheduleSave();
        changed(null);
    }

    /** הזמן האחרון שידוע לנגן לכל צ'אט — המחשב שולח רק מה שחסר. */
    synchronized JSONObject known() {
        JSONObject o = new JSONObject();
        for (Chat c : chats.values()) {
            long t = 0;
            List<Msg> l = messages(c.gid);
            for (Msg m : l) if (m.state == Msg.OK && m.time > t) t = m.time;
            if (t == 0 && c.notified > 0) t = c.notified;
            if (t > 0) try { o.put(c.gid, t); } catch (Exception ignored) { }
        }
        return o;
    }

    // ---------------------------------------------------------------- הודעות

    synchronized List<Msg> messages(String gid) {
        List<Msg> l = msgs.get(gid);
        if (l != null) return l;
        l = new ArrayList<>();
        String s = read(msgsFile(gid));
        if (s != null) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    Msg m = Msg.from(a.getJSONObject(i));
                    if (m.state == Msg.SENDING) m.state = Msg.FAILED;
                    l.add(m);
                }
            } catch (Exception ignored) { }
        }
        msgs.put(gid, l);
        return l;
    }

    /** עותק של ההודעות (בטוח לשימוש בממשק). */
    synchronized List<Msg> snapshot(String gid) {
        return new ArrayList<>(messages(gid));
    }

    /**
     * מיזוג הודעות מהמחשב. מחזיר את ההודעות שלא היו קודם (לצורך התראות).
     */
    synchronized List<Msg> merge(String gid, JSONArray items, boolean older, boolean noMoreOlder) {
        List<Msg> l = messages(gid);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < l.size(); i++) idx.put(l.get(i).key(), i);
        List<Msg> added = new ArrayList<>();
        Chat c = chats.get(gid);
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            Msg m = Msg.from(o);
            m.gid = gid;
            if (m.id.length() == 0) continue;
            Integer at = idx.get(m.key());
            if (at != null) { l.set(at, m); continue; }
            // הודעה שנשלחה מהנגן חוזרת מגוגל: מחליפה את ההודעה הממתינה
            if (m.me) {
                boolean replaced = false;
                for (int j = l.size() - 1; j >= 0 && j >= l.size() - 30; j--) {
                    Msg p = l.get(j);
                    if (p.id.length() == 0 && p.text.equals(m.text) && (p.local.length() == 0) == (m.images() == 0)) {
                        adoptLocalImage(p, m);
                        l.set(j, m); idx.put(m.key(), j); replaced = true; break;
                    }
                }
                if (replaced) continue;
            }
            l.add(m);
            idx.put(m.key(), l.size() - 1);
            added.add(m);
        }
        Collections.sort(l, (a, b) -> {
            boolean pa = a.id.length() == 0, pb = b.id.length() == 0;
            if (pa != pb) return pa ? 1 : -1;   // ממתינות בסוף
            return Long.compare(a.time, b.time);
        });
        while (l.size() > KEEP) l.remove(0);
        if (c != null) {
            if (older && noMoreOlder) c.noMore = true;
            for (int i = l.size() - 1; i >= 0; i--) {
                Msg last = l.get(i);
                if (last.id.length() == 0 || last.in) continue;
                if (c.snippet == null || last.time >= c.snippet.time) c.snippet = last;
                break;
            }
            dirtyChats = true;
        }
        dirtyMsgs.add(gid);
        scheduleSave();
        changed(gid);
        return added;
    }

    /** תמונה שנשלחה מהנגן: הקובץ המקומי הופך לתמונה של ההודעה האמיתית (בלי להוריד אותה שוב). */
    private void adoptLocalImage(Msg pending, Msg real) {
        if (real.id.length() == 0) return;
        // הודעה קולית שהוקלטה בנגן: הקובץ עובר למטמון של ההודעה האמיתית
        if (pending.au != null && pending.au.length() > 0) {
            String lp = pending.au.optJSONObject(0) != null ? pending.au.optJSONObject(0).optString("local") : "";
            File src = new File(lp), dst = Pics.audioFile(app, real.id, 0);
            if (lp.length() > 0 && src.exists() && !dst.exists()) src.renameTo(dst);
        }
        if (pending.local.length() == 0) return;
        File src = new File(pending.local);
        File dst = Pics.imageFile(app, real.id, 0);
        if (src.exists() && !dst.exists()) src.renameTo(dst);
    }

    synchronized Msg addPending(String gid, String text, String th) {
        Msg m = new Msg();
        m.gid = gid;
        m.text = text;
        m.th = th == null ? "" : th;
        m.in = th != null && th.length() > 0;
        m.me = true;
        m.time = System.currentTimeMillis() * 1000;
        m.lid = Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xFFFF));
        m.state = Msg.SENDING;
        messages(gid).add(m);
        dirtyMsgs.add(gid);
        changed(gid);
        return m;
    }

    /** תשובה מהמחשב על שליחה. */
    synchronized void sent(String gid, String lid, boolean ok, JSONObject msg, String err) {
        List<Msg> l = messages(gid);
        for (int i = 0; i < l.size(); i++) {
            Msg p = l.get(i);
            if (!lid.equals(p.lid)) continue;
            if (ok && msg != null) {
                Msg m = Msg.from(msg);
                m.gid = gid;
                adoptLocalImage(p, m);
                boolean dup = false;
                for (Msg x : l) if (x != p && x.id.equals(m.id)) dup = true;
                if (dup) l.remove(i); else l.set(i, m);
                Chat c = chats.get(gid);
                if (c != null && !m.in) { c.snippet = m; c.sort = Math.max(c.sort, m.time); c.lastRead = Math.max(c.lastRead, m.time); dirtyChats = true; }
            } else if (ok) {
                // נשלח, אבל בלי פרטים: נשאר במקום (בצבע הרגיל) עד שההודעה האמיתית תגיע מגוגל
                p.state = Msg.OK;
            } else {
                p.state = Msg.FAILED;
                p.err = err == null ? "" : err;
            }
            break;
        }
        dirtyMsgs.add(gid);
        scheduleSave();
        changed(gid);
    }

    synchronized Msg findPending(String gid, String lid) {
        for (Msg m : messages(gid)) if (lid.equals(m.lid)) return m;
        return null;
    }

    synchronized void removeMsg(String gid, Msg m) {
        messages(gid).remove(m);
        dirtyMsgs.add(gid);
        scheduleSave();
        changed(gid);
    }

    /** הודעה שאין לה תשובה מהמחשב (החיבור נפל באמצע) — מסומנת כנכשלה. */
    synchronized void failSending(String why) {
        for (Map.Entry<String, List<Msg>> e : msgs.entrySet()) {
            boolean any = false;
            for (Msg m : e.getValue()) if (m.state == Msg.SENDING) { m.state = Msg.FAILED; m.err = why; any = true; }
            if (any) { dirtyMsgs.add(e.getKey()); changed(e.getKey()); }
        }
        scheduleSave();
    }

    /** מחיקת כל ההודעות השמורות (ההגדרות נשארות). */
    /** הסרת צ'אטים של חשבונות שהוסרו מהמחשב. */
    synchronized void keepAccounts(java.util.Set<String> keys) {
        if (keys.isEmpty()) return;
        boolean any = false;
        java.util.Iterator<Map.Entry<String, Chat>> it = chats.entrySet().iterator();
        while (it.hasNext()) {
            Chat c = it.next().getValue();
            if (!keys.contains(c.acc())) { it.remove(); msgs.remove(c.gid); msgsFile(c.gid).delete(); any = true; }
        }
        if (any) { dirtyChats = true; scheduleSave(); changed(null); }
    }

    synchronized void clearAll() {
        msgs.clear();
        chats.clear();
        users.clear();
        dirtyChats = true;
        File d = new File(app.getFilesDir(), "msgs");
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        saveNow();
        changed(null);
    }
}
