package org.cagnulen.qdomyoszwift

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.nettarion.hyperborea.core.model.ExerciseData

/**
 * Screensaver for vendor consoles: the display never sleeps (QZ holds a permanent screen
 * wakelock, so Android's own Daydream can never run) and the OEM's idle scenery is unreachable —
 * that app needs the console board to draw anything and QZ holds the USB claim, deliberately and
 * at all times. So QZ shows the idle screen itself: after IDLE_TIMEOUT_MS with no pedalling and
 * no touch while the console is *resting*, AttractActivity takes the foreground with a picture
 * slideshow, and when pedalling resumes the app the rider was resting on comes back.
 *
 * Resting means one of two foreground states: QZ itself, or the launcher. The launcher counts
 * because of the boot hand-off — since QZ started giving the screen back to the launcher once the
 * bike is streaming, QZ is no longer what the console sits on between rides, and gating on
 * [qzResumed] alone left the panel showing a static home screen indefinitely (S22i, 17 h at a
 * stretch, 2026-09-04). Anything else in front is an app the rider deliberately opened — a video,
 * a game stream, music — and the slideshow never fights those.
 *
 * The launcher package is resolved from the HOME intent rather than hardcoded, and the foreground
 * package is fed in by [FloatingWindowGFG]'s existing 1 Hz watch: if that watch is not running
 * (overlay closed, or PACKAGE_USAGE_STATS not granted) the front package reads as unknown and the
 * gate falls back to [qzResumed] alone — the pre-2026-09-04 behaviour, never a wrong hijack.
 *
 * Auto-refront happens ONLY while our own attract screen is active, so QZ never fights a user who
 * deliberately opened some other app while riding.
 *
 * A game stream is the one deliberately opened app that does get interrupted, because leaving it
 * running costs something at the other end: Moonlight's Game activity stops the connection in its
 * own onStop(), so fronting the launcher over it is a clean disconnect. QZ cannot see touches
 * inside that stream — the overlay is hidden over it and gets no events — so pedalling is the only
 * activity signal there is, and the timeout is split accordingly: [STREAM_IDLE_TIMEOUT_MS] once
 * the rider has actually pedalled during this stint, and the much longer
 * [STREAM_UNRIDDEN_TIMEOUT_MS] before that. Choosing a route in MyWhoosh through the stream takes
 * minutes with the pedals still, and cutting the stream out from under that would be worse than
 * leaving it up.
 */
object IdleAttract {

    private const val TAG = "IdleAttract"
    private const val IDLE_TIMEOUT_MS = 10 * 60_000L
    private const val REFRONT_COOLDOWN_MS = 15_000L

    /** No pedalling for this long in a game stream the rider has already ridden — disconnect. */
    private const val STREAM_IDLE_TIMEOUT_MS = 5 * 60_000L

    /** Same, for a stream nobody has pedalled in yet: long enough to set a ride up in. */
    private const val STREAM_UNRIDDEN_TIMEOUT_MS = 20 * 60_000L

    /** Apps whose foreground activity is a live remote stream. Moonlight is the only one here. */
    private val STREAM_PACKAGES = setOf("com.limelight")

    @Volatile private var qzResumed = false
    @Volatile private var attractActive = false
    @Volatile private var lastActiveMs = SystemClock.elapsedRealtime()
    @Volatile private var lastFrontAttemptMs = 0L

    /** Foreground package as last reported by the overlay's watch; null means "not known". */
    @Volatile private var frontPackage: String? = null

    /** What was in front when the slideshow started, so pedalling returns the rider to it. */
    @Volatile private var attractOrigin: String? = null

    @Volatile private var cachedHomePackage: String? = null

    /** Whether the pedals have turned since the current foreground app took the screen. */
    @Volatile private var pedalledInFront = false

    @Volatile private var lastStreamStopMs = 0L

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

