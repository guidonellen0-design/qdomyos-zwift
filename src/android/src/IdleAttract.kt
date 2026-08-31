package org.cagnulen.qdomyoszwift

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.nettarion.hyperborea.core.model.ExerciseData

/**
 * Screensaver for vendor consoles: the display never sleeps (the OEM app holds a permanent
 * screen wakelock), so Android's Daydream can never run. The OEM's own idle behaviour was its
 * in-app attract screen — scenery videos — shown simply because that app was front. This
 * restores it: after IDLE_TIMEOUT_MS with no pedalling and no touch while QZ is front, hand
 * the foreground to the OEM app; when pedalling resumes, bring QZ back.
 *
 * Auto-refront happens ONLY while our own attract hand-off is active, so QZ never fights a
 * user who deliberately opened some other app while riding.
 */
object IdleAttract {

    private const val TAG = "IdleAttract"
    private const val ATTRACT_PACKAGE = "com.ifit.standalone"
    private const val IDLE_TIMEOUT_MS = 10 * 60_000L
    private const val REFRONT_COOLDOWN_MS = 15_000L

    @Volatile private var qzResumed = false
    @Volatile private var attractActive = false
    @Volatile private var lastActiveMs = SystemClock.elapsedRealtime()
    @Volatile private var lastFrontAttemptMs = 0L

    @JvmStatic
    fun onQzResumed() {
        qzResumed = true
        attractActive = false
        lastActiveMs = SystemClock.elapsedRealtime()
    }

    @JvmStatic
    fun onQzPaused() {
        qzResumed = false
    }

    @JvmStatic
    fun onUserInteraction() {
        lastActiveMs = SystemClock.elapsedRealtime()
    }

    /** Called at ~1 Hz from the FitPro data stream — doubles as the idle ticker. */
    fun onExerciseData(ctx: Context, data: ExerciseData) {
        val now = SystemClock.elapsedRealtime()
        val pedalling = (data.speed ?: 0f) > 0.5f || (data.cadence ?: 0) > 5
        if (pedalling) {
            lastActiveMs = now
            if (attractActive && !qzResumed && now - lastFrontAttemptMs > REFRONT_COOLDOWN_MS) {
                lastFrontAttemptMs = now
                front(ctx, ctx.packageName, "pedalling resumed — fronting QZ")
            }
            return
        }
        if (qzResumed && !attractActive && now - lastActiveMs > IDLE_TIMEOUT_MS) {
            attractActive = true
            front(ctx, ATTRACT_PACKAGE, "idle ${IDLE_TIMEOUT_MS / 60_000} min — handing front to attract screen")
        }
    }

    private fun front(ctx: Context, pkg: String, why: String) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            QLog.w(TAG, "no launch intent for $pkg")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        QLog.i(TAG, why)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            QLog.w(TAG, "front($pkg) failed: ${e.message}")
        }
    }
}
