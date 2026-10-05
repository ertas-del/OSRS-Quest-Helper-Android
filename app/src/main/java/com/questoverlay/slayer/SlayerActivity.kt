package com.questoverlay.slayer

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import com.questoverlay.OverlayService
import com.questoverlay.ScreenActivity
import com.questoverlay.Ui
import com.questoverlay.account.CombatLevel
import com.questoverlay.travel.QuestNames
import com.questoverlay.travel.TravelStore

/**
 * Slayer: your master and points, the task you're on, and what your master can give you (with how likely
 * each is), each with a card for where to go, what to wear and bring, and how much food.
 */
class SlayerActivity : ScreenActivity() {
    private var openId: String? = null
    private var showLocked = false

    override fun build() {
        val data = try { SlayerData.load(this) } catch (e: Exception) {
            title("Slayer", "Couldn't load the Slayer data.")
            return
        }
        val store = SlayerStore(this)
        val travel = TravelStore(this)
        val levels = travel.levels
        val slayerLevel = levels["Slayer"]
        val combat = CombatLevel.of(levels)
        title(
            "Slayer",
            "Pick your master, and Breadcrumbs reads new tasks from the game's chat (👁 on). Each task gets a card: where to go, " +
                "what to wear and bring, and how much food."
        )

        val master = data.master(store.master) ?: data.masters.first()
        val rows = data.tables[master.id].orEmpty()
        val done = store.count(master.id)

        // Current task.
        store.task?.let { t ->
            val c = card("Your task")
            val row = data.matchRow(t.master, t.name)
            val card = data.matchCard(t.name)
            val done0 = t.remaining <= 0
            c.addView(Ui.text(this, if (done0) "${t.name} — done" else "${t.remaining} ${t.name}", 18f, if (done0) Ui.GREEN else Ui.GOLD, bold = true))
            note(c, listOfNotNull(
                if (!done0 && t.total > t.remaining) "of ${t.total}" else null,
                t.place?.let { "in $it" },
                "with ${data.master(t.master)?.name ?: t.master}"
            ).joinToString(" · "), Ui.TAN, 12f)
            if (!done0) note(c, "The count updates when you check your gem or helm. Kills aren't announced in chat.")
            c.addView(spaced(SlayerViews.monsterCard(this, t.name, card, row, t.place), 8))
            c.addView(spaced(Ui.button(this, "Clear task", false) {
                store.task = null
                refreshOverlay()
                rebuild()
            }, 8))
        }

        // Master and points.
        val mc = card("Master")
        for (m in data.masters.filter { it.id in data.tables.keys || it.points > 0 }) {
            val ok = (slayerLevel == null || slayerLevel >= m.slayer) && (combat == null || combat >= m.combat || (m.id == "mortimer" && (slayerLevel ?: 0) >= 99))
            val sub = buildString {
                append(m.where)
                if (m.points > 0) append(" · ${m.points}${if (m.diaryPoints > m.points) "/${m.diaryPoints}" else ""} points a task")
                if (!ok) append(" · not open yet")
            }
            val using = m.id == master.id
            rowWithButton(mc, m.name, sub, if (using) "Using ✓" else "Use", if (using) Ui.GREEN else Ui.TEXT) {
                if (!using) {
                    store.master = m.id
                    openId = null
                    rebuild()
                }
            }
        }
        if (master.note.isNotBlank()) note(mc, master.note)

        val pc = card("Points · ${master.name}")
        note(pc, "Tasks done with ${master.name}: $done" + if (store.points >= 0) " · you have ${store.points} points" else "", Ui.TEXT, 13f)
        val base = if (master.diaryPoints > master.points) master.diaryPoints else master.points
        if (master.points > 0) {
            for ((n, pts) in SlayerPlanner.upcoming(base, done, 3)) note(pc, "Task $n pays $pts points (${n - done} to go)", Ui.TAN)
            if (master.diaryPoints > master.points) note(pc, "Counted with the diary bonus (${master.diaryPoints} a task).")
        } else if (master.id == "mortimer") {
            note(pc, "Mortimer pays through his modifiers: see the points column on each task below.")
        }
        note(pc, "The count follows the game's \"You've completed N tasks\" message. If it's off, fix it:")
        pc.addView(spaced(Ui.button(this, "Set task count", false) { askNumber("Tasks done with ${master.name}", done) { store.setCount(master.id, it); rebuild() } }, 4))

        // What this master can give you.
        val eligible = rows.map {
            SlayerPlanner.classify(it, slayerLevel, combat, levels, { q -> questDone(travel, q) })
        }
        val chances = SlayerPlanner.chances(eligible)
        val open = eligible.filter { it.access != SlayerPlanner.Access.LOCKED }.sortedByDescending { it.row.weight }
        val locked = eligible.filter { it.access == SlayerPlanner.Access.LOCKED }.sortedBy { it.row.slayer }

        val tc = card("${master.name} can give you (${open.size})")
        if (levels.isEmpty()) note(tc, "Sync your account (main screen) and this list narrows to what you can really get.")
        else note(tc, "Slayer ${slayerLevel ?: "?"} · combat ${combat ?: "?"}. Chance is by task weight. Prepare for the big ones first.")
        if (rows.isEmpty()) note(tc, "No task list for this master yet.")
        for (e in open) taskRow(tc, data, store, master, e, chances[e.row.id])

        if (locked.isNotEmpty()) {
            val lc = card("Not open to you yet (${locked.size})")
            if (!showLocked) {
                lc.addView(spaced(Ui.button(this, "Show", false) { showLocked = true; rebuild() }, 4))
            } else {
                for (e in locked) {
                    rowWithButton(lc, e.row.name, "Needs ${e.why}", "Card") { openId = if (openId == e.row.id) null else e.row.id; rebuild() }
                    if (openId == e.row.id) lc.addView(spaced(SlayerViews.monsterCard(this, e.row.name, data.card(e.row.id), e.row, null), 4))
                }
            }
        }

        val credit = Ui.text(this, "Task tables and monster notes from the OSRS Wiki. Gear and food are suggestions.", 11f, Ui.MUTED)
        credit.setPadding(0, Ui.dp(this, 14), 0, 0)
        content.addView(credit)
    }