    /**
     * Fed by [FloatingWindowGFG]'s foreground watch at ~1 Hz; null when the foreground app cannot
     * be read, or when the watch stops. A *change* of foreground app is itself activity: the rider
     * just touched something, so the idle clock restarts.
     */
    @JvmStatic
    fun onForegroundPackage(pkg: String?) {
        if (pkg != frontPackage) {
            frontPackage = pkg
            // A new app in front is a new stint: whether the rider pedalled in the last one says
            // nothing about this one, and a stream just opened has not been ridden in yet.
            pedalledInFront = false
            if (pkg != null) {
                lastActiveMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * The slideshow is gone — dismissed by touch, or covered by the app we fronted. Without this
     * the [attractActive] latch would only ever be cleared by QZ resuming, so a slideshow dismissed
     * while resting on the launcher would never come back.
     */
    @JvmStatic
    fun onAttractFinished() {
        attractActive = false
        attractOrigin = null
        lastActiveMs = SystemClock.elapsedRealtime()
    }

    /** Called at ~1 Hz from the FitPro data stream — doubles as the idle ticker. */
    fun onExerciseData(ctx: Context, data: ExerciseData) {
        val now = SystemClock.elapsedRealtime()
        val pedalling = (data.speed ?: 0f) > 0.5f || (data.cadence ?: 0) > 5
        if (pedalling) {
            lastActiveMs = now
            pedalledInFront = true
            if (attractActive && !qzResumed && now - lastFrontAttemptMs > REFRONT_COOLDOWN_MS) {
                lastFrontAttemptMs = now
                val target = attractOrigin ?: ctx.packageName
                front(ctx, target, "pedalling resumed — fronting $target")
            }
            return
        }
        val frontNow = frontPackage
        if (frontNow != null && frontNow in STREAM_PACKAGES) {
            val timeout = if (pedalledInFront) STREAM_IDLE_TIMEOUT_MS else STREAM_UNRIDDEN_TIMEOUT_MS
            if (now - lastActiveMs > timeout && now - lastStreamStopMs > REFRONT_COOLDOWN_MS) {
                lastStreamStopMs = now
                stopStream(ctx, frontNow, timeout)
            }
            return
        }
        if (resting(ctx) && !attractActive && now - lastActiveMs > IDLE_TIMEOUT_MS) {
            attractActive = true
            attractOrigin = if (qzResumed) ctx.packageName else frontPackage
            startAttract(ctx)
        }
    }

    /** QZ in front, or the launcher in front. Everything else is a deliberately opened app. */
    private fun resting(ctx: Context): Boolean {
        if (qzResumed) return true
        val front = frontPackage ?: return false
        return front == homePackageOf(ctx)
    }

    /**
     * The resolved HOME package. A device with no default home set resolves to the system chooser
     * instead, which matches no real foreground package — so the gate simply stays closed rather
     * than guessing.
     */
    private fun homePackageOf(ctx: Context): String? {
        cachedHomePackage?.let { return it }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = try {
            ctx.packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        } catch (e: Exception) {
            QLog.w(TAG, "resolving the home package failed: ${e.message}")
            null
        }
        if (pkg != null) {
            cachedHomePackage = pkg
            QLog.d(TAG, "launcher package is $pkg")
        }
        return pkg
    }

    private fun startAttract(ctx: Context) {
        QLog.i(TAG, "idle ${IDLE_TIMEOUT_MS / 60_000} min over ${attractOrigin ?: "QZ"} — starting attract slideshow")
        // Its own task, not ours: fronting QZ later must cover the slideshow rather than land
        // behind it, and the slideshow must never be what the rider finds on top of QZ's task.
        val intent = Intent(ctx, AttractActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            QLog.w(TAG, "starting attract slideshow failed: ${e.message}")
            attractActive = false
            attractOrigin = null
        }
    }

    /**
     * End an idle game stream by putting the launcher in front of it. There is no API to ask
     * Moonlight to disconnect, and QZ cannot force-stop another app without root — but it does not
     * need to: losing the foreground is what Moonlight itself treats as "stop the connection".
     * The launcher is the target so the console lands where it rests between rides; if no home is
     * resolvable, QZ's own screen does the same job.
     */
    private fun stopStream(ctx: Context, streamPkg: String, timeoutMs: Long) {
        val target = homePackageOf(ctx) ?: ctx.packageName
        front(ctx, target, "no pedalling for ${timeoutMs / 60_000} min in $streamPkg — fronting $target to end the stream")
    }

    private fun front(ctx: Context, pkg: String, why: String) {
        // The launcher is fronted as HOME, not by its launch activity: that is what the boot
        // hand-off uses and what a launcher expects to be asked for.
        val intent = if (pkg == homePackageOf(ctx)) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        }
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
