package il.btchat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** מפעיל את השירות אחרי הדלקה ואחרי עדכון האפליקציה. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        LinkService.start(c);
    }
}
