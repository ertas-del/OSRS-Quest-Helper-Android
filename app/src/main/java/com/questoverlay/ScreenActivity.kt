package com.questoverlay

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * The plain scrolling page the coach screens share: a title, cards, and helpers for the usual
 * rows (switches, sliders, notes). [build] draws the page and is called again by [rebuild].
 */
abstract class ScreenActivity : Activity() {
    protected lateinit var content: LinearLayout
    protected val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.SCREEN_BG)
        scroll.clipToPadding = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    protected abstract fun build()

    protected fun rebuild() {
        content.removeAllViews()
        val pad = Ui.dp(this, 16)
        content.setPadding(pad, pad, pad, pad)
        build()
    }

    protected fun title(text: String, intro: String? = null) {
        content.addView(Ui.text(this, text, 26f, Ui.GOLD, bold = true))
        if (intro != null) {
            val t = Ui.text(this, intro, 13f, Ui.TAN)
            t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8))
            content.addView(t)
        }
    }

    protected fun card(heading: String? = null): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 14)
        c.setPadding(p, p, p, p)
        c.background = Ui.rounded(this, Ui.CARD_BG, 14, Ui.STROKE, 1)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 8)
        c.layoutParams = lp
        if (heading != null) c.addView(Ui.text(this, heading, 15f, Ui.TEXT, bold = true))
        content.addView(c)
        return c
    }

    protected fun note(c: LinearLayout, text: String, color: Int = Ui.MUTED, size: Float = 12f): TextView {
        val t = Ui.text(this, text, size, color)
        t.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        c.addView(t)
        return t
    }

    protected fun spaced(view: View, topDp: Int): View {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, topDp)
        view.layoutParams = lp
        return view
    }

    protected fun switchRow(c: LinearLayout, label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = android.widget.Switch(this)
        sw.text = label
        sw.setTextColor(Ui.TEXT)
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        sw.typeface = Ui.typeface(this, bold = false)
        sw.isChecked = checked
        sw.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.TAN, Ui.MUTED))
        sw.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x99FF981F.toInt(), Ui.STONE_DARK))
        sw.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2))
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        c.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (sub != null) {
            val t = Ui.text(this, sub, 11f, Ui.MUTED)
            t.setPadding(0, 0, Ui.dp(this, 48), Ui.dp(this, 4))
            c.addView(t)
        }
    }

    /** A labelled slider from [min] to [max]; [format] turns the value into the label. */
    protected fun sliderRow(c: LinearLayout, value: Int, min: Int, max: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
        val label = Ui.text(this, format(value), 13f, Ui.TEXT)
        label.setPadding(0, Ui.dp(this, 8), 0, 0)
        c.addView(label)
        val seek = SeekBar(this)
        seek.max = max - min
        seek.progress = value - min
        seek.progressTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.thumbTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = format(progress + min)
                if (fromUser) onChange(progress + min)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        c.addView(seek)
    }

    /** A row with text on the left and a small button on the right. */
    protected fun rowWithButton(c: LinearLayout, title: String, sub: String?, button: String, titleColor: Int = Ui.TEXT, onClick: () -> Unit) =
        rowWithButtons(c, title, sub, listOf(button to onClick), titleColor)

    /** A row with text on the left and small buttons on the right. */
    protected fun rowWithButtons(c: LinearLayout, title: String, sub: String?, buttons: List<Pair<String, () -> Unit>>, titleColor: Int = Ui.TEXT) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(Ui.text(this, title, 14f, titleColor, bold = true))
        if (sub != null) texts.addView(Ui.text(this, sub, 12f, Ui.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((label, onClick) in buttons) {
            val b = Ui.chip(this, label, false)
            b.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6))
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = Ui.dp(this, 6)
            row.addView(b, lp)
        }
        c.addView(spaced(row, 6))
    }
}
