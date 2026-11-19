package il.btchat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** ההגדרות של האפליקציה. */
final class Prefs {
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static boolean notify(Context c) { return sp(c).getBoolean("notify", true); }
    static boolean sound(Context c) { return sp(c).getBoolean("sound", true); }
    static boolean vibrate(Context c) { return sp(c).getBoolean("vibrate", true); }
    static boolean preview(Context c) { return sp(c).getBoolean("preview", true); }
    static boolean replyFromNotif(Context c) { return sp(c).getBoolean("reply", true); }
    static boolean syncRead(Context c) { return sp(c).getBoolean("syncRead", true); }
    static boolean enterSends(Context c) { return sp(c).getBoolean("enterSends", false); }
    /** 0 = לפי המערכת, 1 = בהיר, 2 = כהה */
    static int theme(Context c) { return sp(c).getInt("theme", 0); }
    static String pcName(Context c) { return sp(c).getString("pcName", ""); }
    static String self(Context c) { return sp(c).getString("self", ""); }
    static void setSelf(Context c, String v) { sp(c).edit().putString("self", v).apply(); }

    static void set(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }
    static void setTheme(Context c, int v) { sp(c).edit().putInt("theme", v).apply(); }
    static void setPcName(Context c, String v) { sp(c).edit().putString("pcName", v).apply(); }

    static boolean muted(Context c, String gid) {
        return sp(c).getStringSet("muted", new HashSet<String>()).contains(gid);
    }

    static void setMuted(Context c, String gid, boolean on) {
        Set<String> s = new HashSet<>(sp(c).getStringSet("muted", new HashSet<String>()));
        if (on) s.add(gid); else s.remove(gid);
        sp(c).edit().putStringSet("muted", s).apply();
    }
}
