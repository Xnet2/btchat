package il.btchat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** תמונות פרופיל ותמונות מהודעות: נשמרות בנגן ומתבקשות מהמחשב רק כשצריך. */
final class Pics {
    private Pics() { }

    private static final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    static final Set<String> loading = Collections.synchronizedSet(new HashSet<String>());
    static final Map<String, String> failed = Collections.synchronizedMap(new HashMap<String, String>());

    private static String hash(String s) {
        return Integer.toHexString(s.hashCode()) + "_" + Integer.toHexString(s.length());
    }

    private static File avFile(Context c, String url) {
        File d = new File(c.getFilesDir(), "av");
        d.mkdirs();
        return new File(d, hash(url) + ".jpg");
    }

    private static File imgFile(Context c, String id, int i) {
        File d = new File(c.getCacheDir(), "img");
        d.mkdirs();
        return new File(d, id.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + i + ".jpg");
    }

    /** תמונת פרופיל, או null (ואז מבקשים אותה מהמחשב). */
    static Bitmap avatar(Context c, String url) {
        if (url == null || url.length() == 0) return null;
        Bitmap b = mem.get("av:" + url);
        if (b != null) return b;
        File f = avFile(c, url);
        if (f.exists()) {
            b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) { mem.put("av:" + url, b); return b; }
        }
        if (!failed.containsKey("av:" + url)) LinkService.requestAvatar(url);
        return null;
    }

    static void saveAvatar(Context c, String url, String b64) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            try (FileOutputStream out = new FileOutputStream(avFile(c, url))) { out.write(data); }
            mem.remove("av:" + url);
        } catch (Exception ignored) { }
    }

    static boolean hasImage(Context c, String id, int i) {
        return imgFile(c, id, i).exists();
    }

    /** תמונה מהודעה, מוקטנת לרוחב maxW. */
    static Bitmap image(Context c, String id, int i, int maxW) {
        String key = "img:" + id + ":" + i + ":" + maxW;
        Bitmap b = mem.get(key);
        if (b != null) return b;
        File f = imgFile(c, id, i);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b != null) mem.put(key, b);
        return b;
    }

    static File imageFile(Context c, String id, int i) { return imgFile(c, id, i); }

    static void saveImage(Context c, String id, int i, String b64) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            try (FileOutputStream out = new FileOutputStream(imgFile(c, id, i))) { out.write(data); }
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- הודעות קוליות

    static File audioFile(Context c, String id, int i) {
        File d = new File(c.getCacheDir(), "aud");
        d.mkdirs();
        return new File(d, id.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + i + ".m4a");
    }

    static void saveAudio(Context c, String id, int i, String b64) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            try (FileOutputStream out = new FileOutputStream(audioFile(c, id, i))) { out.write(data); }
        } catch (Exception ignored) { }
    }

    static long cacheSize(Context c) {
        long n = 0;
        for (String dir : new String[]{"img", "aud"}) {
            File[] fs = new File(c.getCacheDir(), dir).listFiles();
            if (fs != null) for (File f : fs) n += f.length();
        }
        return n;
    }

    static void clearImages(Context c) {
        for (String dir : new String[]{"img", "aud"}) {
            File[] fs = new File(c.getCacheDir(), dir).listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
        mem.evictAll();
    }
}
