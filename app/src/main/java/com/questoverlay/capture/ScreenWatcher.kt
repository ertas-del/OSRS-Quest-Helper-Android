package com.questoverlay.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.WindowManager

/**
 * Takes a look at the screen every [intervalMs] while Auto-check is on, reads the text on it and
 * hands the lines to [onLines] on the main thread. Uses Android's screen capture (the same thing
 * screen recorders use): it sees only pixels, never the game's memory, files or network.
 *
 * Nothing is saved. Each picture is read and thrown away.
 */
class ScreenWatcher(
    private val context: Context,
    private val onLines: (List<OcrLine>) -> Unit,
    private val onStopped: () -> Unit,
    private val intervalMs: Long = 1500
) {
    private val mpm = context.getSystemService(MediaProjectionManager::class.java)
    private val thread = HandlerThread("screen-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val reader = TextReader()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var images: ImageReader? = null
    @Volatile private var busy = false
    @Volatile var running = false
        private set

    /** Starts watching with the permission Android just gave. Throws if Android refuses. */
    fun start(resultCode: Int, data: Intent) {
        val p = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("No screen capture")
        projection = p
        // Android 14+ insists on a callback before the capture starts.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (running) {
                        release()
                        onStopped()
                    }
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                handler.post { resize(width, height) }
            }
        }, handler)

        val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = context.resources.displayMetrics.densityDpi
        // Full resolution: the game's text is small, and the reader needs about 16 pixels per letter.
        val w = bounds.width()
        val h = bounds.height()
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        images = ir
        display = p.createVirtualDisplay(
            "breadcrumbs-autocheck", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler
        )
        running = true
        handler.postDelayed(tick, 800)
    }

    /** Stops watching (the player switched it off, or the overlay closed). */
    fun stop() {
        if (!running) return
        release()
    }

    /** Stops for good, including the background thread. */
    fun shutdown() {
        stop()
        reader.close()
        thread.quitSafely()
    }

    private fun release() {
        running = false
        handler.removeCallbacks(tick)
        try { display?.release() } catch (e: Exception) {}
        try { images?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        display = null
        images = null
        projection = null
    }

    /** "A single app" sharing reports the game window's size, which can change (e.g. rotating). */
    private fun resize(width: Int, height: Int) {
        val d = display ?: return
        if (width <= 0 || height <= 0) return
        val old = images
        val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        images = ir
        d.resize(width, height, context.resources.displayMetrics.densityDpi)
        d.surface = ir.surface
        try { old?.close() } catch (e: Exception) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            grab()
            handler.postDelayed(this, intervalMs)
        }
    }

    private fun grab() {
        if (busy) return // still reading the last one: skip this look rather than queue up
        val image = try {
            images?.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        val bitmap = try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (padded.width == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (bitmap == null) return
        busy = true
        reader.read(bitmap) { lines ->
            bitmap.recycle()
            busy = false
            if (running) main.post { onLines(lines) }
        }
    }
}
