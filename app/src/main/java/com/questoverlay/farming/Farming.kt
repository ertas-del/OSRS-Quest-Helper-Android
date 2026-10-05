package com.questoverlay.farming

import com.questoverlay.capture.Fuzzy

/** Patch types, as RuneLite names them. [label] is how the game's messages say it. */
enum class Patch(val label: String) {
    ALLOTMENT("allotment"), FLOWER("flower patch"), BUSH("bush patch"), HOPS("hops patch"),
    HERB("herb patch"), TREE("tree patch"), FRUIT_TREE("fruit tree patch"), CACTUS("cactus patch"),
    HARDWOOD_TREE("hardwood tree patch"), ANIMA("anima patch"), CORAL("coral patch"),
    SEAWEED("seaweed patch"), GRAPES("vine"), MUSHROOM("mushroom patch"), BELLADONNA("belladonna patch"),
    CALQUAT("calquat patch"), SPIRIT_TREE("spirit tree patch"), CELASTRUS("celastrus patch"),
    REDWOOD("redwood patch"), HESPORI("hespori patch"), CRYSTAL_TREE("crystal tree patch")
}

/**
 * A crop: it grows one stage every [tickMinutes] (on the game's fixed farming ticks) and is
 * ready at stage [stages] − 1.
 */
data class Crop(val name: String, val patch: Patch, val tickMinutes: Int, val stages: Int) {
    /** Magic secateurs add 10% yield to these (not Hespori, trees, fruit trees, seaweed…). */
    val secateursHelp: Boolean
        get() = patch in BOOSTED_PATCHES || name == "Limpwurt"

    /** Longest it can take, if planted just after a tick. */
    val maxMinutes: Int get() = tickMinutes * (stages - 1)

    companion object {
        val BOOSTED_PATCHES = setOf(Patch.HERB, Patch.ALLOTMENT, Patch.BUSH, Patch.HOPS, Patch.CELASTRUS, Patch.GRAPES, Patch.CORAL)
    }
}

data class Planting(val crop: Crop, val count: Int)

object Farming {
    val CROPS: List<Crop> get() = CROP_TABLE

    fun crop(name: String): Crop? = CROPS.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * When a crop planted at [plantedMs] is fully grown. Crops grow on fixed farming ticks every
     * [Crop.tickMinutes] (counted from midnight UTC): the first stage comes at the next tick,
     * then one more each tick. So herbs take 60–80 minutes and Hespori 21⅓–32 hours.
     * [offsetMinutes] shifts the ticks for accounts whose ticks are known to be offset.
     */
    fun readyAt(crop: Crop, plantedMs: Long, offsetMinutes: Int = 0): Long {
        val tick = crop.tickMinutes * 60_000L
        val shifted = plantedMs + offsetMinutes * 60_000L
        val nextTick = (shifted / tick + 1) * tick
        return nextTick + (crop.stages - 2) * tick - offsetMinutes * 60_000L
    }

    private val PLANT = Regex("""\bplant (?:the |an? |\d+ )?(.+?) in (?:the |a |your )?(.+?)$""")
    private val HARVEST = Regex("""\b(?:begin to harvest|harvest|pick)s? (?:the |a |an |some )?(.+?)$""")
    private val NOT_CROP = setOf("seed", "seeds", "sapling", "spore", "spores", "seedling", "cutting", "plant", "the", "a", "an", "some")

    /** "You plant the maple sapling in the tree patch." → Maple. OCR slips like "tou plant" are fine. */
    fun planting(message: String): Planting? {
        val n = Fuzzy.norm(message)
        val m = PLANT.find(n) ?: return null
        val what = m.groupValues[1]
        val where = m.groupValues[2]
        if (!where.contains("patch") && !where.contains("allotment") && !where.contains("vine")) return null
        val count = Regex("""\bplant (\d+)""").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val crop = match(what, where) ?: return null
        return Planting(crop, count)
    }

    /** "You begin to harvest the herb patch." / "You pick a Ranarr weed." → the crop or patch's crop. */
    fun harvesting(message: String): Crop? {
        val n = Fuzzy.norm(message)
        if (!n.startsWith("you ")) return null
        val m = HARVEST.find(n) ?: return null
        val crop = match(m.groupValues[1], m.groupValues[1]) ?: return null
        // "You pick a cabbage" also happens in Lumbridge's field: plain "pick" only counts for herbs.
        if (!n.contains("harvest") && crop.patch != Patch.HERB) return null
        return crop
    }

    /** Finds the crop named in [what], or failing that a crop for the patch named in [where]. */
    private fun match(what: String, where: String): Crop? {
        val words = what.split(' ').filter { it.isNotBlank() && it !in NOT_CROP }
        var best: Crop? = null
        var bestScore = 0.0
        for (c in CROPS) {
            val cw = Fuzzy.words(c.name)
            // Every word of the crop's name must appear (plurals and small slips allowed).
            var total = 0.0
            var ok = true
            for (w in cw) {
                val s = words.maxOfOrNull { stemRatio(it, w) } ?: 0.0
                if (s < 0.8) { ok = false; break }
                total += s
            }
            if (!ok) continue
            val score = total / cw.size + cw.size * 0.01
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        if (best != null) return best
        val patch = Patch.entries.firstOrNull { where.contains(it.label) || (it == Patch.ALLOTMENT && where.contains("allotment")) }
        return patch?.let { p -> CROPS.firstOrNull { it.patch == p } }?.let {
            // Unknown crop in a known patch: use the patch's typical timing.
            if (it.patch == Patch.HERB) it.copy(name = "Herb") else it
        }
    }

    private fun stemRatio(a: String, b: String): Double {
        val x = a.removeSuffix("es").removeSuffix("s")
        val y = b.removeSuffix("es").removeSuffix("s")
        if (x == y) return 1.0
        if (x.length >= 4 && y.length >= 4 && (x.startsWith(y) || y.startsWith(x))) return 0.95
        return Fuzzy.ratio(x, y)
    }
}

/**
 * When to warn "Check your magic secateurs": on planting or harvesting a crop they help, at most
 * once every [cooldownMs] per crop, and never if the player said they always carry them.
 * (Once inventory reading exists it will only fire when they can't be seen.)
 */
class SecateursAdvisor(private val clock: () -> Long = { System.currentTimeMillis() }, private val cooldownMs: Long = 10 * 60_000L) {
    private val lastWarned = HashMap<String, Long>()

    data class Warning(val text: String, val speak: String)

    fun onPlant(crop: Crop, alwaysCarry: Boolean, hasFairytale: Boolean?): Warning? =
        warn(crop, alwaysCarry, hasFairytale, harvest = false)

    fun onHarvest(crop: Crop, alwaysCarry: Boolean, hasFairytale: Boolean?): Warning? =
        warn(crop, alwaysCarry, hasFairytale, harvest = true)

    private fun warn(crop: Crop, alwaysCarry: Boolean, hasFairytale: Boolean?, harvest: Boolean): Warning? {
        if (!crop.secateursHelp || alwaysCarry) return null
        val now = clock()
        val key = crop.patch.name
        val last = lastWarned[key]
        if (last != null && now - last < cooldownMs) return null
        lastWarned[key] = now
        if (hasFairytale == false) {
            return Warning(
                "Magic secateurs give +10% ${crop.name.lowercase()}. Get them from Fairytale I – Growing Pains.",
                "Magic secateurs would boost this crop"
            )
        }
        return if (harvest) Warning("No magic secateurs? They give +10% ${crop.name.lowercase()}.", "No magic secateurs!")
        else Warning("Bring magic secateurs to harvest this (+10% yield).", "Check you have your magic secateurs")
    }
}
