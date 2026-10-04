package com.questoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The classic RuneScape interface look: brown stone panels with bevelled edges, a pixel font,
 * and orange / yellow / white text with a hard black shadow.
 * Everything is drawn in code, so there are no XML resources.
 */
object Ui {
    // Stone
    val SCREEN_BG = 0xFF1C1813.toInt()
    val PANEL_BG = 0xFF352D23.toInt() // see-through-ness comes from the opacity slider
    val CARD_BG = 0xFF352D23.toInt()
    val STONE_LIGHT = 0xFF6A5C48.toInt()
    val STONE_DARK = 0xFF19140F.toInt()
    val STROKE = 0xFF000000.toInt()
    val BTN_BG = 0xFF4B3F31.toInt()
    val BTN_PRESSED = 0xFF2E261D.toInt()
    val TRACK = 0xFF1C1813.toInt()

    // Text, in the game's own colours
    val GOLD = 0xFFFF981F.toInt()      // orange: titles and highlights
    val TAN = 0xFFFFFF00.toInt()       // yellow: labels, sections, meta
    val TEXT = 0xFFFFFFFF.toInt()      // white: body text
    val MUTED = 0xFFD2C6AE.toInt()     // faded parchment: notes and hints
    val GREEN = 0xFF00E000.toInt()     // quest complete
    val RED = 0xFFFF2B2B.toInt()       // quest not started
    val DARK_TEXT = 0xFF000000.toInt()

    @Volatile private var font: Typeface? = null
    @Volatile private var fontBold: Typeface? = null

