package org.cagnulen.qdomyoszwift

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/**
 * The idle screen. QZ owns the console board at all times, so the scenery cannot come from the
 * OEM app — that app would need the board to draw anything and cannot have it. This shows the
 * pictures itself instead: a plain full-screen slideshow inside QZ's own APK, cross-fading
 * through whatever images have been dropped into one of the attract directories.
 *
 * It runs in its own task (see the manifest's taskAffinity) so that bringing QZ's main task to
 * the front — which is how IdleAttract reacts to pedalling — simply covers this activity, at
 * which point it finishes itself. Touching the screen ends it too.
 */
class AttractActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var views: Array<ImageView>
    private val handler = Handler(Looper.getMainLooper())
    private var files: List<File> = emptyList()
    private var index = 0
    private var showing = 0
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )

        files = collectImages()
        if (files.isEmpty()) {
            // Nothing to show is not worth a black screen: hand the console straight back rather
            // than leaving the rider looking at nothing.
            QLog.w(TAG, "no images in " + attractDirs().joinToString(", ") + " — not starting slideshow")
            finish()
            return
        }
        QLog.i(TAG, "attract slideshow starting with ${files.size} image(s)")

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        views = Array(2) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.alpha = 0f
            root.addView(
                iv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            iv
        }
        setContentView(root)
        hideSystemUi()
        root.post { advance(firstFrame = true) }
    }

    /**
     * Loads the next image on a background thread and cross-fades it in. Decoding a console-sized
     * photo takes long enough to stutter the fade if it happens on the main thread.
     */
    private fun advance(firstFrame: Boolean) {
        if (stopped || files.isEmpty()) return
        val file = files[index % files.size]
        index++
        Thread {
            val bitmap = try {
                decodeScaled(file)
            } catch (e: Exception) {
                QLog.w(TAG, "decode failed for ${file.name}: ${e.message}")
                null
            } catch (e: OutOfMemoryError) {
                QLog.w(TAG, "out of memory decoding ${file.name}")
                null
            }
            handler.post {
                if (stopped) return@post
                if (bitmap == null) {
                    // Skip the unreadable file rather than stalling the slideshow on it.
                    handler.postDelayed({ advance(firstFrame) }, 100L)
                    return@post
                }
                val next = (showing + 1) % views.size
                views[next].setImageBitmap(bitmap)
                views[next].animate().alpha(1f).setDuration(if (firstFrame) 0L else FADE_MS).start()
                if (!firstFrame) {
                    views[showing].animate().alpha(0f).setDuration(FADE_MS).start()
                }
                showing = next
                handler.postDelayed({ advance(firstFrame = false) }, DWELL_MS)
            }
        }.start()
    }

    /**
     * Decodes at no more than roughly screen resolution. The console has little heap and the
     * pictures pushed to it are whatever size the rider happened to have.
     */
    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val w = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        val h = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** Directories searched for pictures, in order of preference. */
    private fun attractDirs(): List<File> {
        val dirs = ArrayList<File>()
        getExternalFilesDir(null)?.let { dirs.add(File(it, "attract")) }
        // Shared storage is the obvious place to drop photos from a PC, but reading it needs a
        // storage permission QZ may not hold, in which case listFiles() simply returns null.
        dirs.add(File("/sdcard/Pictures/qz-attract"))
        return dirs
    }

    private fun collectImages(): List<File> {
        for (dir in attractDirs()) {
            val found = dir.listFiles { f: File ->
                f.isFile && EXTENSIONS.any { f.name.lowercase().endsWith(it) }
            }
            if (found != null && found.isNotEmpty()) {
                return found.sortedBy { it.name }
            }
        }
        return emptyList()
    }

    private fun hideSystemUi() {
        root.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            QLog.i(TAG, "attract dismissed by touch")
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        // Fully covered means QZ's own task came back to the front — pedalling resumed, or the
        // rider went somewhere else. Either way this activity has served its purpose; leaving it
        // in the back stack would put the slideshow back on screen the next time QZ is dismissed.
        if (!isFinishing) {
            QLog.i(TAG, "attract stopped — finishing")
            finish()
        }
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        // Release IdleAttract's latch. QZ resuming used to be the only thing that cleared it,
        // which is wrong now that the console rests on the launcher: a slideshow dismissed there
        // would otherwise be the last one this process ever showed.
        IdleAttract.onAttractFinished()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AttractActivity"
        private const val DWELL_MS = 12_000L
        private const val FADE_MS = 1_500L
        private val EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp")
    }
}
