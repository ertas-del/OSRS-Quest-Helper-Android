package com.questoverlay.puzzles

/*
 * Puzzle solvers. Plain Kotlin with no Android code, so they can be tested on a desktop.
 * The rules and tables come from the RuneLite Quest Helper plugin's puzzle code
 * (https://github.com/Zoinkwiz/quest-helper, BSD 2-Clause), reworked to take what the
 * player can see instead of reading the game.
 */

/** Sins of the Father door: a 5x5 Kakurasu. */
object Kakurasu {
    /**
     * rows[r] = total of (column index + 1) over the pressed cells in row r.
     * cols[c] = total of (row index + 1) over the pressed cells in column c.
     * Returns grid[row][col] = pressed, or null when no grid fits.
     */
    fun solve(rows: IntArray, cols: IntArray): Array<BooleanArray>? {
        require(rows.size == 5 && cols.size == 5)
        // Every way to fill one line: a 5-bit mask whose weighted sum matches.
        val options = Array(5) { r -> (0 until 32).filter { lineSum(it) == rows[r] } }
        if (options.any { it.isEmpty() }) return null
        val chosen = IntArray(5)
        val colTotals = IntArray(5)

        fun search(r: Int): Boolean {
            if (r == 5) return (0 until 5).all { colTotals[it] == cols[it] }
            for (mask in options[r]) {
                var ok = true
                for (c in 0 until 5) {
                    if (mask and (1 shl c) != 0) colTotals[c] += r + 1
                    // Prune: a column can't overshoot its total.
                    if (colTotals[c] > cols[c]) ok = false
                }
                if (ok) {
                    // Prune: remaining rows can add at most (r+2)+...+5 to a column.
                    val remaining = ((r + 2)..5).sum()
                    ok = (0 until 5).all { colTotals[it] + remaining >= cols[it] }
                }
                if (ok) {
                    chosen[r] = mask
                    if (search(r + 1)) return true
                }
                for (c in 0 until 5) if (mask and (1 shl c) != 0) colTotals[c] -= r + 1
            }
            return false
        }

        if (!search(0)) return null
        return Array(5) { r -> BooleanArray(5) { c -> chosen[r] and (1 shl c) != 0 } }
    }

    private fun lineSum(mask: Int): Int = (0 until 5).sumOf { if (mask and (1 shl it) != 0) it + 1 else 0 }
}

/** The Curse of Arrav metal door: four letters on the code key become four digits. */
object MetalDoor {
    // Columns: strip 4, strip 2, strip 1, strip 3.
    private val TABLE = mapOf(
        'A' to intArrayOf(7, 9, 6, 4),
        'B' to intArrayOf(5, 3, 1, 0),
        'C' to intArrayOf(2, 8, 6, 3),
        'D' to intArrayOf(0, 6, 4, 7),
        'E' to intArrayOf(4, 3, 6, 4),
        'F' to intArrayOf(2, 2, 1, 9),
        'G' to intArrayOf(2, 3, 2, 6),
        'H' to intArrayOf(4, 3, 8, 1),
        'I' to intArrayOf(9, 3, 0, 9)
    )

    const val LETTERS = "ABCDEFGHI"

    fun code(letters: String): IntArray? {
        val s = letters.uppercase()
        if (s.length != 4 || s.any { it !in TABLE }) return null
        return intArrayOf(
            TABLE.getValue(s[0])[2], // strip 1
            TABLE.getValue(s[1])[1], // strip 2
            TABLE.getValue(s[2])[3], // strip 3
            TABLE.getValue(s[3])[0]  // strip 4
        )
    }
}

