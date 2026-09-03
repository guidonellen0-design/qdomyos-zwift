package org.cagnulen.qdomyoszwift;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * start leaves a marker behind: FitProDeviceService reads it the moment the board is
 * actually streaming, and hands the screen back to the launcher.
 *
 * Keying the hand-off off a live session is what keeps the USB permission dialog on screen
 * long enough to be answered. An earlier version keyed it off the metrics overlay opening,
 * on the assumption that the overlay meant a connected bike; it does not. With
 * floating_startup the overlay opens as QZ starts, about a second before the dialog is even
 * up, and the ACTION_MAIN/CATEGORY_HOME intent tore that dialog down unanswered - so the
 * console landed on the launcher with an overlay full of zeroes and no bike (measured on
 * the S22i 2026-09-03).
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
    private static final String PREFS_NAME = "QzBoot";

    /** Set when this boot started QZ; cleared by the first hand-off that acts on it. */
    private static final String PREF_HANDOFF_PENDING = "handoffPending";

    /**
     * Gives the screen back to the launcher if QZ is only on it because this boot put it there.
     * Called from FitProDeviceService once the board is streaming, which is the first moment the
     * overlay has real numbers in it and QZ has stopped needing the foreground.
     *
     * The marker is one-shot, so a session that drops and reconnects later does not move the
     * rider off whatever app they had chosen. If the bike never connects the marker is never
     * consumed and QZ stays in front, where its own screen can be read.
     */
    public static void handOffToLauncherIfBootStart(Context context) {
        final SharedPreferences bootPrefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!bootPrefs.getBoolean(PREF_HANDOFF_PENDING, false)) {
            return;
        }
        bootPrefs.edit().remove(PREF_HANDOFF_PENDING).apply();

        final Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(home);
        QLog.d(TAG, "bike connected after a boot start, handing the screen to the launcher");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = (intent == null) ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HANDOFF_PENDING, true)
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
