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
 *
 * QZ only borrows the screen here. It needs the foreground to come up and claim the bike,
 * but the console is meant to settle on the launcher, not on QZ fullscreen, so the boot
 * start leaves a marker behind: FloatingWindowGFG reads it when the metrics window opens -
 * that is, once the bike is connected and the rider has answered the USB permission dialog -
 * and hands the screen back to the launcher. Keying the hand-off off the overlay rather than
 * off a timer is what keeps that dialog on screen long enough to be answered.
 */
public class QzBootReceiver extends BroadcastReceiver {

    private static final String TAG = "QzBootReceiver";

    /**
     * Just long enough for the home screen to settle before QZ takes the foreground. This used to
     * be 45 seconds, on the theory that QZ had to arrive after the OEM stack to win the USB claim
     * — but a claim can be lost at any time, not only at boot, so that was buying a delay the user
     * felt on every start-up in exchange for a guarantee it never actually gave. The session
     * watchdog in FitProDeviceService reclaims the interface whenever it is taken, which covers
     * the boot race and the attract-screen hand-off alike.
     */
    private static final long START_DELAY_MS = 8_000L;

    /** Shared preferences file carrying the one-shot hand-off marker. */
    static final String PREFS_NAME = "QzBoot";

    /** elapsedRealtime() at which the boot start was scheduled; absent when nothing is pending. */
    static final String PREF_HANDOFF_SINCE = "handoffPendingSince";

    /**
     * How long the marker stays good for. Past this the console is no longer booting: someone is
     * using it, and opening the overlay by hand must not throw them off the screen they chose.
     */
    static final long HANDOFF_WINDOW_MS = 5L * 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = (intent == null) ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_HANDOFF_SINCE, SystemClock.elapsedRealtime())
                .apply();

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