/** Lunar Diplomacy chance challenge: six dice that each flip between two opposite faces. */
object Dice {
    val DICE = listOf(
        "Centre-west die (1 or 6)", "Centre-east die (6 or 1)", "North-west die (5 or 2)",
        "South-west die (2 or 5)", "North-east die (4 or 3)", "South-east die (3 or 4)"
    )
    private val TABLE = mapOf(
        12 to intArrayOf(1, 1, 2, 2, 3, 3), 13 to intArrayOf(1, 1, 2, 2, 3, 4),
        14 to intArrayOf(1, 1, 2, 2, 4, 4), 15 to intArrayOf(1, 1, 2, 5, 3, 3),
        16 to intArrayOf(1, 1, 2, 5, 3, 4), 17 to intArrayOf(1, 1, 2, 5, 4, 4),
        18 to intArrayOf(1, 1, 5, 5, 3, 3), 19 to intArrayOf(1, 1, 5, 5, 3, 4),
        20 to intArrayOf(1, 1, 5, 5, 4, 4), 21 to intArrayOf(1, 6, 2, 5, 3, 4),
        22 to intArrayOf(1, 6, 2, 5, 4, 4), 23 to intArrayOf(1, 6, 5, 5, 3, 3),
        24 to intArrayOf(1, 6, 5, 5, 3, 4), 25 to intArrayOf(1, 6, 5, 5, 4, 4),
        26 to intArrayOf(6, 6, 2, 5, 3, 4), 27 to intArrayOf(6, 6, 2, 5, 4, 4),
        28 to intArrayOf(6, 6, 5, 5, 3, 3), 29 to intArrayOf(6, 6, 5, 5, 3, 4),
        30 to intArrayOf(6, 6, 5, 5, 4, 4)
    )
    val TARGETS: List<Int> = TABLE.keys.sorted()

    fun faces(target: Int): IntArray? = TABLE[target]
}

/** Letter wheels and dials: how many clicks, and which way, from the current letter to the target. */
object Wheels {
    data class Move(val clicks: Int, val forward: Boolean)

    /** Shortest way round; on a tie, go forward. Null if a letter isn't on the wheel. */
    fun move(wheel: String, current: Char, target: Char): Move? {
        val n = wheel.length
        val c = wheel.indexOf(current.uppercaseChar())
        val t = wheel.indexOf(target.uppercaseChar())
        if (c < 0 || t < 0) return null
        val fwd = Math.floorMod(t - c, n)
        val back = Math.floorMod(c - t, n)
        return if (back < fwd) Move(back, false) else Move(fwd, true)
    }
}

/** Dragon Slayer II crypt: three clues from the plaque fix all four busts. */
object Crypt {
    val BUSTS = listOf("Aivas", "Camorra", "Robert", "Tristan")
    /** "<name> sat at the north of the table" */
    val NORTH_NAMES = listOf("Zartharim" to "Aivas", "Saranthium" to "Camorra", "Arkney" to "Robert", "Karville" to "Tristan")
    /** "opposite the one with the <weapon>" (south) and "The one with a <weapon> asked the" (west) */
    val WEAPONS = listOf("crossbow" to "Aivas", "axe" to "Camorra", "bow" to "Robert", "sword" to "Tristan")

    /** Returns busts for north, east, south, west, or null if two clues name the same bust. */
    fun solve(northBust: String, southBust: String, westBust: String): List<String>? {
        val used = listOf(northBust, southBust, westBust)
        if (used.toSet().size != 3) return null
        val east = BUSTS.firstOrNull { it !in used } ?: return null
        return listOf(northBust, east, southBust, westBust)
    }
}

/** Beneath Cursed Sands tomb riddle: four sentences fix the emblem for each urn. */
object Tomb {
    val GODS = listOf(
        "god of isolation" to "Scarab", "god of health" to "Human",
        "goddess of resourcefulness" to "Crocodile", "goddess of companionship" to "Baboon"
    )
    val OFFERINGS = listOf(
        "a carving" to "Scarab", "some wine" to "Human", "a necklace" to "Crocodile", "some linen" to "Baboon"
    )
}

/** The Forsaken Tower refinery: where "Cleansing fluid" appears in the notes decides the bottle. */
object Fluid {
    val CHOICES = listOf(
        "\"Cleansing fluid is directly left of …\"" to 1,
        "\"Cleansing fluid is next to …\"" to 2,
        "\"… is next to Cleansing fluid.\"" to 3,
        "\"… is directly right of Cleansing fluid.\"" to 4,
        "\"Cleansing fluid is directly right of …\"" to 5
    )
}

/** Song of the Elves, Baxtorian's pillars: five hints place five items, the sixth goes south-east. */
object Baxtorian {
    data class Pillar(val name: String, val hint: String?)

    val PILLARS = listOf(
        Pillar("South-west", "I am the …"),
        Pillar("West", "I am next to the …"),
        Pillar("North-west", "I am opposite the …"),
        Pillar("East", "I am not next to the …"),
        Pillar("North-east", "I am not the …"),
        Pillar("South-east", null)
    )
    val ITEMS = listOf(
        "Nature rune",
        "Irit leaf (or any flowers)",
        "Black knife or black dagger",
        "Wine of Zamorak or Zamorak brew",
        "Adamant chainbody",
        "Cabbage"
    )
    val ORDINALS = listOf("1st", "2nd", "3rd", "4th", "5th", "6th")

