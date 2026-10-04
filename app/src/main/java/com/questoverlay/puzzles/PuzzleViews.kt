package com.questoverlay.puzzles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.questoverlay.BevelDrawable
import com.questoverlay.Ui

/** Inputs the player has entered, kept outside the views so they survive redraws. */
class PuzzleState {
    private val ints = HashMap<String, IntArray>()
    private val seqs = HashMap<String, MutableList<Int>>()

    fun ints(key: String, size: Int, initial: Int = 0): IntArray =
        ints.getOrPut(key) { IntArray(size) { initial } }.let { if (it.size == size) it else IntArray(size) { initial }.also { n -> ints[key] = n } }

    fun seq(key: String): MutableList<Int> = seqs.getOrPut(key) { ArrayList() }

    fun clear(prefix: String) {
        ints.keys.filter { it.startsWith(prefix) }.forEach { ints.remove(it) }
        seqs.keys.filter { it.startsWith(prefix) }.forEach { seqs.remove(it) }
    }
}

/**
 * Builds the inside of the Puzzle tab. Every input is a tap (the overlay never takes the
 * keyboard, so the game keeps its focus). [redraw] rebuilds the card after a change.
 */
class PuzzleViews(private val ctx: Context, private val state: PuzzleState, private val redraw: () -> Unit) {

    fun build(p: Puzzle): View {
        val col = vertical()
        if (p.intro.isNotBlank()) col.addView(body(p.intro))
        when (p.kind) {
            "solver" -> col.addView(solver(p))
            else -> col.addView(guide(p))
        }
        if (p.notes.isNotEmpty()) {
            col.addView(label("Notes"))
            for (n in p.notes) col.addView(hint("• $n"))
        }
        return col
    }

    // ------------------------------------------------------------------ guides

