package com.questoverlay.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Draws an outline around one thing on the game screen (the dialogue option to pick). It lives in
 * its own full-screen window that can't be touched, so every tap still goes to the game.
 */
class HighlightView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        color = 0xFF000000.toInt()
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFF981F.toInt() // the game's orange
    }
    private val rect = RectF()

    /** What to outline, in 0..1 screen coordinates; null draws nothing. */
    var box: OcrLine? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val b = box ?: return
        val pad = 6f * density
        rect.set(b.left * width - pad, b.top * height - pad, b.right * width + pad, b.bottom * height + pad)
        val r = 4f * density
        canvas.drawRoundRect(rect, r, r, outer)
        canvas.drawRoundRect(rect, r, r, inner)
    }
}
