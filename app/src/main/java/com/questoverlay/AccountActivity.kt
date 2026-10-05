package com.questoverlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.questoverlay.account.Advisor
import com.questoverlay.account.Req
import com.questoverlay.travel.AccountSync
import com.questoverlay.travel.TravelStore
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * "Your account": levels, quests, quest points and diary progress in one place, plus what to do
 * next. Everything comes from the public hiscores, WikiSync and what you've done in Breadcrumbs.
 */
class AccountActivity : Activity() {

    private lateinit var store: TravelStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var quests: List<Quest> = emptyList()
    private var showAllReady = false
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TravelStore(this)
        quests = try {
            QuestRepository.load(this)
        } catch (e: Exception) {
            emptyList()
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.SCREEN_BG)
        scroll.clipToPadding = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        // Finishing or marking quests done elsewhere changes the suggestions.
        if (::content.isInitialized) build()
    }

    private fun advisor() = Advisor(quests, store.levels, store.completedQuests, store.startedQuests)

    private fun build() {
        content.removeAllViews()
        val pad = Ui.dp(this, 16)
        content.setPadding(pad, pad, pad, pad)
        val a = advisor()

        content.addView(Ui.text(this, "Your account", 26f, Ui.GOLD, bold = true))
        val intro = Ui.text(
            this,
            "Levels from the official hiscores, quests and diaries from WikiSync, plus what you've done in Breadcrumbs.",
            13f,
            Ui.TAN
        )
        intro.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6))
        content.addView(intro)

        content.addView(syncCard())
        content.addView(statsRow(a))
        content.addView(nextCard(a))
        content.addView(almostCard(a))
        content.addView(skillsCard())
        content.addView(diaryCard())
        content.addView(questsCard(a))

        val credit = Ui.text(
            this,
            "Nothing here reads the game. WikiSync only has data if you've played with RuneLite's WikiSync plugin; " +
                "quests you finish or mark done in Breadcrumbs count too.",
            11f,
            Ui.MUTED
        )
        credit.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8))
        content.addView(credit)
    }

    // ---------------------------------------------------------------- building blocks

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 14)
        c.setPadding(p, p, p, p)
        c.background = Ui.rounded(this, Ui.CARD_BG, 14, Ui.STROKE, 1)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 10)
        c.layoutParams = lp
        if (title.isNotEmpty()) c.addView(Ui.text(this, title, 16f, Ui.GOLD, bold = true))
        return c
    }

    private fun note(c: LinearLayout, text: String) {
        val t = Ui.text(this, text, 12f, Ui.MUTED)
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        c.addView(t)
    }

    private fun spaced(view: View, topDp: Int): View {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, topDp)
        view.layoutParams = lp
        return view
    }

    private fun fmt(n: Int): String = NumberFormat.getIntegerInstance().format(n)

    // ---------------------------------------------------------------- sync

    private fun syncCard(): View {
        val c = card("")
        val name = EditText(this)
        name.hint = "RuneScape name"
        name.setText(store.username)
        name.setSingleLine(true)
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        name.imeOptions = EditorInfo.IME_ACTION_DONE
        name.setTextColor(Ui.TEXT)
        name.setHintTextColor(Ui.MUTED)
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        name.typeface = Ui.typeface(this, bold = false)
        val p = Ui.dp(this, 12)
        name.setPadding(p, p, p, p)
        name.background = BevelDrawable(this, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
        c.addView(name)

        c.addView(spaced(Ui.button(this, if (syncing) "Syncing…" else "Sync account", true) {
            if (!syncing) sync(name.text.toString())
        }, 8))

        status = Ui.text(this, lastSyncText(), 12f, Ui.TAN)
        status.setPadding(0, Ui.dp(this, 8), 0, 0)
        c.addView(status)
        return c
    }

    private fun lastSyncText(): String {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val lines = ArrayList<String>()
        lines.add(
            if (store.levelsUpdated > 0) "Hiscores: ${df.format(Date(store.levelsUpdated))}"
            else "Hiscores: not loaded yet"
        )
        lines.add(
            if (store.wikiSyncUpdated > 0) "WikiSync: ${df.format(Date(store.wikiSyncUpdated))}"
            else "WikiSync: not loaded yet"
        )
        return lines.joinToString("\n")
    }

    /** Hiscores first (levels), then WikiSync (quests, diaries). Either can fail on its own. */
    private fun sync(rawName: String) {
        val rsn = rawName.trim()
        if (rsn.isEmpty()) {
            status.text = "Type your RuneScape name first."
            return
        }
        store.username = rsn
        syncing = true
        status.text = "Asking the hiscores…"
        val messages = ArrayList<String>()
        AccountSync.fetchLevels(rsn) { levels ->
            if (isDestroyed) return@fetchLevels
            when (levels) {
                is AccountSync.Outcome.Ok -> {
                    store.levels = levels.value
                    store.levelsUpdated = System.currentTimeMillis()
                    messages.add("Levels loaded.")
                }
                is AccountSync.Outcome.Error -> messages.add(levels.message)
            }
            status.text = "Asking WikiSync…"
            AccountSync.fetchWikiSync(rsn) { wiki ->
                if (isDestroyed) return@fetchWikiSync
                when (wiki) {
                    is AccountSync.Outcome.Ok -> {
                        store.applyWikiSync(wiki.value)
                        messages.add("Quests and diaries loaded from WikiSync.")
                    }
                    is AccountSync.Outcome.Error -> messages.add(wiki.message)
                }
                syncing = false
                build()
                status.text = messages.joinToString("\n") + "\n" + lastSyncText()
            }
        }
        build()
        status.text = "Asking the hiscores…"
    }

    // ---------------------------------------------------------------- headline numbers

    private fun statsRow(a: Advisor): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val levels = store.levels
        val total = levels["Overall"] ?: levels.filterKeys { it != "Overall" }.values.sum().takeIf { it > 0 }
        val done = quests.count { a.isDone(it) }
        val boxes = listOf(
            "Combat" to (a.combat?.toString() ?: "–"),
            "Total" to (total?.let { fmt(it) } ?: "–"),
            "Quest pts" to "${a.questPoints}/${a.maxQuestPoints}",
            "Quests" to "$done/${quests.size}"
        )
        for ((i, b) in boxes.withIndex()) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            val p = Ui.dp(this, 8)
            box.setPadding(Ui.dp(this, 4), p, Ui.dp(this, 4), p)
            box.background = BevelDrawable(this, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
            val value = Ui.text(this, b.second, 16f, Ui.TAN, bold = true)
            value.gravity = Gravity.CENTER
            value.maxLines = 1
            value.setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            val label = Ui.text(this, b.first, 12f, Ui.MUTED)
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            box.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)))
            box.addView(label)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = Ui.dp(this, 4)
            row.addView(box, lp)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 10)
        row.layoutParams = lp
        return row
    }

    // ---------------------------------------------------------------- what to do next

    private fun questRow(c: LinearLayout, q: Quest, detail: String, a: Advisor) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val nameColor = if (a.isStarted(q)) Ui.TAN else Ui.TEXT
        texts.addView(Ui.text(this, q.name, 14f, nameColor, bold = true))
        texts.addView(Ui.text(this, detail, 12f, Ui.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val start = Ui.chip(this, "Start", false)
        start.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8))
        start.setOnClickListener { startQuest(q) }
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.leftMargin = Ui.dp(this, 8)
        row.addView(start, slp)
        c.addView(row)
    }

    private fun describe(q: Quest, a: Advisor): String {
        val parts = ArrayList<String>()
        if (a.isStarted(q)) parts.add("In progress")
        parts.add(q.difficulty.ifBlank { q.type })
        if (q.qp > 0) parts.add("${q.qp} QP")
        val opens = a.unlocks(q)
        if (opens > 0) parts.add("needed for $opens other quest${if (opens == 1) "" else "s"}")
        return parts.joinToString(" · ")
    }

    private fun nextCard(a: Advisor): View {
        val c = card("Do next")
        val ready = a.ready()
        if (store.completedQuests.isEmpty() && store.levels.isEmpty()) {
            note(c, "Sync your account (or mark quests done on the quest list) for suggestions that fit you. Until then, everything counts as available.")
        }
        if (ready.isEmpty()) {
            note(c, "Nothing is ready right now. Check \"Almost there\" below for the levels to train.")
            return c
        }
        note(c, "${ready.size} quests you can start now. Ones you've started come first, then the ones other quests need.")
        val shown = if (showAllReady) ready else ready.take(6)
        for (q in shown) questRow(c, q, describe(q, a), a)
        if (ready.size > 6) {
            c.addView(spaced(Ui.button(this, if (showAllReady) "Show fewer" else "Show all ${ready.size}", false) {
                showAllReady = !showAllReady
                build()
            }, 10))
        }
        return c
    }

    private fun almostCard(a: Advisor): View {
        val c = card("Almost there")
        if (store.levels.filterKeys { it != "Overall" }.isEmpty()) {
            note(c, "Sync your levels to see quests that are only a few levels away.")
            return c
        }
        val almost = a.almost().take(6)
        if (almost.isEmpty()) {
            note(c, "No quests are a few levels away right now.")
            return c
        }
        note(c, "Quests held back only by a few levels.")
        for ((q, missing) in almost) questRow(c, q, "Needs " + missing.joinToString(", "), a)
        return c
    }

    private fun startQuest(q: Quest) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(EXTRA_START_QUEST, q.id)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    // ---------------------------------------------------------------- skills

    private fun skillsCard(): View {
        val c = card("Skills")
        val levels = store.levels
        if (levels.filterKeys { it != "Overall" }.isEmpty()) {
            note(c, "Sync your account to load your levels from the hiscores.")
            return c
        }
        for (rowSkills in Req.SKILLS.chunked(3)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for ((i, skill) in rowSkills.withIndex()) {
                val lvl = levels[skill]
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.HORIZONTAL
                cell.gravity = Gravity.CENTER_VERTICAL
                val p = Ui.dp(this, 6)
                cell.setPadding(Ui.dp(this, 8), p, Ui.dp(this, 8), p)
                cell.background = BevelDrawable(this, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
                val label = Ui.text(this, skill, 12f, Ui.MUTED)
                label.maxLines = 1
                label.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                cell.addView(label, LinearLayout.LayoutParams(0, Ui.dp(this, 18), 1f))
                val color = when {
                    lvl == null -> Ui.MUTED
                    lvl >= 99 -> Ui.GOLD
                    else -> Ui.TAN
                }
                cell.addView(Ui.text(this, lvl?.toString() ?: "–", 14f, color, bold = true))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) lp.leftMargin = Ui.dp(this, 4)
                row.addView(cell, lp)
            }
            // Keep the columns even on a short last row.
            repeat(3 - rowSkills.size) {
                val lp = LinearLayout.LayoutParams(0, 1, 1f)
                lp.leftMargin = Ui.dp(this, 4)
                row.addView(View(this), lp)
            }
            c.addView(spaced(row, 4))
        }
        if (Req.SKILLS.any { levels[it] == null }) {
            note(c, "A dash means the hiscores don't rank that skill yet (it's too low).")
        }
        return c
    }

    // ---------------------------------------------------------------- diaries

    private fun diaryCard(): View {
        val c = card("Achievement diaries")
        val diaries = store.diaries
        if (diaries.isEmpty()) {
            note(c, "Diary progress comes from WikiSync. Sync your account if you've played with RuneLite's WikiSync plugin.")
            return c
        }
        val tiers = listOf("Easy", "Medium", "Hard", "Elite")
        val done = diaries.count { it.complete }
        note(c, "$done of ${diaries.size} tiers complete.")

        // Header row with the tier names.
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1.6f))
        for (t in tiers) {
            val h = Ui.text(this, t, 12f, Ui.MUTED, bold = true)
            h.gravity = Gravity.CENTER
            head.addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(spaced(head, 6))

        val byRegion = diaries.groupBy { it.region }
        for (region in byRegion.keys.sorted()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val name = Ui.text(this, region, 12f, Ui.TEXT)
            name.maxLines = 1
            name.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            row.addView(name, LinearLayout.LayoutParams(0, Ui.dp(this, 20), 1.6f))
            for (t in tiers) {
                val d = byRegion[region]?.firstOrNull { it.tier.equals(t, ignoreCase = true) }
                val (text, color) = when {
                    d == null -> "?" to Ui.MUTED
                    d.complete -> "✓" to Ui.GREEN
                    d.done > 0 -> "${d.done}/${d.total}" to Ui.TAN
                    else -> "0/${d.total}" to Ui.RED
                }
                val cell = Ui.text(this, text, 12f, color, bold = true)
                cell.gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.leftMargin = Ui.dp(this, 2)
                cell.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
                cell.background = BevelDrawable(this, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
                row.addView(cell, lp)
            }
            c.addView(spaced(row, 3))
        }

        // The unfinished tiers you're furthest into.
        val closest = diaries.filter { !it.complete && it.done > 0 && it.total > 0 }
            .sortedByDescending { it.done.toFloat() / it.total }
            .take(3)
        if (closest.isNotEmpty()) {
            note(c, "Closest to done: " + closest.joinToString(", ") { "${it.region} ${it.tier} (${it.done}/${it.total})" })
        }
        return c
    }

    // ---------------------------------------------------------------- quest totals

    private fun questsCard(a: Advisor): View {
        val c = card("Quests")
        val groups = listOf(
            "Free" to quests.filter { it.type == "Free" },
            "Members" to quests.filter { it.type == "Members" },
            "Miniquests" to quests.filter { it.type == "Miniquest" }
        )
        for ((label, list) in groups) {
            val done = list.count { a.isDone(it) }
            val started = list.count { a.isStarted(it) }
            val line = "$label: $done of ${list.size} done" + if (started > 0) ", $started in progress" else ""
            val t = Ui.text(this, line, 13f, if (done == list.size && list.isNotEmpty()) Ui.GREEN else Ui.TEXT)
            t.setPadding(0, Ui.dp(this, 4), 0, 0)
            c.addView(t)
        }
        val cape = a.questPoints >= a.maxQuestPoints && a.maxQuestPoints > 0
        note(c, if (cape) "Every quest point earned. Quest cape time!" else "${a.maxQuestPoints - a.questPoints} quest points to go for the quest cape.")
        return c
    }

    companion object {
        /** Quest id for the main screen to start in the overlay. */
        const val EXTRA_START_QUEST = "start_quest"
    }
}
