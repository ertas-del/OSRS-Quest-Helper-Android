package com.questoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** Colours and small view helpers. Everything is built in code, so there are no XML resources. */
object Ui {
    val SCREEN_BG = 0xFF17110B.toInt()
    val PANEL_BG = 0xF0221A12.toInt()
    val CARD_BG = 0xFF2A2016.toInt()
    val STROKE = 0xFF6B5428.toInt()
    val GOLD = 0xFFFFC83D.toInt()
    val TAN = 0xFFD9C79B.toInt()
    val TEXT = 0xFFF3EBD6.toInt()
    val MUTED = 0xFFA89B7C.toInt()
    val GREEN = 0xFF63C174.toInt()
    val RED = 0xFFE5735C.toInt()
    val BTN_BG = 0xFF3A2D1C.toInt()
    val TRACK = 0xFF3D3123.toInt()
    val DARK_TEXT = 0xFF1B1409.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = dpf(ctx, radiusDp.toFloat())
        if (strokeDp > 0) d.setStroke(dp(ctx, strokeDp), strokeColor)
        return d
    }

    fun oval(ctx: Context, fill: Int, strokeColor: Int, strokeDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(fill)
        d.setStroke(dp(ctx, strokeDp), strokeColor)
        return d
    }

    fun text(
        ctx: Context,
        s: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        italic: Boolean = false
    ): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        t.setTypeface(Typeface.DEFAULT, style)
        return t
    }

    fun button(ctx: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 14f, if (primary) DARK_TEXT else TEXT, bold = true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        t.background = rounded(ctx, if (primary) GOLD else BTN_BG, 10, STROKE, 1)
        t.setOnClickListener { onClick() }
        return t
    }
}

/** A ScrollView that grows with its content but never taller than [maxHeightPx]. */
class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx
        else min(maxHeightPx, MeasureSpec.getSize(heightMeasureSpec))
        val capped = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}

/** Compass that points the way for a step. Shapes only, so it needs no fonts or images. */
class CompassView(context: Context) : View(context) {

    var dir: Dir = Dir.NONE
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpf(context, 2f)
        color = Ui.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textAlign = Paint.Align.CENTER
        textSize = Ui.dpf(context, 9f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - Ui.dpf(context, 3f)

        fillPaint.color = Ui.CARD_BG
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // N / E / S / W letters sit just inside the ring.
        val inset = Ui.dpf(context, 11f)
        val baseline = labelPaint.textSize * 0.35f
        canvas.drawText("N", cx, cy - r + inset, labelPaint)
        canvas.drawText("S", cx, cy + r - inset + labelPaint.textSize * 0.7f, labelPaint)
        canvas.drawText("E", cx + r - inset + Ui.dpf(context, 1f), cy + baseline, labelPaint)
        canvas.drawText("W", cx - r + inset - Ui.dpf(context, 1f), cy + baseline, labelPaint)

        when {
            dir.isCompass -> {
                canvas.save()
                canvas.rotate(dir.degrees, cx, cy)
                path.reset()
                path.moveTo(cx, cy - r * 0.62f)
                path.lineTo(cx + r * 0.22f, cy + r * 0.26f)
                path.lineTo(cx, cy + r * 0.10f)
                path.lineTo(cx - r * 0.22f, cy + r * 0.26f)
                path.close()
                fillPaint.color = Ui.GOLD
                canvas.drawPath(path, fillPaint)
                canvas.restore()
            }
            dir == Dir.UP || dir == Dir.DOWN -> {
                val up = dir == Dir.UP
                val tip = if (up) cy - r * 0.45f else cy + r * 0.45f
                val base = if (up) cy + r * 0.30f else cy - r * 0.30f
                path.reset()
                path.moveTo(cx, tip)
                path.lineTo(cx + r * 0.38f, base)
                path.lineTo(cx - r * 0.38f, base)
                path.close()
                fillPaint.color = Ui.GOLD
                canvas.drawPath(path, fillPaint)
            }
            dir == Dir.HERE -> {
                fillPaint.color = Ui.GOLD
                canvas.drawCircle(cx, cy, r * 0.18f, fillPaint)
                val ring = Paint(Paint.ANTI_ALIAS_FLAG)
                ring.style = Paint.Style.STROKE
                ring.strokeWidth = Ui.dpf(context, 2f)
                ring.color = Ui.GOLD
                canvas.drawCircle(cx, cy, r * 0.42f, ring)
            }
            else -> {
                fillPaint.color = Ui.MUTED
                canvas.drawCircle(cx, cy, r * 0.08f, fillPaint)
            }
        }
    }
}

/** Small square used as a checkbox for items and as a progress marker for steps. */
class MarkView(context: Context) : View(context) {

    enum class State { TODO, CURRENT, DONE }

    var state: State = State.TODO
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpf(context, 1.6f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpf(context, 2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Ui.DARK_TEXT
    }
    private val rect = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = Ui.dp(context, 20)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = strokePaint.strokeWidth
        rect.set(inset, inset, w - inset, h - inset)
        val radius = Ui.dpf(context, 5f)

        when (state) {
            State.DONE -> {
                fillPaint.color = Ui.GREEN
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                path.reset()
                path.moveTo(w * 0.26f, h * 0.52f)
                path.lineTo(w * 0.43f, h * 0.69f)
                path.lineTo(w * 0.75f, h * 0.33f)
                canvas.drawPath(path, tickPaint)
            }
            State.CURRENT -> {
                strokePaint.color = Ui.GOLD
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
                fillPaint.color = Ui.GOLD
                canvas.drawCircle(w / 2f, h / 2f, w * 0.17f, fillPaint)
            }
            State.TODO -> {
                strokePaint.color = Ui.MUTED
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            }
        }
    }
}
