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
import com.questoverlay.account.Advisor
import com.questoverlay.capture.AlertRule
import com.questoverlay.capture.AlertStore
import com.questoverlay.capture.Alerter
import com.questoverlay.capture.BuiltInAlerts
import com.questoverlay.capture.CaptureLog
import com.questoverlay.travel.TravelMode
import com.questoverlay.travel.TravelStore
import kotlin.math.roundToInt

/** Quest picker and settings. Pick a quest, start the overlay, then switch to the game. */
class MainActivity : Activity() {

    private lateinit var store: ProgressStore
    private enum class Filter(val label: String) {
        ALL("Quests"), FREE("Free"), MEMBERS("Members"), MINI("Mini"), DIARIES("Diaries")
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
    private lateinit var listHeading: TextView

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
        handleStartQuest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartQuest(intent)
    }

    /** "Start" on the account screen opens this screen with a quest to put in the overlay. */
    private fun handleStartQuest(intent: Intent?) {
        val id = intent?.getStringExtra(AccountActivity.EXTRA_START_QUEST) ?: return
        intent.removeExtra(AccountActivity.EXTRA_START_QUEST)
        quests.firstOrNull { it.id == id }?.let { startOverlay(it) }
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
        val diaryCount = quests.count { it.isDiary }
        val intro = Ui.text(
            this,
            "${quests.size - diaryCount} quests and miniquests, and all $diaryCount achievement diary tiers. " +
                "It never touches the game: it only reads the screen when you switch on \uD83D\uDC41.",
            13f,
            Ui.TAN
        )
        intro.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4))
        content.addView(intro)
        val fanMade = Ui.text(this, JAGEX_DISCLAIMER, 11f, Ui.MUTED)
        fanMade.setPadding(0, 0, 0, Ui.dp(this, 14))
        content.addView(fanMade)

        content.addView(accountCard())
        if (!Authenticity.isOfficial(this)) content.addView(unofficialCard())
        content.addView(permissionCard())
        content.addView(opacityCard())
        content.addView(travelCard())
        content.addView(autoCheckCard())
        content.addView(coachCard())
        content.addView(alertsCard())

        content.addView(spaced(Ui.button(this, "Open Old School RuneScape", false) { launchGame() }, 12))
        if (OverlayService.running) {
            content.addView(spaced(Ui.button(this, "Stop overlay", false) { stopOverlay() }, 8))
        }

        val headingRow = LinearLayout(this)
        headingRow.orientation = LinearLayout.HORIZONTAL
        headingRow.gravity = Gravity.BOTTOM
        headingRow.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6))
        listHeading = Ui.text(this, if (filter == Filter.DIARIES) "Achievement diaries" else "Quests", 18f, Ui.TEXT, bold = true)
        headingRow.addView(listHeading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countLabel = Ui.text(this, "", 12f, Ui.MUTED)
        headingRow.addView(countLabel)
        content.addView(headingRow)

        content.addView(searchBox())
        content.addView(filterRow())
        content.addView(toggleRow())

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

        // Optional tip jar. Nothing in the app is locked behind it.
        val tip = Ui.text(this, "Breadcrumbs is free. Enjoying it? Tip on Ko-fi ›", 12f, Ui.GOLD, bold = true)
        tip.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12))
        tip.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, "No browser found to open Ko-fi.", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(tip)
    }

    private fun searchBox(): View {
        val box = EditText(this)
        box.hint = "Search quests and diaries"
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

    /** "In progress" and "Hide done": switched on and off with a tap, remembered between visits. */
    private fun toggleRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 4)
        row.layoutParams = lp
        fun toggle(label: String, on: () -> Boolean, set: (Boolean) -> Unit): TextView {
            val chip = Ui.chip(this, "", false)
            fun style() {
                val active = on()
                chip.text = (if (active) "\u2713 " else "") + label
                chip.setTextColor(if (active) Ui.TAN else Ui.GOLD)
                chip.background = Ui.stoneButton(this, down = active)
            }
            style()
            chip.setOnClickListener {
                set(!on())
                style()
                filterChanged()
            }
            return chip
        }
        row.addView(toggle("In progress", { store.startedOnly }, { store.startedOnly = it }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val hlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        hlp.leftMargin = Ui.dp(this, 4)
        row.addView(toggle("Hide done", { store.hideDone }, { store.hideDone = it }), hlp)
        return row
    }

    /** Finished in Breadcrumbs, marked done, or (diaries) complete according to WikiSync. */
    private fun isDone(q: Quest): Boolean {
        if (store.stepIndex(q) >= q.steps.size || travel.isQuestDone(q.name)) return true
        return q.isDiary && travel.diaryTier(q.region, q.tier)?.complete == true
    }

    private fun styleChips(chips: List<TextView>) {
        for ((i, chip) in chips.withIndex()) {
            val active = Filter.entries[i] == filter
            chip.setTextColor(if (active) Ui.TAN else Ui.GOLD)
            chip.background = Ui.stoneButton(this, down = active)
        }
    }

    private fun inTab(q: Quest): Boolean = when (filter) {
        Filter.ALL -> !q.isDiary
        Filter.FREE -> q.type == "Free"
        Filter.MEMBERS -> q.type == "Members"
        Filter.MINI -> q.type == "Miniquest"
        Filter.DIARIES -> q.isDiary
    }

    private fun matches(q: Quest): Boolean {
        if (!inTab(q)) return false
        val done = isDone(q)
        if (store.hideDone && done) return false
        if (store.startedOnly && (done || store.stepIndex(q) == 0)) return false
        val text = query.trim()
        if (text.isEmpty()) return true
        val needle = normalise(text)
        val hay = searchNames.getOrPut(q.id) {
            // Diaries can also be found by their tasks ("fishing trawler").
            normalise(if (q.isDiary) q.name + " " + q.steps.joinToString(" ") { it.section } else q.name)
        }
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
        noMatches = Ui.text(this, "Nothing matches.", 14f, Ui.MUTED)
        noMatches.setPadding(0, Ui.dp(this, 12), 0, 0)
        listContainer.addView(noMatches)
        applyFilter()
    }

    private fun applyFilter() {
        var shown = 0
        var inTab = 0
        var doneInTab = 0
        for ((q, card) in cards) {
            val show = matches(q)
            card.visibility = if (show) View.VISIBLE else View.GONE
            if (show) shown++
            if (inTab(q)) {
                inTab++
                if (isDone(q)) doneInTab++
            }
        }
        val noun = if (filter == Filter.DIARIES) "tiers" else "quests"
        if (::noMatches.isInitialized) {
            noMatches.text = if (store.hideDone && doneInTab == inTab && inTab > 0) "All done here \u2713" else "Nothing matches."
            noMatches.visibility = if (shown > 0) View.GONE else View.VISIBLE
        }
        if (::listHeading.isInitialized) listHeading.text = if (filter == Filter.DIARIES) "Achievement diaries" else "Quests"
        if (::countLabel.isInitialized) {
            countLabel.text = (if (shown == inTab) "$inTab $noun" else "$shown of $inTab $noun") + " \u00B7 $doneInTab done"
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

    /** Shown when this copy isn't signed with the Breadcrumbs release key. */
    private fun unofficialCard(): View {
        val c = card()
        c.background = Ui.rounded(this, Ui.CARD_BG, 14, Ui.RED, 2)
        c.addView(Ui.text(this, "Unofficial copy", 16f, Ui.RED, bold = true))
        val why = Ui.text(
            this,
            "This copy of Breadcrumbs wasn't published by its developer, so it may have been changed. " +
                "The real app is free, with no ads, from the official site.",
            12f,
            Ui.TEXT
        )
        why.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8))
        c.addView(why)
        c.addView(Ui.button(this, "Get the official app", true) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE)))
            } catch (e: Exception) {
                Toast.makeText(this, OFFICIAL_SITE, Toast.LENGTH_LONG).show()
            }
        })
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
        val doneHere = isDone(q)
        val nameColor = when {
            doneHere -> Ui.GREEN
            step > 0 -> Ui.TAN
            else -> Ui.RED
        }
        c.addView(Ui.text(this, q.name, 17f, nameColor, bold = true))
        val tasks = q.steps.count { it.section.isNotBlank() }
        val meta = (if (q.isDiary) listOf(q.region, q.tier, "$tasks tasks", "${q.steps.size} steps")
            else listOf(q.type, q.difficulty, "${q.steps.size} steps"))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        c.addView(Ui.text(this, meta, 12f, Ui.TAN))

        val unmet = if (q.requirements.isEmpty()) emptyList() else
            Advisor(QuestRepository.questsOnly(this), travel.levels, travel.completedQuests, travel.startedQuests).unmet(q)
        if (unmet.isNotEmpty()) {
            val req = Ui.text(this, "Still needed: " + unmet.joinToString(", "), 12f, Ui.MUTED)
            req.maxLines = 2
            req.ellipsize = TextUtils.TruncateAt.END
            req.setPadding(0, Ui.dp(this, 4), 0, 0)
            c.addView(req)
        }

        val markedDone = travel.isQuestDone(q.name)
        val synced = if (q.isDiary) travel.diaryTier(q.region, q.tier) else null
        val status = when {
            step >= q.steps.size -> "Complete ✓"
            markedDone -> "Marked as done ✓"
            synced?.complete == true -> "Complete (WikiSync) ✓"
            step == 0 && synced != null && synced.total > 0 -> "${synced.done} of ${synced.total} tasks done (WikiSync)"
            step == 0 -> "Not started"
            q.isDiary -> "In progress: ${q.sectionOf(step)} (step ${step + 1} of ${q.steps.size})"
            else -> "In progress: step ${step + 1} of ${q.steps.size}"
        }
        val statusColor = if (doneHere) Ui.GREEN else Ui.MUTED
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

    /** Explains Auto-check and shows what it read lately, so misreads can be spotted. */
    private fun autoCheckCard(): View {
        val c = card()
        c.addView(Ui.text(this, "Auto-check (screen reading)", 14f, Ui.TEXT, bold = true))
        val how = Ui.text(
            this,
            "Tap \uD83D\uDC41 on the floating card and share the game screen (on Android 14+ pick " +
                "\"A single app\" \u2192 Old School RuneScape). Breadcrumbs then notices who you're talking to, " +
                "outlines the right dialogue option, asks \"Looks done?\" and ticks quests off when they're " +
                "complete. \uD83D\uDD0A / \uD83D\uDD07 turns the voice on or off.",
            12f,
            Ui.TAN
        )
        how.setPadding(0, Ui.dp(this, 2), 0, 0)
        c.addView(how)
        val privacy = Ui.text(
            this,
            "It only reads pixels, like a screen recorder: never the game's memory or network, and it never " +
                "taps anything. Pictures are read on your phone and thrown away; nothing is saved or sent.",
            11f,
            Ui.MUTED
        )
        privacy.setPadding(0, Ui.dp(this, 6), 0, 0)
        c.addView(privacy)

        val recent = CaptureLog.recent(16)
        c.addView(spaced(Ui.text(this, "What it read", 13f, Ui.TEXT, bold = true), 10))
        val log = Ui.text(
            this,
            if (recent.isEmpty()) "Nothing yet. Switch on \uD83D\uDC41 and play for a bit, then come back here."
            else recent.joinToString("\n"),
            11f,
            Ui.MUTED
        )
        log.typeface = android.graphics.Typeface.MONOSPACE
        log.setTextIsSelectable(true)
        val box = MaxHeightScrollView(this, Ui.dp(this, 220))
        val p = Ui.dp(this, 8)
        log.setPadding(p, p, p, p)
        box.addView(log)
        box.background = BevelDrawable(this, 0xFF231D16.toInt(), Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
        box.isNestedScrollingEnabled = true
        c.addView(spaced(box, 4))
        if (recent.isNotEmpty()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(Ui.button(this, "Refresh", false) { refresh() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            clp.leftMargin = Ui.dp(this, 8)
            row.addView(Ui.button(this, "Clear", false) {
                CaptureLog.clear()
                refresh()
            }, clp)
            c.addView(spaced(row, 8))
        }
        return c
    }

    private fun switchRow(c: LinearLayout, label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
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

    /** The coach's other tools: vitals warnings, farming timers, kill counts and pets, Where am I, Sailing. */
    private fun coachCard(): View {
        val c = card()
        c.addView(Ui.text(this, "Coach", 14f, Ui.TEXT, bold = true))
        val farm = com.questoverlay.farming.FarmingStore(this).timers.filter { it.readyAt > System.currentTimeMillis() }
        val sub = Ui.text(
            this,
            "HP and prayer warnings, farming timers" + (if (farm.isNotEmpty()) " (${farm.size} growing)" else "") +
                ", kill counts and pet odds, finding you on the minimap, Sailing, and Slayer.",
            12f,
            Ui.TAN
        )
        sub.setPadding(0, Ui.dp(this, 2), 0, 0)
        c.addView(sub)
        fun open(label: String, cls: Class<*>, primary: Boolean = false): View =
            Ui.button(this, label, primary) { startActivity(Intent(this, cls)) }
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(open("Farming", com.questoverlay.farming.FarmingActivity::class.java), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        l2.leftMargin = Ui.dp(this, 8)
        row1.addView(open("Pets & KC", com.questoverlay.capture.PetsActivity::class.java), l2)
        c.addView(spaced(row1, 10))
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(open("Where am I", com.questoverlay.location.WhereActivity::class.java), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val l3 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        l3.leftMargin = Ui.dp(this, 8)
        row2.addView(open("Sailing", com.questoverlay.sailing.SailingActivity::class.java), l3)
        c.addView(spaced(row2, 8))
        c.addView(spaced(open("Slayer", com.questoverlay.slayer.SlayerActivity::class.java), 8))
        c.addView(spaced(open("Warnings & settings", com.questoverlay.capture.CoachSettingsActivity::class.java), 8))
        return c
    }

    /** AFK alerts: what to buzz you about while Auto-check is watching. */
    private fun alertsCard(): View {
        val alerts = AlertStore(this)
        val c = card()
        c.addView(Ui.text(this, "AFK alerts", 14f, Ui.TEXT, bold = true))
        val how = Ui.text(
            this,
            "While \uD83D\uDC41 Auto-check is on, Breadcrumbs watches the game's messages. When one of these appears " +
                "it buzzes, says it out loud (unless muted) and pops up a notification. The game has to stay on " +
                "screen; a pop-up or split-screen window works if you want to do something else.",
            12f,
            Ui.TAN
        )
        how.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4))
        c.addView(how)
        switchRow(c, "AFK alerts on", null, alerts.enabled) {
            alerts.enabled = it
            refresh()
        }
        if (!alerts.enabled) return c

        for (rule in BuiltInAlerts.ALL) {
            switchRow(
                c, rule.label,
                if (rule.confirmed) "\u201C${rule.patterns.first()}\u201D"
                else "Exact wording not confirmed yet. If it misses, use \u201CAlert me on this\u201D below.",
                alerts.isOn(rule)
            ) { alerts.setOn(rule, it) }
        }

        val custom = alerts.custom
        if (custom.isNotEmpty()) {
            c.addView(spaced(Ui.text(this, "Your alerts", 13f, Ui.TEXT, bold = true), 10))
            for (rule in custom) customAlertRow(c, alerts, rule)
        }

        c.addView(spaced(Ui.text(this, "Alert me on this", 13f, Ui.TEXT, bold = true), 10))
        val lines = CaptureLog.recentLines().take(12)
        if (lines.isEmpty()) {
            val t = Ui.text(this, "Lines the game shows will appear here once Auto-check has been on. Tap one to be alerted whenever it shows up.", 12f, Ui.MUTED)
            c.addView(t)
        } else {
            val t = Ui.text(this, "Tap a line it read to be alerted whenever it shows up again.", 12f, Ui.MUTED)
            c.addView(t)
            for (line in lines) {
                val chip = Ui.chip(this, line, false)
                chip.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
                chip.maxLines = 2
                chip.ellipsize = TextUtils.TruncateAt.END
                chip.setOnClickListener { addAlertDialog(alerts, line) }
                c.addView(spaced(chip, 4))
            }
        }
        c.addView(spaced(Ui.button(this, "Test the buzz and notification", false) {
            Alerter(this).fire("Test alert", "This is what an AFK alert looks like.")
        }, 10))
        return c
    }

    private fun customAlertRow(c: LinearLayout, alerts: AlertStore, rule: AlertRule) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(Ui.text(this, rule.label, 13f, Ui.TEXT, bold = true))
        texts.addView(Ui.text(this, "\u201C${rule.patterns.first()}\u201D", 11f, Ui.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val remove = Ui.chip(this, "Remove", false)
        remove.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6))
        remove.setOnClickListener {
            alerts.removeCustom(rule.id)
            refresh()
        }
        row.addView(remove)
        c.addView(spaced(row, 6))
    }

    /** Lets you trim the line down to the part that matters ("The cargo hold is full") before saving. */
    private fun addAlertDialog(alerts: AlertStore, line: String) {
        val input = EditText(this)
        input.setText(line)
        input.setSelection(input.text.length)
        input.setSingleLine(false)
        val pad = Ui.dp(this, 20)
        val frame = LinearLayout(this)
        frame.setPadding(pad, Ui.dp(this, 8), pad, 0)
        frame.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        android.app.AlertDialog.Builder(this)
            .setTitle("Alert me when this shows up")
            .setMessage("Trim it to the part that matters; small misreads are still caught.")
            .setView(frame)
            .setPositiveButton("Add alert") { _, _ ->
                val phrase = input.text.toString().trim()
                if (phrase.length >= 4) {
                    alerts.addCustom(phrase.take(40), phrase)
                    refresh()
                } else {
                    Toast.makeText(this, "That's too short to watch for.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A one-glance summary of the account, opening the full "Your account" screen. */
    private fun accountCard(): View {
        val c = card()
        c.addView(Ui.text(this, "Your account", 14f, Ui.TEXT, bold = true))
        val name = travel.username
        val advisor = Advisor(quests.filter { !it.isDiary }, travel.levels, travel.completedQuests, travel.startedQuests)
        if (name.isBlank()) {
            val sub = Ui.text(this, "Add your RuneScape name to see your levels, quests, diaries and what to do next.", 12f, Ui.TAN)
            sub.setPadding(0, Ui.dp(this, 2), 0, 0)
            c.addView(sub)
        } else {
            val levels = travel.levels
            val total = levels["Overall"] ?: levels.filterKeys { it != "Overall" }.values.sum().takeIf { it > 0 }
            val summary = buildString {
                append(name)
                advisor.combat?.let { append(" \u00B7 Combat $it") }
                total?.let { append(" \u00B7 Total $it") }
                append(" \u00B7 ${advisor.questPoints}/${advisor.maxQuestPoints} QP")
            }
            val sub = Ui.text(this, summary, 12f, Ui.TAN)
            sub.setPadding(0, Ui.dp(this, 2), 0, 0)
            c.addView(sub)
            advisor.ready().firstOrNull()?.let { next ->
                val n = Ui.text(this, "Next up: ${next.name}", 12f, Ui.TEXT)
                n.setPadding(0, Ui.dp(this, 2), 0, 0)
                c.addView(n)
            }
        }
        c.addView(spaced(Ui.button(this, if (name.isBlank()) "Set up your account" else "Open your account", name.isBlank()) {
            startActivity(Intent(this, AccountActivity::class.java))
        }, 10))
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
        const val KOFI_URL = "https://ko-fi.com/breadcrumbsqh"
        const val OFFICIAL_SITE = "https://ertas-del.github.io/OSRS-Quest-Helper-Android/"
        /** Wording required by Jagex's Fan Content Policy. */
        const val JAGEX_DISCLAIMER =
            "Created using intellectual property belonging to Jagex Limited under the terms of " +
                "Jagex's Fan Content Policy. This content is not endorsed by or affiliated with Jagex."
        const val OSRS_PACKAGE = "com.jagex.oldscape.android"
    }
}
