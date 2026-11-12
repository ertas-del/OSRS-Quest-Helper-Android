package com.questoverlay.sailing

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.questoverlay.Ui

/**
 * Stone bubble shown when the quest card is collapsed and a sailing target is set.
 * Tap expands the card. [update] refreshes the tile distance without rebuilding.
 */
class SailingBubble(
    context: Context,
    val label: String,
    distance: Int,
    private val onClick: () -> Unit
) : FrameLayout(context) {

    init {
        val size = Ui.dp(context, 84)
        layoutParams = LayoutParams(size, size)
        background = Ui.oval(context, Ui.CARD_BG, Ui.GOLD, 2)

        val text = Ui.text(context, line(distance), 12f, Ui.TAN, bold = true)
        text.gravity = Gravity.CENTER
        text.maxLines = 3
        text.ellipsize = TextUtils.TruncateAt.END
        text.setPadding(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
        addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { onClick() }
    }

    /** Pass a negative distance when the minimap fix is missing or stale. */
    fun update(distance: Int) {
        (getChildAt(0) as? TextView)?.text = line(distance)
    }

    private fun line(distance: Int): String {
        val tiles = if (distance < 0) "\u2014" else "$distance tiles"
        return "$label\n$tiles"
    }
}