    /** Pixelify Sans (SIL Open Font License), bundled in assets/fonts. Falls back to the system font. */
    fun typeface(ctx: Context, bold: Boolean): Typeface {
        val base = font ?: try {
            Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/PixelifySans.ttf").also { font = it }
        } catch (e: Exception) {
            Typeface.DEFAULT.also { font = it }
        }
        if (!bold) return base
        fontBold?.let { return it }
        // The font is variable (weight 400-700): ask for the real bold rather than a smeared fake one.
        val b = try {
            Typeface.Builder(ctx.applicationContext.assets, "fonts/PixelifySans.ttf")
                .setFontVariationSettings("'wght' 700")
                .build() ?: Typeface.create(base, 700, false)
        } catch (e: Exception) {
            Typeface.create(base, 700, false)
        }
        fontBold = b
        return b
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /**
     * Panels and buttons. With a stroke it becomes a bevelled stone panel (the radius is ignored,
     * the classic interface is square); without one it's a flat fill, used for highlights.
     */
    fun rounded(ctx: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): Drawable {
        if (strokeDp > 0) {
            // A coloured stroke (e.g. the quest on screen) becomes a coloured outer edge.
            val edge = if (strokeColor == STROKE || strokeColor == 0) STROKE else strokeColor
            return BevelDrawable(ctx, fill, STONE_LIGHT, STONE_DARK, edge, raised = true)
        }
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = dpf(ctx, minOf(radiusDp, 3).toFloat())
        return d
    }

    fun oval(ctx: Context, fill: Int, strokeColor: Int, strokeDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(fill)
        d.setStroke(dp(ctx, strokeDp), strokeColor)
        return d
    }

    /** A stone button face that looks pushed in while pressed (or when [down] is true). */
    fun stoneButton(ctx: Context, down: Boolean = false): Drawable {
        val up = BevelDrawable(ctx, BTN_BG, STONE_LIGHT, STONE_DARK, STROKE, raised = true)
        val pressed = BevelDrawable(ctx, BTN_PRESSED, STONE_DARK, STONE_LIGHT, STROKE, raised = false)
        if (down) return pressed
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        s.addState(intArrayOf(), up)
        return s
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
        // Nothing smaller than 12sp: small text is hard to read over a busy game screen.
        val size = maxOf(sizeSp, 12f)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        // The pixel font only for big bold titles, where it reads well. Everything else uses the
        // phone's own font, which stays sharp at small sizes.
        val title = bold && size >= 16f
        t.typeface = when {
            title -> typeface(ctx, bold = true)
            bold -> Typeface.create(Typeface.DEFAULT, 600, false)
            else -> Typeface.DEFAULT
        }
        t.letterSpacing = if (title) 0.02f else 0.01f
        t.setLineSpacing(dpf(ctx, 2f), 1f)
        // A tight black shadow (about one real pixel) keeps text readable on stone without blurring it.
        val shadow = if (title) dpf(ctx, 1f) else maxOf(1f, dpf(ctx, 0.5f))
        t.setShadowLayer(0.01f, shadow, shadow, DARK_TEXT)
        return t
    }

    fun button(ctx: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 14f, if (primary) TAN else GOLD, bold = true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        t.background = stoneButton(ctx)
        t.setOnClickListener { onClick() }
        return t
    }

    /** A selectable chip / tab: pushed-in stone with yellow text when active. */
    fun chip(ctx: Context, label: String, active: Boolean, sizeSp: Float = 12f): TextView {
        val t = text(ctx, label, sizeSp, if (active) TAN else GOLD, bold = true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        t.background = stoneButton(ctx, down = active)
        return t
    }
}

/**
 * Square stone bevel: a black outer line, then a light edge on the top-left and a dark edge on
 * the bottom-right (swapped when sunken), around a flat fill.
 */
class BevelDrawable(
    ctx: Context,
    private val fill: Int,
    private val topLeft: Int,
    private val bottomRight: Int,
    private val outer: Int,
    private val raised: Boolean
) : Drawable() {
    // Whole pixels, so the edges stay crisp on screens with fractional density.
    private val px = Ui.dp(ctx, 1).coerceAtLeast(1).toFloat()
    private val bevel = Ui.dp(ctx, 2).coerceAtLeast(2).toFloat()
    private val paint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val path = Path()
    private var paintAlpha = 255

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val btm = b.bottom.toFloat()

        paint.color = outer
        paint.alpha = alphaOf(outer)
        canvas.drawRect(l, t, r, btm, paint)

        val il = l + px
        val it = t + px
        val ir = r - px
        val ib = btm - px

        // Light edge (top + left)
        paint.color = topLeft
        paint.alpha = alphaOf(topLeft)
        canvas.drawRect(il, it, ir, ib, paint)

        // Dark edge (bottom + right), as a polygon so the corners meet diagonally.
        paint.color = bottomRight
        paint.alpha = alphaOf(bottomRight)
        path.reset()
        path.moveTo(ir, it)
        path.lineTo(ir, ib)
        path.lineTo(il, ib)
        path.lineTo(il + bevel, ib - bevel)
        path.lineTo(ir - bevel, ib - bevel)
        path.lineTo(ir - bevel, it + bevel)
        path.close()
        canvas.drawPath(path, paint)

        paint.color = fill
        paint.alpha = alphaOf(fill)
        canvas.drawRect(il + bevel, it + bevel, ir - bevel, ib - bevel, paint)
    }

    private fun alphaOf(color: Int): Int = ((color ushr 24) * paintAlpha) / 255

    override fun getPadding(padding: Rect): Boolean = false

    override fun setAlpha(alpha: Int) {
        paintAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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

/** Compass in the style of the game's minimap compass: a stone dial with a red-tipped needle. */
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
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = Ui.dpf(context, 10f)
        typeface = Ui.typeface(context, bold = true)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - Ui.dpf(context, 3f)

        // Dial: dark face, black rim, stone inner ring.
        fillPaint.color = Ui.STONE_DARK
        canvas.drawCircle(cx, cy, r, fillPaint)
        ringPaint.color = Ui.STROKE
        canvas.drawCircle(cx, cy, r, ringPaint)
        ringPaint.color = Ui.STONE_LIGHT
        canvas.drawCircle(cx, cy, r - Ui.dpf(context, 2f), ringPaint)

        // Letters with the hard shadow. N in red, like the in-game compass.
        val inset = Ui.dpf(context, 12f)
        val base = labelPaint.textSize * 0.35f
        fun letter(s: String, x: Float, y: Float, color: Int) {
            labelPaint.color = Ui.DARK_TEXT
            canvas.drawText(s, x + 1.5f, y + 1.5f, labelPaint)
            labelPaint.color = color
            canvas.drawText(s, x, y, labelPaint)
        }
        letter("N", cx, cy - r + inset, Ui.RED)
        letter("S", cx, cy + r - inset + labelPaint.textSize * 0.7f, Ui.TAN)
        letter("E", cx + r - inset + Ui.dpf(context, 1f), cy + base, Ui.TAN)
        letter("W", cx - r + inset - Ui.dpf(context, 1f), cy + base, Ui.TAN)

        when {
            dir.isCompass -> {
                canvas.save()
                canvas.rotate(dir.degrees, cx, cy)
                // Red front half, white back half: the classic compass needle.
                path.reset()
                path.moveTo(cx, cy - r * 0.62f)
                path.lineTo(cx + r * 0.16f, cy)
                path.lineTo(cx - r * 0.16f, cy)
                path.close()
                fillPaint.color = Ui.RED
                canvas.drawPath(path, fillPaint)
                path.reset()
                path.moveTo(cx, cy + r * 0.45f)
                path.lineTo(cx + r * 0.16f, cy)
                path.lineTo(cx - r * 0.16f, cy)
                path.close()
                fillPaint.color = Ui.TEXT
                canvas.drawPath(path, fillPaint)
                fillPaint.color = Ui.STROKE
                canvas.drawCircle(cx, cy, r * 0.06f, fillPaint)
                canvas.restore()
            }
            dir == Dir.UP || dir == Dir.DOWN -> {
                val up = dir == Dir.UP
                val tip = if (up) cy - r * 0.45f else cy + r * 0.45f
                val bse = if (up) cy + r * 0.30f else cy - r * 0.30f
                path.reset()
                path.moveTo(cx, tip)
                path.lineTo(cx + r * 0.38f, bse)
                path.lineTo(cx - r * 0.38f, bse)
                path.close()
                fillPaint.color = Ui.TAN
                canvas.drawPath(path, fillPaint)
                ringPaint.color = Ui.STROKE
                canvas.drawPath(path, ringPaint)
            }
            dir == Dir.HERE -> {
                // A yellow "X marks the spot".
                val k = r * 0.28f
                val cross = Paint(Paint.ANTI_ALIAS_FLAG)
                cross.style = Paint.Style.STROKE
                cross.strokeCap = Paint.Cap.ROUND
                cross.strokeWidth = Ui.dpf(context, 5f)
                cross.color = Ui.STROKE
                canvas.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
                canvas.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
                cross.strokeWidth = Ui.dpf(context, 3f)
                cross.color = Ui.TAN
                canvas.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
                canvas.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
            }
            else -> {
                fillPaint.color = Ui.MUTED
                canvas.drawCircle(cx, cy, r * 0.08f, fillPaint)
            }
        }
    }
}

/** Small sunken stone box used as a checkbox for items and as a progress marker for steps. */
class MarkView(context: Context) : View(context) {

    enum class State { TODO, CURRENT, DONE }

    var state: State = State.TODO
        set(value) {
            field = value
            invalidate()
        }

    private val box = BevelDrawable(context, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = Ui.dp(context, 20)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        box.setBounds(0, 0, width, height)
        box.draw(canvas)

        when (state) {
            State.DONE -> {
                path.reset()
                path.moveTo(w * 0.24f, h * 0.52f)
                path.lineTo(w * 0.43f, h * 0.71f)
                path.lineTo(w * 0.78f, h * 0.30f)
                tickPaint.strokeWidth = Ui.dpf(context, 4f)
                tickPaint.color = Ui.STROKE
                canvas.drawPath(path, tickPaint)
                tickPaint.strokeWidth = Ui.dpf(context, 2.2f)
                tickPaint.color = Ui.GREEN
                canvas.drawPath(path, tickPaint)
            }
            State.CURRENT -> {
                fillPaint.color = Ui.TAN
                canvas.drawRect(w * 0.32f, h * 0.32f, w * 0.68f, h * 0.68f, fillPaint)
            }
            State.TODO -> Unit
        }
    }
}
