package com.questoverlay.capture

/** The numbers on the orbs next to the minimap. Null when that orb wasn't read this look. */
data class VitalsReading(val hp: Int? = null, val prayer: Int? = null, val run: Int? = null, val spec: Int? = null) {
    val isEmpty: Boolean get() = hp == null && prayer == null && run == null && spec == null
}

/**
 * Where the minimap sits, in pixels of the captured screen. Other things (the orbs, the compass)
 * are found relative to it, so one setting covers every phone.
 */
data class HudLayout(val minimapX: Float, val minimapY: Float, val minimapRadius: Float, val screenW: Int, val screenH: Int) {
    companion object {
        /**
         * Measured on a Galaxy S26 and S22+ in landscape (2340×1080): the minimap's centre is about
         * 0.19–0.21 screen-heights in from the right edge and 0.18 down from the top.
         */
        fun default(w: Int, h: Int): HudLayout {
            val landscape = w >= h
            val unit = if (landscape) h.toFloat() else w.toFloat()
            return HudLayout(w - 0.197f * unit, 0.181f * unit, 0.143f * unit, w, h)
        }
    }

    val unit: Float get() = minOf(screenW, screenH).toFloat()
}

object VitalsReader {
    /**
     * Where each orb's number sits relative to the minimap's centre, in screen-heights
     * (measured on the S26: HP, Prayer, Run, Special attack from top to bottom).
     */
    private val SLOTS = listOf(
        -0.241f to -0.053f,
        -0.243f to 0.018f,
        -0.228f to 0.088f,
        -0.174f to 0.150f
    )
    private const val TOLERANCE = 0.045f
    private val NUMBER = Regex("""^\s*(\d{1,3})\s*$""")

    /** Reads the orb numbers from OCR lines (0..1 coordinates) using the minimap's position. */
    fun read(lines: List<OcrLine>, hud: HudLayout): VitalsReading {
        val w = hud.screenW.toFloat()
        val h = hud.screenH.toFloat()
        val values = arrayOfNulls<Int>(4)
        val best = FloatArray(4) { Float.MAX_VALUE }
        for (l in lines) {
            val m = NUMBER.find(l.text.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')) ?: continue
            val v = m.groupValues[1].toInt()
            val dx = (l.centerX * w - hud.minimapX) / hud.unit
            val dy = (l.centerY * h - hud.minimapY) / hud.unit
            for ((i, slot) in SLOTS.withIndex()) {
                val d = maxOf(kotlin.math.abs(dx - slot.first), kotlin.math.abs(dy - slot.second))
                if (d < TOLERANCE && d < best[i]) {
                    best[i] = d
                    values[i] = v
                }
            }
        }
        return VitalsReading(values[0], values[1], values[2], values[3])
    }
}

/** What the player wants to be warned about. Percentages are of the level (max) when known. */
data class VitalsSettings(
    val enabled: Boolean = true,
    val hpPercent: Int = 50,
    val prayerPoints: Int = 10,
    val runPercent: Int = 0,
    val maxHp: Int? = null,
    val maxPrayer: Int? = null
)

data class VitalsWarning(val id: String, val text: String, val urgent: Boolean)

/**
 * Turns orb readings into "Eat now!" style warnings. A value has to read the same (or lower) in
 * two looks in a row before it counts, so one misread "9" for "99" doesn't set anything off.
 * After a warning it stays quiet until the value drops further or [repeatMs] passes.
 */
class VitalsEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val repeatMs: Long = 45_000,
    private val urgentRepeatMs: Long = 12_000
) {
    private val last = HashMap<String, Int>()
    private val warnedAt = HashMap<String, Long>()
    private val warnedValue = HashMap<String, Int>()

    fun reset() {
        last.clear()
        warnedAt.clear()
        warnedValue.clear()
    }

    /** The confirmed value: this look and the last one both read, and close to each other. */
    private fun confirmed(id: String, v: Int?): Int? {
        if (v == null) return null
        val prev = last.put(id, v) ?: return null
        if (kotlin.math.abs(prev - v) > maxOf(3, prev / 5)) return null
        return maxOf(prev, v)
    }

    fun check(r: VitalsReading, s: VitalsSettings): List<VitalsWarning> {
        if (!s.enabled) return emptyList()
        val out = ArrayList<VitalsWarning>()
        val now = clock()

        confirmed("hp", r.hp)?.let { hp ->
            val max = s.maxHp ?: 99
            if (hp in 1..(max + 20)) {
                val pct = hp * 100 / max
                if (pct <= s.hpPercent) {
                    val urgent = pct <= s.hpPercent / 2
                    warn("hp", hp, urgent, now, if (urgent) "Eat now! $hp HP" else "Low HP: $hp")?.let { out.add(it) }
                } else clear("hp")
            }
        }
        confirmed("prayer", r.prayer)?.let { p ->
            val max = s.maxPrayer ?: 99
            if (s.prayerPoints > 0 && p <= max + 20) {
                if (p <= s.prayerPoints) {
                    warn("prayer", p, p <= s.prayerPoints / 2, now, "Drink a prayer potion: $p prayer")?.let { out.add(it) }
                } else clear("prayer")
            }
        }
        confirmed("run", r.run)?.let { run ->
            if (s.runPercent > 0 && run <= 100) {
                if (run <= s.runPercent) warn("run", run, false, now, "Run energy low: $run%")?.let { out.add(it) }
                else clear("run")
            }
        }
        return out
    }

    private fun warn(id: String, value: Int, urgent: Boolean, now: Long, text: String): VitalsWarning? {
        val at = warnedAt[id]
        val was = warnedValue[id]
        val gap = if (urgent) urgentRepeatMs else repeatMs
        val worse = was != null && value < was - maxOf(2, was / 10)
        if (at != null && now - at < gap && !worse) return null
        warnedAt[id] = now
        warnedValue[id] = value
        return VitalsWarning(id, text, urgent)
    }

    private fun clear(id: String) {
        warnedAt.remove(id)
        warnedValue.remove(id)
    }
}
