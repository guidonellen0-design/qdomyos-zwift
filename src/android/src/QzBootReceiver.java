package org.cagnulen.qdomyoszwift;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * Brings QZ up by itself after the console finishes booting.
 *
 * On an iFIT console the vendor stack owns boot: com.ifit.eru's
 * TabletStartupReceiver fires on BOOT_COMPLETED and starts com.ifit.standalone
 * roughly three seconds later. Whichever app claims the USB HID interface last
 * wins it, so QZ has to arrive after that rather than race it - hence the
 * delay rather than an immediate start.
 *
 * The work is handed to AlarmManager because onReceive must return promptly and
 * the receiver's process may be torn down long before the delay elapses.
 */
public class QzBootReceiver extends BroadcastReceiver {

    private static final String TAG = "QzBootReceiver";

    /** Long enough for the iFIT stack to settle before QZ takes the foreground. */
    private static final long START_DELAY_MS = 45_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = (intent == null) ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }

        final Intent launch = new Intent(context, CustomQtActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        final PendingIntent pending = PendingIntent.getActivity(context, 0, launch, flags);

        final AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            QLog.e(TAG, "no AlarmManager available, QZ will not autostart");
            return;
        }

        final long at = SystemClock.elapsedRealtime() + START_DELAY_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending);
        } else {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending);
        }
        QLog.d(TAG, "boot completed, QZ autostart scheduled in " + (START_DELAY_MS / 1000) + "s");
    }
}