    /**
     * hints[i] = ordinal (0..5) read on pillar i (the first five pillars), or -1 if unknown.
     * Returns the item index for each of the six pillars, -1 where still unknown.
     */
    fun solve(hints: IntArray): IntArray {
        val out = IntArray(6) { -1 }
        for (i in 0 until 5) out[i] = hints[i]
        val known = hints.filter { it >= 0 }
        if (known.size == 5 && known.toSet().size == 5) out[5] = (0 until 6).first { it !in known }
        return out
    }
}

/** Glouphrie disc machines: every disc is worth colour × shape. */
object Discs {
    val COLOURS = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet")
    val SHAPES = listOf("circle" to 1, "triangle" to 3, "square" to 4, "pentagon" to 5)

    fun value(colour: Int, shape: Int): Int = (colour + 1) * SHAPES[shape].second

    /** Every disc worth exactly v, e.g. 12 -> ["Yellow square", "Green triangle"]. */
    fun discsWorth(v: Int): List<String> {
        val out = ArrayList<String>()
        for (c in COLOURS.indices) for (s in SHAPES.indices) {
            if (value(c, s) == v) out.add("${COLOURS[c]} ${SHAPES[s].first}")
        }
        return out
    }

    /** All the values a disc can have. */
    val VALUES: List<Int> = COLOURS.indices.flatMap { c -> SHAPES.indices.map { s -> value(c, s) } }.distinct().sorted()

    /** Ways to make [target] from exactly [count] discs (values ascending, no repeats of the same set). */
    fun combos(target: Int, count: Int, limit: Int = 8): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        fun go(start: Int, left: Int, sum: Int, acc: MutableList<Int>) {
            if (out.size >= limit) return
            if (left == 0) {
                if (sum == target) out.add(acc.toList())
                return
            }
            for (i in start until VALUES.size) {
                val v = VALUES[i]
                if (sum + v * left > target) break
                acc.add(v)
                go(i, left - 1, sum + v, acc)
                acc.removeAt(acc.size - 1)
            }
        }
        go(0, count, 0, ArrayList())
        return out
    }
}

/**
 * Icthlarin's Little Helper door tiles. Clicking a tile flips it and its left and right
 * neighbours in the same row. Given which tiles in a row are wrong, which to click.
 */
object RowToggle {
    /** Quest Helper's own answers, keyed by "wrong" pattern (1 = not the goal look), left to right. */
    private val PLUGIN = mapOf(
        "11011" to "10001", "01110" to "00100", "10001" to "10110", "00100" to "00011",
        "10101" to "10101", "00111" to "00010", "11100" to "01000", "00011" to "00001",
        "11000" to "10000", "10010" to "01100", "01001" to "00110", "01101" to "00101",
        "10110" to "10100", "11111" to "01001", "00000" to "00000"
    )

    /** wrong[i] = tile i (0..4, left to right) isn't the goal look. Returns tiles to click, or null. */
    fun solve(wrong: BooleanArray): BooleanArray? {
        if (wrong.size == 5) {
            val key = wrong.joinToString("") { if (it) "1" else "0" }
            PLUGIN[key]?.let { s -> return BooleanArray(5) { s[it] == '1' } }
        }
        val n = wrong.size
        var target = 0
        for (i in 0 until n) if (wrong[i]) target = target or (1 shl i)
        var best: Int? = null
        for (clicks in 0 until (1 shl n)) {
            var flipped = 0
            for (i in 0 until n) if (clicks and (1 shl i) != 0) {
                flipped = flipped xor (1 shl i)
                if (i > 0) flipped = flipped xor (1 shl (i - 1))
                if (i < n - 1) flipped = flipped xor (1 shl (i + 1))
            }
            if (flipped == target && (best == null || Integer.bitCount(clicks) < Integer.bitCount(best))) best = clicks
        }
        val b = best ?: return null
        return BooleanArray(n) { b and (1 shl it) != 0 }
    }
}

/** A Kingdom Divided, Forthos ruins: the code is the stone numbers in the order R, O, S, E. */
object StoneCode {
    val STONES = listOf("E" to "north-east", "R" to "south-east", "S" to "south-west", "O" to "north-west")

    /** digits in STONES order (E, R, S, O). */
    fun code(e: Int, r: Int, s: Int, o: Int): String = "$r$o$s$e"
}
