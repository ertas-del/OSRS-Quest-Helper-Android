package com.questoverlay.slayer

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.questoverlay.Ui

/** The monster card, shared by the Slayer screen and the floating card: where, what to wear, what to bring, food. */
object SlayerViews {
    private fun line(ctx: Context, parent: LinearLayout, text: String, color: Int = Ui.TEXT, size: Float = 12f, bold: Boolean = false, top: Int = 2) {
        val t = Ui.text(ctx, text, size, color, bold = bold)
        t.setPadding(0, Ui.dp(ctx, top), 0, Ui.dp(ctx, 1))
        parent.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun heading(ctx: Context, parent: LinearLayout, text: String) = line(ctx, parent, text.uppercase(), Ui.TAN, 11f, bold = true, top = 10)

    /** True when two place names are about the same place ("Catacombs of Kourend" / "the Catacombs"). */
    fun samePlace(a: String, b: String): Boolean {
        val x = SlayerNames.key(a)
        val y = SlayerNames.key(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    fun foodColor(amount: String): Int = when (amount) {
        "heavy" -> Ui.RED
        "moderate" -> Ui.GOLD
        "light" -> Ui.GREEN
        else -> Ui.MUTED
    }

    fun foodWord(amount: String): String = when (amount) {
        "heavy" -> "Lots of food"
        "moderate" -> "Some food"
        "light" -> "Little food"
        "none" -> "No food needed"
        else -> "Food: not known"
    }

    /**
     * [place] is where the master sent you (Konar names one), so that route comes first. [row] is the
     * master's table line for the monster (kills, requirements), if there is one.
     */
    fun monsterCard(ctx: Context, name: String, card: MonsterCard?, row: TaskRow?, place: String?): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(ctx, 10)
        box.setPadding(p, p, p, p)
        box.background = Ui.rounded(ctx, Ui.STONE_DARK, 6, Ui.STROKE, 1)

        line(ctx, box, card?.name ?: row?.name ?: name, Ui.GOLD, 16f, bold = true, top = 0)
        if (row != null) {
            val bits = ArrayList<String>()
            if (row.slayer > 0) bits.add("Slayer ${row.slayer}")
            if (row.combat > 0) bits.add("Combat ${row.combat}")
            bits.add("${row.kills} kills")
            line(ctx, box, bits.joinToString(" · "), Ui.TAN, 12f)
            if (row.needs.isNotBlank()) line(ctx, box, "Needs: ${row.needs}", Ui.MUTED, 11f)
        }
        if (card == null) {
            line(ctx, box, "No prep page for this one yet.", Ui.MUTED, 12f, top = 8)
            if (row != null) line(ctx, box, "Places: " + row.places.joinToString(", "), Ui.TEXT, 12f, top = 8)
            return box
        }

        // What it does to you, and what to do about it.
        val hits = buildString {
            if (card.attackStyles.isNotEmpty()) append("Hits with ${card.attackStyles.joinToString(", ")}")
            if (card.maxHit != null) append(if (isEmpty()) "Max hit ${card.maxHit}" else ", max hit ${card.maxHit}")
        }
        if (hits.isNotEmpty()) line(ctx, box, hits, Ui.TEXT, 13f, top = 6)
        card.protectPrayer?.let { line(ctx, box, "Pray: $it", Ui.TEXT, 12f) }
        card.weakness?.let { line(ctx, box, "Weak to: $it", Ui.TEXT, 12f) }

        // Food.
        if (card.foodAmount.isNotEmpty()) {
            line(ctx, box, foodWord(card.foodAmount) + (card.foodNote?.let { " — $it" } ?: ""), foodColor(card.foodAmount), 12f, bold = true, top = 8)
        }

        // Bring.
        if (card.mustBring.isNotEmpty()) {
            heading(ctx, box, "Bring")
            for (b in card.mustBring) line(ctx, box, "• $b", Ui.GOLD, 12f, bold = true)
        }

        // Wear.
        if (card.tiers.isNotEmpty()) {
            heading(ctx, box, "Wear" + (card.gearStyle?.let { " ($it)" } ?: ""))
            for (t in card.tiers) line(ctx, box, "${t.label}: ${t.items.joinToString(", ")}", Ui.TEXT, 12f)
        }

        // Where, your place first.
        if (card.locations.isNotEmpty()) {
            heading(ctx, box, "Where")
            val sorted = if (place != null) card.locations.sortedBy { if (samePlace(it.name, place)) 0 else 1 } else card.locations
            var any = false
            for ((i, l) in sorted.withIndex()) {
                val mine = place != null && samePlace(l.name, place)
                any = any || mine
                line(ctx, box, l.name + (if (mine) "  ← your task" else ""), if (mine) Ui.GREEN else Ui.TEXT, 13f, bold = true, top = if (i == 0) 2 else 6)
                if (l.how.isNotBlank()) line(ctx, box, l.how, Ui.MUTED, 11f)
                val tags = listOfNotNull(
                    if (l.multi == true) "multi-combat" else null,
                    if (l.cannon == true) "cannon OK" else if (l.cannon == false) "no cannon" else null
                )
                if (tags.isNotEmpty()) line(ctx, box, tags.joinToString(" · "), Ui.TAN, 11f)
                l.note?.takeIf { it.isNotBlank() }?.let { line(ctx, box, it, Ui.MUTED, 11f) }
            }
            if (place != null && !any) line(ctx, box, "Your task place \"$place\" isn't listed above; same monsters, check the Map button.", Ui.MUTED, 11f, top = 6)
        }

        if (card.tips.isNotEmpty()) {
            heading(ctx, box, "Tips")
            for (t in card.tips) line(ctx, box, "• $t", Ui.TEXT, 12f)
        }
        line(ctx, box, "Gear and food are suggestions. Max hits and routes are from the OSRS Wiki.", Ui.MUTED, 10f, top = 10)
        return box
    }
}
