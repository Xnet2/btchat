package il.btchat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** הודעה, צ'אט ומשתמש — כפי שהם מגיעים מהמחשב ונשמרים בנגן. */
final class Model {
    private Model() { }

    static final class User {
        String id, name = "", av = "";

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("n", name).put("av", av);
        }

        static User from(JSONObject o) {
            User u = new User();
            u.id = o.optString("id");
            u.name = o.optString("n");
            u.av = o.optString("av");
            return u;
        }
    }

    static final class Msg {
        static final int OK = 0, SENDING = 1, FAILED = 2;
        String gid = "", id = "", th = "", s = "", text = "", note = "", lid = "", err = "";
        long time;
        boolean me, ed, sys, in, del;
        int rc;
        long rl;             // זמן התשובה האחרונה בשרשור
        JSONArray a, r;      // תמונות, תגובות
        JSONArray f;         // עיצוב: [[התחלה, אורך, סוג], …]
        JSONArray rp;        // מי ענה בשרשור
        JSONArray au;        // הודעות קוליות: [{k, ct, d (מילישניות), w (גל קול), local}]
        JSONObject q;        // ציטוט
        String local = "";   // תמונה שנשלחת מהנגן (נתיב מקומי, עד שגוגל מאשרת)
        int state = OK;

        String key() { return id.length() > 0 ? id : "lid:" + lid; }

        int images() { return a == null ? 0 : a.length(); }

        /** טקסט קצר לרשימת הצ'אטים ולהתראות. */
        String preview() {
            if (del) return "ההודעה נמחקה";
            if (text.trim().length() > 0) return text.trim().replace('\n', ' ');
            if (local.length() > 0) return "שלח/ה תמונה";
            if (au != null && au.length() > 0) return "🎤 הודעה קולית";
            if (images() > 0) return "שלח/ה תמונה";
            if (note.length() > 0) return note.startsWith("📎") || note.startsWith("📄") ? "שלח/ה קובץ" : note.startsWith("🎬") ? "שלח/ה סרטון" : note.replace('\n', ' ');
            return "";
        }

        /** הודעה בלי טקסט (תמונה/קובץ) — מוצגת בנטוי ברשימה, כמו בגוגל צ'אט. */
        boolean previewItalic() { return text.trim().length() == 0; }

        static Msg from(JSONObject o) {
            Msg m = new Msg();
            m.gid = o.optString("g");
            m.id = o.optString("id");
            m.th = o.optString("th");
            m.s = o.optString("s");
            m.text = o.optString("text");
            m.note = o.optString("note");
            m.lid = o.optString("lid");
            m.err = o.optString("err");
            m.time = o.optLong("time");
            m.me = o.optBoolean("me");
            m.ed = o.optBoolean("ed");
            m.sys = o.optBoolean("sys");
            m.in = o.optBoolean("in");
            m.rc = o.optInt("rc");
            m.del = o.optBoolean("del");
            m.rl = o.optLong("rl");
            m.a = o.optJSONArray("a");
            m.r = o.optJSONArray("r");
            m.f = o.optJSONArray("f");
            m.rp = o.optJSONArray("rp");
            m.au = o.optJSONArray("au");
            m.q = o.optJSONObject("q");
            m.local = o.optString("local");
            m.state = o.optInt("st", OK);
            return m;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("g", gid).put("id", id).put("th", th).put("s", s).put("text", text).put("time", time);
            if (note.length() > 0) o.put("note", note);
            if (lid.length() > 0) o.put("lid", lid);
            if (err.length() > 0) o.put("err", err);
            if (me) o.put("me", true);
            if (ed) o.put("ed", true);
            if (sys) o.put("sys", true);
            if (in) o.put("in", true);
            if (rc > 0) o.put("rc", rc);
            if (del) o.put("del", true);
            if (rl > 0) o.put("rl", rl);
            if (a != null) o.put("a", a);
            if (r != null) o.put("r", r);
            if (f != null) o.put("f", f);
            if (rp != null) o.put("rp", rp);
            if (au != null) o.put("au", au);
            if (q != null) o.put("q", q);
            if (local.length() > 0) o.put("local", local);
            if (state != OK) o.put("st", state);
            return o;
        }
    }

    static final class Chat {
        String gid = "", name = "", av = "";
        boolean dm;
        String[] users = new String[0];
        long sort, lastRead, unread;
        long notified;       // עד איזה זמן כבר הוקפצו התראות
        long readSent;       // עד איזה זמן כבר נשלח "נקרא" לגוגל
        Msg snippet;
        boolean noMore;      // אין הודעות ישנות יותר
        boolean space() { return !dm; }

        /** החשבון שהצ'אט שייך לו (החלק שלפני "|" במזהה). */
        String acc() {
            int bar = gid.indexOf('|');
            return bar > 0 ? gid.substring(0, bar) : "";
        }

        void apply(JSONObject o) {
            name = o.optString("n", name);
            av = o.optString("av", "");
            dm = o.optBoolean("dm", dm);
            JSONArray u = o.optJSONArray("u");
            if (u != null) {
                users = new String[u.length()];
                for (int i = 0; i < u.length(); i++) users[i] = u.optString(i);
            }
            sort = o.optLong("s", sort);
            lastRead = o.optLong("lr", lastRead);
            unread = o.optLong("un", unread);
            JSONObject sn = o.optJSONObject("sn");
            if (sn != null) {
                Msg m = Msg.from(sn);
                if (snippet == null || m.time >= snippet.time) snippet = m;
            }
        }

        static Chat from(JSONObject o) {
            Chat c = new Chat();
            c.gid = o.optString("g");
            c.apply(o);
            c.notified = o.optLong("nt");
            c.readSent = o.optLong("rs");
            c.noMore = o.optBoolean("nm");
            return c;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("g", gid).put("n", name).put("av", av).put("dm", dm).put("s", sort).put("lr", lastRead).put("un", unread);
            JSONArray u = new JSONArray();
            for (String s : users) u.put(s);
            o.put("u", u);
            if (snippet != null) o.put("sn", snippet.toJson());
            o.put("nt", notified).put("rs", readSent);
            if (noMore) o.put("nm", true);
            return o;
        }

        /** זמן ההודעה האחרונה הידועה (למיון ולתצוגה). */
        long lastTime() {
            return Math.max(sort, snippet != null ? snippet.time : 0);
        }

        boolean hasUnread() {
            return unread > 0 || (snippet != null && !snippet.me && snippet.time > lastRead && lastRead > 0);
        }
    }
}
