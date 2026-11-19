package il.btchat;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.List;

import il.btchat.Model.Chat;
import il.btchat.Model.Msg;

/** הפעולות שבהתראה: תשובה, סימון כנקרא, וסגירה. */
public final class ReplyReceiver extends BroadcastReceiver {
    static final String REPLY = "il.btchat.REPLY", READ = "il.btchat.READ", DISMISS = "il.btchat.DISMISS";

    @Override
    public void onReceive(Context c, Intent i) {
        String gid = i.getStringExtra("g");
        if (gid == null) return;
        String a = i.getAction();
        if (DISMISS.equals(a)) { Notifier.forget(gid); return; }
        if (READ.equals(a)) {
            markRead(c, gid);
            Notifier.cancel(c, gid);
            return;
        }
        if (REPLY.equals(a) && Build.VERSION.SDK_INT >= 20) {
            Bundle r = RemoteInput.getResultsFromIntent(i);
            CharSequence text = r == null ? null : r.getCharSequence(Notifier.KEY_REPLY);
            if (text == null || text.toString().trim().length() == 0) return;
            if (!LinkService.connected()) {
                Toast.makeText(c, "הנגן לא מחובר למחשב — ההודעה לא נשלחה", Toast.LENGTH_LONG).show();
                return;
            }
            LinkService.sendMessage(c, gid, text.toString().trim(), null);
            markRead(c, gid);
            Notifier.replied(c, gid);
        }
    }

    static void markRead(Context c, String gid) {
        Store st = Store.get(c);
        Chat ch = st.chat(gid);
        if (ch == null) return;
        long t = ch.sort;
        List<Msg> l = st.snapshot(gid);
        for (Msg m : l) if (m.state == Msg.OK && m.time > t) t = m.time;
        st.markReadLocal(gid, t);
        if (Prefs.syncRead(c)) LinkService.read(gid, t);
    }
}
