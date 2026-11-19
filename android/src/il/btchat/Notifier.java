package il.btchat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import il.btchat.Model.Chat;
import il.btchat.Model.Msg;
import il.btchat.player.R;

/** ההתראות: אחת לכל צ'אט, עם ההודעות האחרונות שלא נקראו, תשובה מהירה וסימון כנקרא. */
final class Notifier {
    static final int ONGOING_ID = 1;
    static final String KEY_REPLY = "reply";
    private static final Map<String, List<String>> lines = new HashMap<>();

    private static String channelId(Context c) {
        boolean s = Prefs.sound(c), v = Prefs.vibrate(c);
        return "msg_" + (s ? "s" : "") + (v ? "v" : "") + (!s && !v ? "q" : "");
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel link = new NotificationChannel("link", "חיבור למחשב", NotificationManager.IMPORTANCE_MIN);
        link.setShowBadge(false);
        nm.createNotificationChannel(link);
        String[][] all = {{"msg_sv", "הודעות"}, {"msg_s", "הודעות (בלי רטט)"}, {"msg_v", "הודעות (רטט בלבד)"}, {"msg_q", "הודעות (שקט)"}};
        for (String[] ch : all) {
            NotificationChannel n = new NotificationChannel(ch[0], ch[1], NotificationManager.IMPORTANCE_HIGH);
            boolean sound = ch[0].equals("msg_sv") || ch[0].equals("msg_s");
            boolean vib = ch[0].endsWith("v");
            if (!sound) n.setSound(null, null);
            n.enableVibration(vib);
            if (vib) n.setVibrationPattern(new long[]{0, 200, 120, 200});
            n.enableLights(true);
            n.setLightColor(0xFF0B57D0);
            nm.createNotificationChannel(n);
        }
    }

    private static int flags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, flags());
    }

    private static PendingIntent openChat(Context c, String gid) {
        Intent list = new Intent(c, MainActivity.class);
        list.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent ch = new Intent(c, ChatActivity.class);
        ch.putExtra("g", gid);
        return PendingIntent.getActivities(c, gid.hashCode(), new Intent[]{list, ch}, flags());
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    @SuppressWarnings("deprecation")
    static Notification ongoing(Context c, String text) {
        Notification.Builder b = builder(c, "link")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ביטיצ'אט")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c))
                .setPriority(Notification.PRIORITY_MIN);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF0B57D0);
        return b.build();
    }

    static void updateOngoing(Context c, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(ONGOING_ID, ongoing(c, text));
    }

    private static int idFor(String gid) { return 1000 + (gid.hashCode() & 0xFFFFFF); }

    @SuppressWarnings("deprecation")
    static synchronized void post(Context c, Chat chat, List<Msg> fresh) {
        Store st = Store.get(c);
        String title = st.title(chat);
        boolean preview = Prefs.preview(c);
        List<String> l = lines.get(chat.gid);
        if (l == null) { l = new ArrayList<>(); lines.put(chat.gid, l); }
        for (Msg m : fresh) {
            String who = st.userName(m.s);
            String body = preview ? m.preview() : "הודעה חדשה";
            if (m.in) body = "בשרשור: " + body;
            l.add(chat.dm || who.length() == 0 ? body : who.split(" ")[0] + ": " + body);
        }
        while (l.size() > 7) l.remove(0);
        Msg last = fresh.get(fresh.size() - 1);
        String text = l.get(l.size() - 1);
        Notification.Builder b = builder(c, channelId(c))
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(last.time / 1000)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(openChat(c, chat.gid))
                .setDeleteIntent(action(c, ReplyReceiver.DISMISS, chat.gid))
                .setPriority(Notification.PRIORITY_HIGH);
        if (Store.accounts(c).length() > 1) {
            // כמה חשבונות: מציינים לאיזה חשבון ההודעה הגיעה
            org.json.JSONObject acc = Store.account(c, chat.acc());
            if (acc != null) b.setSubText(acc.optString("e", acc.optString("n")));
        }
        if (l.size() > 1) {
            Notification.InboxStyle in = new Notification.InboxStyle();
            for (String s : l) in.addLine(s);
            in.setBigContentTitle(title);
            b.setStyle(in);
            b.setNumber(l.size());
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        try {
            int size = c.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
            b.setLargeIcon(avatar(c, st.title(chat), st.chatAvatar(chat), !chat.dm, size));
        } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(0xFF0B57D0);
            b.setCategory(Notification.CATEGORY_MESSAGE);
            b.setVisibility(Notification.VISIBILITY_PRIVATE);
        }
        if (Build.VERSION.SDK_INT >= 24 && Prefs.replyFromNotif(c)) {
            RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel("תשובה").build();
            Intent ri2 = new Intent(c, ReplyReceiver.class).setAction(ReplyReceiver.REPLY).putExtra("g", chat.gid);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) fl |= 0x02000000; // FLAG_MUTABLE — נדרש לתשובה מההתראה
            PendingIntent pi = PendingIntent.getBroadcast(c, chat.gid.hashCode() + 1, ri2, fl);
            b.addAction(new Notification.Action.Builder(0, "תשובה", pi).addRemoteInput(ri).build());
        }
        b.addAction(0, "סימון כנקרא", action(c, ReplyReceiver.READ, chat.gid));
        if (Build.VERSION.SDK_INT < 26) {
            int d = Notification.DEFAULT_LIGHTS;
            if (Prefs.sound(c)) d |= Notification.DEFAULT_SOUND;
            if (Prefs.vibrate(c)) d |= Notification.DEFAULT_VIBRATE;
            b.setDefaults(d);
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(idFor(chat.gid), b.build());
    }

    private static PendingIntent action(Context c, String act, String gid) {
        Intent i = new Intent(c, ReplyReceiver.class).setAction(act).putExtra("g", gid);
        return PendingIntent.getBroadcast(c, (act + gid).hashCode(), i, flags());
    }

    private static Bitmap avatar(Context c, String name, String url, boolean group, int size) {
        AvatarView v = new AvatarView(c);
        v.set(name, url, group);
        v.measure(size, size);
        v.layout(0, 0, size, size);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    static synchronized void cancel(Context c, String gid) {
        lines.remove(gid);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(idFor(gid));
    }

    static synchronized void forget(String gid) { lines.remove(gid); }

    /** אחרי תשובה מההתראה: מחליפים את ההתראה בהודעה קצרה שנעלמת. */
    @SuppressWarnings("deprecation")
    static void replied(Context c, String gid) {
        cancel(c, gid);
    }
}
