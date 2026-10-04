package com.questoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.questoverlay.travel.TravelMode
import com.questoverlay.travel.TravelStore
import kotlin.math.roundToInt

/** Quest picker and settings. Pick a quest, start the overlay, then switch to the game. */
class MainActivity : Activity() {

    private lateinit var store: ProgressStore
    private enum class Filter(val label: String) {
        ALL("All"), FREE("Free"), MEMBERS("Members"), MINI("Mini"), STARTED("Started")
    }

    private lateinit var travel: TravelStore
    private lateinit var content: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var listBox: ScrollView
    private lateinit var countLabel: TextView
    private var quests: List<Quest> = emptyList()
    private var query = ""
    private var filter = Filter.ALL
    private val cards = ArrayList<Pair<Quest, View>>()
    private val searchNames = HashMap<String, String>()
    private lateinit var noMatches: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProgressStore(this)
        travel = TravelStore(this)
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
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(scroll)

        // Android 15+ draws apps edge to edge, so keep the content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    // ---------------------------------------------------------------- screen

    private fun refresh() {
        // Rebuilding (after Mark done, Reset, Start) keeps your place in the quest list.
        val keepListScroll = if (::listBox.isInitialized) listBox.scrollY else 0
        content.removeAllViews()
        val pad = Ui.dp(this, 16)
        content.setPadding(pad, pad, pad, pad)

        content.addView(Ui.text(this, "Breadcrumbs", 28f, Ui.GOLD, bold = true))
        val intro = Ui.text(
            this,
            "${quests.size} quests and miniquests. It never touches the game: you tick steps off yourself.",
            13f,
            Ui.TAN
        )
        intro.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4))
        content.addView(intro)
        val fanMade = Ui.text(this, JAGEX_DISCLAIMER, 11f, Ui.MUTED)
        fanMade.setPadding(0, 0, 0, Ui.dp(this, 14))
        content.addView(fanMade)

        content.addView(permissionCard())
        content.addView(opacityCard())
        content.addView(travelCard())

        content.addView(spaced(Ui.button(this, "Open Old School RuneScape", false) { launchGame() }, 12))
        if (OverlayService.running) {
            content.addView(spaced(Ui.button(this, "Stop overlay", false) { stopOverlay() }, 8))
        }

        val headingRow = LinearLayout(this)
        headingRow.orientation = LinearLayout.HORIZONTAL
        headingRow.gravity = Gravity.BOTTOM
        headingRow.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6))
        headingRow.addView(
            Ui.text(this, "Quests", 18f, Ui.TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        countLabel = Ui.text(this, "", 12f, Ui.MUTED)
        headingRow.addView(countLabel)
        content.addView(headingRow)

        content.addView(searchBox())
        content.addView(filterRow())

        // The quest list scrolls inside its own box, so the page doesn't turn into one long scroll.
        // Reaching the top or bottom of the box hands the scroll back to the page (nested scrolling).
        listContainer = LinearLayout(this)
        listContainer.orientation = LinearLayout.VERTICAL
        listBox = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.62f).toInt())
        listBox.isNestedScrollingEnabled = true
        listBox.isScrollbarFadingEnabled = false
        listBox.scrollBarSize = Ui.dp(this, 4)
        listBox.verticalScrollbarThumbDrawable = ColorDrawable(Ui.GOLD)
        listBox.verticalScrollbarTrackDrawable = ColorDrawable(0x33000000)
        listBox.background = BevelDrawable(this, 0xFF231D16.toInt(), Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
        val bp = Ui.dp(this, 6)
        listBox.setPadding(bp, 0, bp + Ui.dp(this, 4), bp)
        listBox.addView(
            listContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val boxLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        boxLp.topMargin = Ui.dp(this, 8)
        content.addView(listBox, boxLp)
        refreshList()
        if (keepListScroll > 0) listBox.post { listBox.scrollTo(0, keepListScroll) }

        val credit = Ui.text(
            this,
            "Quest steps come from the RuneLite Quest Helper plugin (BSD 2-Clause, " +
                "Copyright (c) 2020 Zoinkwiz). Travel data from the Shortest Path plugin (BSD 2-Clause). Map by mejrs. " +
                "Compass bearings are worked out from map tiles, so trust your minimap if they disagree.",
            11f,
            Ui.MUTED
        )
        credit.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8))
        content.addView(credit)
    }

    private fun searchBox(): View {
        val box = EditText(this)
        box.hint = "Search quests"
        box.setText(query)
        box.setSelection(box.text.length)
        box.setSingleLine(true)
        box.setTextColor(Ui.TEXT)
        box.setHintTextColor(Ui.MUTED)
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        box.typeface = Ui.typeface(this, bold = false)
        box.imeOptions = EditorInfo.IME_ACTION_SEARCH
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val p = Ui.dp(this, 12)
        box.setPadding(p, p, p, p)
        box.background = BevelDrawable(this, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q != query) {
                    query = q
                    filterChanged()
                }
            }
        })
        return box
    }

    private fun filterRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 8)
        row.layoutParams = lp
        val chips = ArrayList<TextView>()
        for (f in Filter.entries) {
            val chip = Ui.chip(this, f.label, f == filter)
            chip.setOnClickListener {
                filter = f
                styleChips(chips)
                filterChanged()
            }
            chips.add(chip)
            val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (f.ordinal > 0) clp.leftMargin = Ui.dp(this, 4)
            row.addView(chip, clp)
        }
        styleChips(chips)
        return row
    }

    private fun styleChips(chips: List<TextView>) {
        for ((i, chip) in chips.withIndex()) {
            val active = Filter.entries[i] == filter
            chip.setTextColor(if (active) Ui.TAN else Ui.GOLD)
            chip.background = Ui.stoneButton(this, down = active)
        }
    }

    private fun matches(q: Quest): Boolean {
        val step = store.stepIndex(q)
        val ok = when (filter) {
            Filter.ALL -> true
            Filter.FREE -> q.type == "Free"
            Filter.MEMBERS -> q.type == "Members"
            Filter.MINI -> q.type == "Miniquest"
            Filter.STARTED -> step > 0
        }
        if (!ok) return false
        val text = query.trim()
        if (text.isEmpty()) return true
        val needle = normalise(text)
        val hay = searchNames.getOrPut(q.id) { normalise(q.name) }
        return hay.contains(needle)
    }

    private fun normalise(s: String): String =
        s.lowercase().replace(APOSTROPHES, "").replace(NON_ALNUM, " ").replace(SPACES, " ").trim()

    /** Rebuilds the cards in sorted order. Typing in the search box only calls [applyFilter]. */
    private fun refreshList() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        cards.clear()
        val active = if (OverlayService.running) store.activeQuestId else null
        // The quest on screen first, then ones in progress, then the rest alphabetically.
        val sorted = quests.sortedWith(
            compareBy<Quest>(
                { if (it.id == active) 0 else 1 },
                {
                    val s = store.stepIndex(it)
                    if (s > 0 && s < it.steps.size) 0 else 1
                }
            )
        )
        for (q in sorted) {
            val card = questCard(q)
            cards.add(q to card)
            listContainer.addView(card)
        }
        noMatches = Ui.text(this, "No quests match.", 14f, Ui.MUTED)
        noMatches.setPadding(0, Ui.dp(this, 12), 0, 0)
        listContainer.addView(noMatches)
        applyFilter()
    }

    private fun applyFilter() {
        var shown = 0
        for ((q, card) in cards) {
            val show = matches(q)
            card.visibility = if (show) View.VISIBLE else View.GONE
            if (show) shown++
        }
        if (::noMatches.isInitialized) noMatches.visibility = if (shown > 0) View.GONE else View.VISIBLE
        if (::countLabel.isInitialized) {
            countLabel.text = if (shown == cards.size) "${cards.size} quests" else "$shown of ${cards.size}"
        }
    }

    /** A new search or filter starts at the top of the list box. */
    private fun filterChanged() {
        applyFilter()
        if (::listBox.isInitialized) listBox.scrollTo(0, 0)
    }

    private fun spaced(view: View, topDp: Int): View {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, topDp)
        view.layoutParams = lp
        return view
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 14)
        c.setPadding(p, p, p, p)
        c.background = Ui.rounded(this, Ui.CARD_BG, 14, Ui.STROKE, 1)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 8)
        c.layoutParams = lp
        return c
    }

    private fun permissionCard(): View {
        val c = card()
        if (Settings.canDrawOverlays(this)) {
            c.addView(Ui.text(this, "Overlay permission granted ✓", 14f, Ui.GREEN, bold = true))
        } else {
            c.addView(Ui.text(this, "Overlay permission needed", 14f, Ui.RED, bold = true))
            val why = Ui.text(
                this,
                "Android needs you to allow \"Display over other apps\" so the card can sit on top of the game.",
                12f,
                Ui.TEXT
            )
            why.setPadding(0, Ui.dp(this, 4), 0, 0)
            c.addView(why)
            c.addView(spaced(Ui.button(this, "Grant permission", true) { openOverlaySettings() }, 10))
        }
        return c
    }

    private fun opacityCard(): View {
        val c = card()
        val percent = (store.opacity * 100f).roundToInt()
        val label = Ui.text(this, "Overlay opacity: $percent%", 14f, Ui.TEXT, bold = true)
        c.addView(label)

        val seek = SeekBar(this)
        seek.max = 60
        seek.progress = percent - 40
        seek.progressTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.thumbTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress + 40
                label.text = "Overlay opacity: $value%"
                if (fromUser) store.opacity = value / 100f
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {
                refreshOverlay()
            }
        })
        c.addView(seek)
        return c
    }

    private fun questCard(q: Quest): View {
        val step = store.stepIndex(q).coerceIn(0, q.steps.size)
        val c = card()
        val isActive = OverlayService.running && store.activeQuestId == q.id
        if (isActive) c.background = Ui.rounded(this, Ui.CARD_BG, 14, Ui.GOLD, 2)

        // Same colours as the in-game quest list: red not started, yellow in progress, green done.
        val doneHere = step >= q.steps.size || travel.isQuestDone(q.name)
        val nameColor = when {
            doneHere -> Ui.GREEN
            step > 0 -> Ui.TAN
            else -> Ui.RED
        }
        c.addView(Ui.text(this, q.name, 17f, nameColor, bold = true))
        val meta = listOf(q.type, q.difficulty, "${q.steps.size} steps")
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        c.addView(Ui.text(this, meta, 12f, Ui.TAN))

        if (q.requirements.isNotEmpty()) {
            val req = Ui.text(this, "Needs: " + q.requirements.joinToString(", "), 12f, Ui.MUTED)
            req.maxLines = 2
            req.ellipsize = TextUtils.TruncateAt.END
            req.setPadding(0, Ui.dp(this, 4), 0, 0)
            c.addView(req)
        }

        val markedDone = travel.isQuestDone(q.name)
        val status = when {
            step >= q.steps.size -> "Complete ✓"
            markedDone -> "Marked as done ✓"
            step == 0 -> "Not started"
            else -> "In progress: step ${step + 1} of ${q.steps.size}"
        }
        val statusColor = if (step >= q.steps.size || markedDone) Ui.GREEN else Ui.MUTED
        val statusView = Ui.text(this, status, 13f, statusColor)
        statusView.setPadding(0, Ui.dp(this, 6), 0, 0)
        c.addView(statusView)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val start = Ui.button(this, if (isActive) "Showing" else "Start overlay", !isActive) { startOverlay(q) }
        buttons.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        if (step > 0) {
            val reset = Ui.button(this, "Reset", false) {
                store.reset(q.id)
                refreshOverlay()
                refresh()
            }
            val resetLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            resetLp.leftMargin = Ui.dp(this, 8)
            buttons.addView(reset, resetLp)
        }
        // Tells the travel guide which quest-locked transport you can use.
        val done = Ui.button(this, if (markedDone) "Done \u2713" else "Mark done", false) {
            travel.setQuestDone(q.name, !markedDone)
            refreshOverlay()
            refresh()
        }
        if (markedDone) done.setTextColor(Ui.GREEN)
        val doneLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
        doneLp.leftMargin = Ui.dp(this, 8)
        buttons.addView(done, doneLp)
        c.addView(spaced(buttons, 10))
        return c
    }

    private fun travelCard(): View {
        val c = card()
        c.addView(Ui.text(this, "Travel guide", 14f, Ui.TEXT, bold = true))
        val modes = TravelMode.entries.count { travel.isModeOn(it) }
        val lv = travel.levels
        val who = if (travel.username.isNotBlank()) travel.username else "No account linked"
        val summary = buildString {
            append(who)
            append(" \u00B7 ")
            append(if (travel.members) "Members" else "Free-to-play")
            append(" \u00B7 $modes ways to travel on")
            if (lv.isNotEmpty()) append(" \u00B7 Magic ${lv["Magic"] ?: "?"}")
        }
        val sub = Ui.text(this, summary, 12f, Ui.TAN)
        sub.setPadding(0, Ui.dp(this, 2), 0, 0)
        c.addView(sub)
        c.addView(spaced(Ui.button(this, "Travel settings", false) {
            startActivity(Intent(this, TravelSettingsActivity::class.java))
        }, 10))
        return c
    }

    // ---------------------------------------------------------------- actions

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun startOverlay(q: Quest) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant the overlay permission first.", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        store.activeQuestId = q.id
        val intent = Intent(this, OverlayService::class.java)
        intent.putExtra(OverlayService.EXTRA_QUEST_ID, q.id)
        startForegroundService(intent)
        Toast.makeText(
            this,
            "Overlay started. Open the game, then drag the card by its title.",
            Toast.LENGTH_LONG
        ).show()
        refresh()
    }

    private fun stopOverlay() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }
        // The service takes a moment to shut down, so redraw after it has.
        content.postDelayed({ refresh() }, 400)
    }

    /** Tells a running overlay to redraw (opacity or progress changed). */
    private fun refreshOverlay() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
        }
    }

    private fun launchGame() {
        val intent = packageManager.getLaunchIntentForPackage(OSRS_PACKAGE)
        if (intent == null) {
            Toast.makeText(this, "Couldn't find the game. Open it from your app list.", Toast.LENGTH_LONG).show()
        } else {
            startActivity(intent)
        }
    }

    private companion object {
        val APOSTROPHES = Regex("['\u2019]")
        val NON_ALNUM = Regex("[^a-z0-9 ]")
        val SPACES = Regex("\\s+")
        const val REQUEST_NOTIFICATIONS = 100
        /** Wording required by Jagex's Fan Content Policy. */
        const val JAGEX_DISCLAIMER =
            "Created using intellectual property belonging to Jagex Limited under the terms of " +
                "Jagex's Fan Content Policy. This content is not endorsed by or affiliated with Jagex."
        const val OSRS_PACKAGE = "com.jagex.oldscape.android"
    }
}