    private fun guide(p: Puzzle): View {
        val col = vertical()
        if (p.grid.isNotEmpty()) {
            val g = GridPreview(ctx, p.grid.map { row -> BooleanArray(row.length) { row[it] == 'X' } })
            col.addView(g, LinearLayout.LayoutParams(Ui.dp(ctx, 160), Ui.dp(ctx, 160)).apply {
                topMargin = Ui.dp(ctx, 6)
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        val ticks = state.ints("${p.id}#ticks", p.steps.size)
        p.steps.forEachIndexed { i, s ->
            val done = ticks[i] == 1
            val t = Ui.text(ctx, "${i + 1}. $s", 13f, if (done) Ui.MUTED else Ui.TEXT)
            if (done) t.paintFlags = t.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            t.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4))
            t.setOnClickListener {
                ticks[i] = 1 - ticks[i]
                redraw()
            }
            col.addView(t)
        }
        col.addView(hint("Tap a line to tick it off."))
        return col
    }

    // ------------------------------------------------------------------ solvers

    private fun solver(p: Puzzle): View = when (p.solver) {
        "kakurasu" -> kakurasu(p)
        "metaldoor" -> metalDoor(p)
        "dice" -> dice(p)
        "letterlock" -> letterLock(p)
        "wordarrows" -> wordArrows(p)
        "crypt" -> crypt(p)
        "tomb" -> tomb(p)
        "fluid" -> fluid(p)
        "stonecode" -> stoneCode(p)
        "sequence" -> sequence(p)
        "baxtorian" -> baxtorian(p)
        "discs" -> discs(p)
        "rowtoggle" -> rowToggle(p)
        else -> body("This puzzle helper needs a newer version of the app.")
    }

    private fun kakurasu(p: Puzzle): View {
        val col = vertical()
        val rows = state.ints("${p.id}#rows", 5)
        val cols = state.ints("${p.id}#cols", 5)
        col.addView(label("Numbers beside the rows (top to bottom)"))
        col.addView(stepperRow(rows, 0, 15) { "R${it + 1}" })
        col.addView(label("Numbers under the columns (left to right)"))
        col.addView(stepperRow(cols, 0, 15) { "C${it + 1}" })

        if (rows.all { it == 0 } && cols.all { it == 0 }) {
            col.addView(hint("Enter the ten numbers to see which switches to press."))
            return col
        }
        val grid = Kakurasu.solve(rows, cols)
        col.addView(label("Press these switches"))
        if (grid == null) {
            col.addView(bad("No grid matches those numbers. Double-check them."))
        } else {
            col.addView(GridPreview(ctx, grid.toList()), LinearLayout.LayoutParams(Ui.dp(ctx, 150), Ui.dp(ctx, 150)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(ctx, 4)
            })
        }
        col.addView(resetButton(p))
        return col
    }

    private fun metalDoor(p: Puzzle): View {
        val col = vertical()
        val letters = state.ints("${p.id}#letters", 4)
        col.addView(label("Letters on the code key"))
        col.addView(cyclerRow(letters, MetalDoor.LETTERS.map { it.toString() }))
        val code = MetalDoor.code(letters.joinToString("") { MetalDoor.LETTERS[it].toString() })
        col.addView(label("Door code"))
        col.addView(big(code?.joinToString("  ") ?: "?"))
        return col
    }

    private fun dice(p: Puzzle): View {
        val col = vertical()
        val t = state.ints("${p.id}#target", 1, Dice.TARGETS.first())
        col.addView(label("Number the Fluke asks for"))
        col.addView(stepper("Target", t, 0, Dice.TARGETS.first(), Dice.TARGETS.last()) { it.toString() })
        val faces = Dice.faces(t[0])
        col.addView(label("Show these faces"))
        if (faces == null) col.addView(bad("Pick a number from ${Dice.TARGETS.first()} to ${Dice.TARGETS.last()}."))
        else Dice.DICE.forEachIndexed { i, name -> col.addView(result("$name: ${faces[i]}")) }
        return col
    }

    private fun letterLock(p: Puzzle): View {
        val col = vertical()
        val cfg = p.config
        val forward = cfg.optString("forward", "right arrow")
        val backward = cfg.optString("backward", "left arrow")
        val dialsJson = cfg.optJSONArray("dials")
        val answersJson = cfg.optJSONArray("answers")
        val answers = (0 until (answersJson?.length() ?: 0)).map {
            val o = answersJson!!.getJSONObject(it)
            o.optString("label") to o.optString("word").uppercase()
        }

        val wheels: List<String> = if (dialsJson != null) {
            (0 until dialsJson.length()).map { dialsJson.getString(it).uppercase() }
        } else {
            val alpha = cfg.optString("alphabet", "ABCDEFGHIJKLMNOPQRSTUVWXYZ").uppercase()
            val len = answers.firstOrNull()?.second?.length ?: 4
            List(len) { alpha }
        }

        // The target: one of the known answers, or (for dials) a letter picked per wheel.
        val target: String = if (answers.isNotEmpty()) {
            val pick = state.ints("${p.id}#answer", 1, if (answers.size == 1) 0 else -1)
            if (answers.size > 1) {
                col.addView(label("Which one?"))
                col.addView(choiceList(answers.map { "${it.first}  →  ${it.second}" }, pick))
            }
            if (pick[0] < 0) {
                col.addView(hint("Pick the answer above."))
                return col
            }
            answers[pick[0]].second
        } else {
            val t = state.ints("${p.id}#target", wheels.size)
            col.addView(label("Letters from the book, in order"))
            col.addView(cyclerRowPerWheel(t, wheels))
            wheels.indices.joinToString("") { wheels[it][t[it]].toString() }
        }

        col.addView(label("Code: $target"))
        col.addView(label("What the lock shows now"))
        val cur = state.ints("${p.id}#current", wheels.size)
        col.addView(cyclerRowPerWheel(cur, wheels))
        wheels.forEachIndexed { i, w ->
            val m = Wheels.move(w, w[cur[i]], target.getOrElse(i) { w[0] })
            val text = when {
                m == null -> "Wheel ${i + 1}: letter not on this wheel"
                m.clicks == 0 -> "Wheel ${i + 1}: already ${target[i]} ✓"
                else -> "Wheel ${i + 1}: ${m.clicks} × ${if (m.forward) forward else backward} → ${target[i]}"
            }
            col.addView(result(text))
        }
        col.addView(hint("Then press the confirm / enter button."))
        return col
    }

    private fun wordArrows(p: Puzzle): View {
        val col = vertical()
        val wordsObj = p.config.optJSONObject("words")
        val words = wordsObj?.keys()?.asSequence()?.toList() ?: emptyList()
        val count = p.config.optInt("count", 4)
        val picks = state.ints("${p.id}#words", count)
        col.addView(label("Highlighted words, in order"))
        col.addView(cyclerRow(picks, words))
        col.addView(label("Arrow code"))
        col.addView(big(picks.joinToString(", ") { wordsObj?.optString(words.getOrElse(it) { "" }) ?: "?" }))
        return col
    }

    private fun crypt(p: Puzzle): View {
        val col = vertical()
        val north = state.ints("${p.id}#n", 1, -1)
        val south = state.ints("${p.id}#s", 1, -1)
        val west = state.ints("${p.id}#w", 1, -1)
        col.addView(label("\"___ sat at the north of the table\""))
        col.addView(choiceList(Crypt.NORTH_NAMES.map { it.first }, north))
        col.addView(label("\"…opposite the one with the ___\""))
        col.addView(choiceList(Crypt.WEAPONS.map { it.first }, south))
        col.addView(label("\"The one with a ___ asked the…\""))
        col.addView(choiceList(Crypt.WEAPONS.map { it.first }, west))
        if (north[0] < 0 || south[0] < 0 || west[0] < 0) {
            col.addView(hint("Answer all three to see where the busts go."))
            return col
        }
        val r = Crypt.solve(Crypt.NORTH_NAMES[north[0]].second, Crypt.WEAPONS[south[0]].second, Crypt.WEAPONS[west[0]].second)
        col.addView(label("Busts"))
        if (r == null) col.addView(bad("Two answers point at the same bust. Re-read the plaque."))
        else listOf("North", "East", "South", "West").forEachIndexed { i, d -> col.addView(result("$d plinth: ${r[i]}")) }
        return col
    }

    private fun tomb(p: Puzzle): View {
        val col = vertical()
        val a = state.ints("${p.id}#a", 1, -1)
        val b = state.ints("${p.id}#b", 1, -1)
        val c = state.ints("${p.id}#c", 1, -1)
        val d = state.ints("${p.id}#d", 1, -1)
        col.addView(label("\"The ___ arrived just before…\""))
        col.addView(choiceList(Tomb.GODS.map { it.first }, a))
        col.addView(label("\"…arrived just before the ___\""))
        col.addView(choiceList(Tomb.GODS.map { it.first }, b))
        col.addView(label("\"To the one that arrived first, he offered ___\""))
        col.addView(choiceList(Tomb.OFFERINGS.map { it.first }, c))
        col.addView(label("\"The one that was offered ___ …\""))
        col.addView(choiceList(Tomb.OFFERINGS.map { it.first }, d))
        col.addView(label("Emblems, north to south"))
        fun e(i: Int, list: List<Pair<String, String>>) = if (i < 0) "?" else list[i].second
        col.addView(result("Northernmost urn: ${e(a[0], Tomb.GODS)}"))
        col.addView(result("Centre-north urn: ${e(b[0], Tomb.GODS)}"))
        col.addView(result("Centre-south urn: ${e(c[0], Tomb.OFFERINGS)}"))
        col.addView(result("Southernmost urn: ${e(d[0], Tomb.OFFERINGS)}"))
        return col
    }

    private fun fluid(p: Puzzle): View {
        val col = vertical()
        val pick = state.ints("${p.id}#pick", 1, -1)
        col.addView(label("The sentence with Cleansing fluid"))
        col.addView(choiceList(Fluid.CHOICES.map { it.first }, pick))
        if (pick[0] >= 0) {
            val n = Fluid.CHOICES[pick[0]].second
            col.addView(big("Fluid $n"))
            col.addView(hint("Option $n in the table's take menu."))
        }
        return col
    }

    private fun stoneCode(p: Puzzle): View {
        val col = vertical()
        val d = state.ints("${p.id}#digits", 4)
        col.addView(label("Number from each stone pile"))
        StoneCode.STONES.forEachIndexed { i, (letter, where) ->
            col.addView(stepper("$letter ($where)", d, i, 0, 9) { it.toString() })
        }
        col.addView(label("Panel code (R, O, S, E)"))
        col.addView(big(StoneCode.code(d[0], d[1], d[2], d[3]).toList().joinToString("  ")))
        return col
    }

    private fun sequence(p: Puzzle): View {
        val col = vertical()
        val itemsJson = p.config.optJSONArray("items")
        val items = (0 until (itemsJson?.length() ?: 0)).map {
            val o = itemsJson!!.getJSONObject(it)
            o.optString("name") to o.optString("detail")
        }
        val count = p.config.optInt("count", items.size)
        val repeats = p.config.optBoolean("repeats", false)
        val action = p.config.optString("action", "{detail}")
        val seq = state.seq("${p.id}#seq")

        col.addView(label("Tap in order (${seq.size}/$count)"))
        val grid = vertical()
        var row: LinearLayout? = null
        items.forEachIndexed { i, (name, _) ->
            if (i % 2 == 0) {
                row = horizontal()
                grid.addView(row)
            }
            val used = !repeats && i in seq
            val b = Ui.chip(ctx, name, used)
            b.setOnClickListener {
                if (seq.size < count && (repeats || i !in seq)) {
                    seq.add(i)
                    redraw()
                }
            }
            row!!.addView(b, weighted(gapLeft = i % 2 == 1))
        }
        col.addView(grid)
        if (seq.isNotEmpty()) {
            col.addView(label("Your order"))
            seq.forEachIndexed { n, i ->
                val (name, detail) = items[i]
                col.addView(result("${n + 1}. " + action.replace("{detail}", detail).replace("{name}", name)))
            }
            val undo = horizontal()
            undo.addView(Ui.button(ctx, "Undo", false) {
                if (seq.isNotEmpty()) seq.removeAt(seq.size - 1)
                redraw()
            }, weighted())
            undo.addView(Ui.button(ctx, "Clear", false) {
                seq.clear()
                redraw()
            }, weighted(gapLeft = true))
            col.addView(undo, spaced(8))
        }
        return col
    }

    private fun baxtorian(p: Puzzle): View {
        val col = vertical()
        val hints = state.ints("${p.id}#hints", 5, -1)
        col.addView(label("Each hint ends in a number"))
        val opts = listOf("?") + Baxtorian.ORDINALS
        for (i in 0 until 5) {
            val pillar = Baxtorian.PILLARS[i]
            val cell = IntArray(1) { hints[i] + 1 }
            col.addView(cycler("\"${pillar.hint}\"", opts, cell) { hints[i] = it - 1 })
        }
        val r = Baxtorian.solve(hints)
        col.addView(label("Place on each pillar"))
        Baxtorian.PILLARS.forEachIndexed { i, pillar ->
            val item = r[i]
            col.addView(result("${pillar.name}: ${if (item >= 0) Baxtorian.ITEMS[item] else "?"}"))
        }
        if (hints.filter { it >= 0 }.let { it.size != it.toSet().size }) {
            col.addView(bad("Two pillars have the same number. Check the hints again."))
        }
        return col
    }

    private fun discs(p: Puzzle): View {
        val col = vertical()
        val yewnock = p.config.optString("mode") == "yewnock"
        val colourNames = Discs.COLOURS
        val shapeNames = Discs.SHAPES.map { it.first }

        fun discPicker(key: String, title: String): Int {
            val cs = state.ints(key, 2)
            col.addView(label(title))
            val row = horizontal()
            row.addView(cyclerView(colourNames, cs, 0), weighted())
            row.addView(cyclerView(shapeNames, cs, 1), weighted(gapLeft = true))
            col.addView(row)
            val v = Discs.value(cs[0], cs[1])
            col.addView(hint("Worth $v"))
            return v
        }

        fun showCombos(target: Int, count: Int) {
            val combos = Discs.combos(target, count)
            if (combos.isEmpty()) {
                col.addView(bad("No ${if (count == 1) "disc is" else "$count discs are"} worth $target."))
                return
            }
            for (c in combos) {
                val names = c.joinToString("  +  ") { v -> Discs.discsWorth(v).joinToString(" or ") }
                col.addView(result(if (count == 1) names else "${c.joinToString(" + ")}: $names"))
            }
        }

        if (yewnock) {
            val stage = state.ints("${p.id}#stage", 1)
            val tabs = horizontal()
            listOf("Puzzle 1", "Puzzle 2").forEachIndexed { i, s ->
                val t = Ui.chip(ctx, s, stage[0] == i)
                t.setOnClickListener {
                    stage[0] = i
                    redraw()
                }
                tabs.addView(t, weighted(gapLeft = i > 0))
            }
            col.addView(tabs)
            if (stage[0] == 0) {
                val a = discPicker("${p.id}#p1a", "Left target disc")
                val b = discPicker("${p.id}#p1b", "Right target disc")
                col.addView(label("Insert one disc worth ${a + b}"))
                showCombos(a + b, 1)
            } else {
                val t = discPicker("${p.id}#p2", "Target disc")
                col.addView(label("Insert two discs adding up to $t"))
                showCombos(t, 2)
            }
        } else {
            val target = state.ints("${p.id}#target", 1, 1)
            val count = state.ints("${p.id}#count", 1, 1)
            col.addView(label("Target value"))
            col.addView(stepper("Value", target, 0, 1, 105, step = 1) { it.toString() })
            col.addView(stepper("Jump by 10", target, 0, 1, 105, step = 10) { it.toString() })
            col.addView(label("How many discs does this slot take?"))
            col.addView(choiceList(listOf("1 disc", "2 discs", "3 discs"), IntArray(1).also { it[0] = count[0] - 1 }) { count[0] = it + 1 })
            col.addView(label("Discs that work"))
            showCombos(target[0], count[0])
        }
        return col
    }

    private fun rowToggle(p: Puzzle): View {
        val col = vertical()
        val wrong = state.ints("${p.id}#wrong", 5)
        col.addView(label("Tap the tiles in this row that are wrong"))
        val row = horizontal()
        for (i in 0 until 5) {
            val on = wrong[i] == 1
            val cell = Ui.chip(ctx, if (on) "✖" else " ", on, 14f)
            cell.setOnClickListener {
                wrong[i] = 1 - wrong[i]
                redraw()
            }
            row.addView(cell, weighted(gapLeft = i > 0))
        }
        col.addView(row)
        val clicks = RowToggle.solve(BooleanArray(5) { wrong[it] == 1 })
        col.addView(label("Click these"))
        if (clicks == null) {
            col.addView(bad("This row can't be fixed on its own. You've probably picked the wrong look as the goal: tap every tile to swap."))
        } else if (clicks.none { it }) {
            col.addView(result("Nothing: this row is done ✓"))
        } else {
            val out = horizontal()
            for (i in 0 until 5) {
                val t = Ui.chip(ctx, if (clicks[i]) "●" else " ", clicks[i], 14f)
                t.setTextColor(if (clicks[i]) Ui.GREEN else Ui.MUTED)
                out.addView(t, weighted(gapLeft = i > 0))
            }
            col.addView(out)
        }
        col.addView(hint("Do one row at a time from the top. After clicking, enter the next row as it looks now."))
        col.addView(Ui.button(ctx, "Next row", false) {
            wrong.fill(0)
            redraw()
        }, spaced(8))
        return col
    }

    // ------------------------------------------------------------------ small building blocks

    private fun vertical() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weighted(gapLeft: Boolean = false) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (gapLeft) leftMargin = Ui.dp(ctx, 4)
        }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = Ui.dp(ctx, top)
    }

    private fun body(s: String) = Ui.text(ctx, s, 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 4)) }
    private fun hint(s: String) = Ui.text(ctx, s, 11f, Ui.MUTED).apply { setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3)) }
    private fun bad(s: String) = Ui.text(ctx, s, 12f, Ui.RED).apply { setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3)) }
    private fun result(s: String) = Ui.text(ctx, s, 13f, Ui.TAN, bold = true).apply { setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3)) }
    private fun big(s: String) = Ui.text(ctx, s, 24f, Ui.TAN, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
    }

    private fun label(s: String) = Ui.text(ctx, s, 11f, Ui.GOLD, bold = true).apply {
        setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4))
    }

    private fun smallButton(s: String, onClick: () -> Unit): TextView {
        val t = Ui.text(ctx, s, 16f, Ui.GOLD, bold = true)
        t.gravity = Gravity.CENTER
        t.background = Ui.stoneButton(ctx)
        t.setOnClickListener { onClick() }
        return t
    }

    /** "Label  [-] value [+]" editing values[index]. */
    private fun stepper(name: String, values: IntArray, index: Int, min: Int, max: Int, step: Int = 1, fmt: (Int) -> String): View {
        val row = horizontal()
        row.setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2))
        row.addView(Ui.text(ctx, name, 12f, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val size = Ui.dp(ctx, 30)
        row.addView(smallButton("−") {
            values[index] = (values[index] - step).coerceAtLeast(min)
            redraw()
        }, LinearLayout.LayoutParams(size, size))
        val v = Ui.text(ctx, fmt(values[index]), 15f, Ui.TAN, bold = true)
        v.gravity = Gravity.CENTER
        row.addView(v, LinearLayout.LayoutParams(Ui.dp(ctx, 40), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(smallButton("+") {
            values[index] = (values[index] + step).coerceAtMost(max)
            redraw()
        }, LinearLayout.LayoutParams(size, size))
        return row
    }

    /** Five compact steppers in a row (for the door grid's clues). */
    private fun stepperRow(values: IntArray, min: Int, max: Int, name: (Int) -> String): View {
        val row = horizontal()
        for (i in values.indices) {
            val cell = vertical()
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.addView(Ui.text(ctx, name(i), 10f, Ui.MUTED).apply { gravity = Gravity.CENTER })
            cell.addView(smallButton("+") {
                values[i] = (values[i] + 1).coerceAtMost(max)
                redraw()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)))
            cell.addView(Ui.text(ctx, values[i].toString(), 15f, Ui.TAN, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2))
            })
            cell.addView(smallButton("−") {
                values[i] = (values[i] - 1).coerceAtLeast(min)
                redraw()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)))
            row.addView(cell, weighted(gapLeft = i > 0))
        }
        return row
    }

    /** "‹ value ›" cycling through options, editing values[index]. */
    private fun cyclerView(options: List<String>, values: IntArray, index: Int, onSet: ((Int) -> Unit)? = null): View {
        if (options.isEmpty()) return body("Missing puzzle data.")
        val row = horizontal()
        val n = options.size.coerceAtLeast(1)
        fun set(v: Int) {
            values[index] = Math.floorMod(v, n)
            onSet?.invoke(values[index])
            redraw()
        }
        val size = Ui.dp(ctx, 28)
        row.addView(smallButton("‹") { set(values[index] - 1) }, LinearLayout.LayoutParams(size, size))
        val v = Ui.text(ctx, options.getOrElse(values[index]) { "?" }, 14f, Ui.TAN, bold = true)
        v.gravity = Gravity.CENTER
        v.maxLines = 1
        row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(smallButton("›") { set(values[index] + 1) }, LinearLayout.LayoutParams(size, size))
        return row
    }

    private fun cycler(name: String, options: List<String>, values: IntArray, onSet: (Int) -> Unit): View {
        val col = vertical()
        col.addView(Ui.text(ctx, name, 12f, Ui.TEXT).apply { setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 2)) })
        col.addView(cyclerView(options, values, 0, onSet))
        return col
    }

    /** One cycler per slot, all over the same options (e.g. four code-key letters). */
    private fun cyclerRow(values: IntArray, options: List<String>): View {
        if (options.isEmpty()) return body("Missing puzzle data.")
        val row = horizontal()
        for (i in values.indices) {
            val cell = vertical()
            cell.addView(smallButton("▲") {
                values[i] = Math.floorMod(values[i] + 1, options.size)
                redraw()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)))
            cell.addView(Ui.text(ctx, options.getOrElse(values[i]) { "?" }, if ((options.maxOfOrNull { it.length } ?: 0) > 2) 12f else 18f, Ui.TAN, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3))
            })
            cell.addView(smallButton("▼") {
                values[i] = Math.floorMod(values[i] - 1, options.size)
                redraw()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)))
            row.addView(cell, weighted(gapLeft = i > 0))
        }
        return row
    }

    /** Like [cyclerRow] but every slot has its own letters (e.g. the dials of a chest). */
    private fun cyclerRowPerWheel(values: IntArray, wheels: List<String>): View {
        val row = horizontal()
        for (i in values.indices) {
            val opts = wheels[i].map { it.toString() }
            val cell = vertical()
            cell.addView(smallButton("▲") {
                values[i] = Math.floorMod(values[i] + 1, opts.size)
                redraw()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)))
            cell.addView(Ui.text(ctx, opts.getOrElse(values[i]) { "?" }, 18f, Ui.TAN, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3))
            })
            cell.addView(smallButton("▼") {
                values[i] = Math.floorMod(values[i] - 1, opts.size)
                redraw()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)))
            row.addView(cell, weighted(gapLeft = i > 0))
        }
        return row
    }

    /** Vertical single-choice list; selected[0] holds the chosen index (-1 = none). */
    private fun choiceList(options: List<String>, selected: IntArray, onSet: ((Int) -> Unit)? = null): View {
        val col = vertical()
        options.forEachIndexed { i, s ->
            val active = selected[0] == i
            val t = Ui.chip(ctx, s, active)
            t.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            t.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 7), Ui.dp(ctx, 10), Ui.dp(ctx, 7))
            t.setOnClickListener {
                selected[0] = i
                onSet?.invoke(i)
                redraw()
            }
            col.addView(t, spaced(if (i == 0) 0 else 3))
        }
        return col
    }

    private fun resetButton(p: Puzzle): View =
        Ui.button(ctx, "Clear", false) {
            state.clear(p.id)
            redraw()
        }.apply { layoutParams = spaced(8) }
}

/** A small grid of stone squares; filled squares are lit yellow. Used for door grids and diagrams. */
class GridPreview(context: Context, private val cells: List<BooleanArray>) : View(context) {
    private val paint = Paint().apply { style = Paint.Style.FILL }
    private val off = BevelDrawable(context, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
    private val on = BevelDrawable(context, Ui.TAN, 0xFFFFFF99.toInt(), 0xFF9C8A00.toInt(), Ui.STROKE, raised = true)

    override fun onDraw(canvas: Canvas) {
        val rows = cells.size
        val cols = cells.maxOfOrNull { it.size } ?: 0
        if (rows == 0 || cols == 0) return
        val cw = width / cols
        val ch = height / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            val d = if (cells[r].getOrElse(c) { false }) on else off
            d.setBounds(c * cw, r * ch, (c + 1) * cw, (r + 1) * ch)
            d.draw(canvas)
        }
        paint.color = Ui.STROKE
    }
}