    private fun taskRow(c: android.widget.LinearLayout, data: SlayerData, store: SlayerStore, master: SlayerMaster, e: SlayerPlanner.Eligible, chance: Float?) {
        val r = e.row
        val sub = buildString {
            append("${r.kills} kills")
            if (chance != null) append(" · ${Math.round(chance * 100)}%")
            if (master.id == "mortimer" && r.points != null) append(" · points ${r.points}")
            if (e.access == SlayerPlanner.Access.ABILITY) append(" · needs a Slayer reward")
        }
        rowWithButtons(c, r.name, sub, listOf(
            (if (openId == r.id) "Hide" else "Card") to { openId = if (openId == r.id) null else r.id; rebuild() },
            "Got it" to { gotTask(store, master, r) }
        ), if (e.access == SlayerPlanner.Access.ABILITY) Ui.MUTED else Ui.TEXT)
        if (openId == r.id) {
            val place = if (r.places.size == 1) r.places[0] else null
            c.addView(spaced(SlayerViews.monsterCard(this, r.name, data.card(r.id), r, place), 4))
            if (r.places.size > 1) note(c, "${master.name} assigns it in: " + r.places.joinToString(", "), Ui.TAN, 11f)
        }
    }

    /** Fallback when the game message wasn't read: tell Breadcrumbs which task you got and how many. */
    private fun gotTask(store: SlayerStore, master: SlayerMaster, r: TaskRow) {
        askNumber("How many ${r.name}? (${r.kills})", r.hi) { n ->
            store.task = ActiveTask(master.id, r.name, n, n, r.places.singleOrNull(), System.currentTimeMillis())
            refreshOverlay()
            rebuild()
        }
    }

    private fun askNumber(title: String, initial: Int, onOk: (Int) -> Unit) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(initial.toString())
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().toIntOrNull()?.let { if (it in 0..5000) onOk(it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Null when we don't know your quests (so nothing is ruled out). */
    private fun questDone(travel: TravelStore, name: String): Boolean? {
        val done = travel.completedQuests
        if (done.isEmpty()) return null
        val k = QuestNames.canon(name)
        val started = travel.startedQuests
        fun has(set: Set<String>) = k in set || (k.length >= 10 && set.any { it.startsWith(k) && it.length - k.length >= 8 })
        return has(done) || has(started)
    }

    private fun refreshOverlay() {
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
    }
}
