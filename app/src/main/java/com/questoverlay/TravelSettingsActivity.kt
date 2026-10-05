package com.questoverlay

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.questoverlay.travel.AccountSync
import com.questoverlay.travel.TravelEngine
import com.questoverlay.travel.TravelMode
import com.questoverlay.travel.TravelProfile
import com.questoverlay.travel.TravelStore
import java.text.DateFormat
import java.util.Date

/** Everything the route planner needs to know about the player. */
class TravelSettingsActivity : Activity() {

    private lateinit var store: TravelStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var portalsOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TravelStore(this)

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

    override fun onPause() {
        super.onPause()
        // Let a running overlay re-plan with the new settings.
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
        }
    }

    private fun build() {
        content.removeAllViews()
        val pad = Ui.dp(this, 16)
        content.setPadding(pad, pad, pad, pad)

        content.addView(Ui.text(this, "Travel guide", 26f, Ui.GOLD, bold = true))
        val intro = Ui.text(
            this,
            "Routes use teleports, fairy rings, spirit trees, ships and more. Tell it what you can use and it picks the fastest way.",
            13f,
            Ui.TAN
        )
        intro.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10))
        content.addView(intro)

        content.addView(accountCard())
        content.addView(basicsCard())
        content.addView(modesCard())
        content.addView(houseCard())
        content.addView(rulesCard())

        val credit = Ui.text(
            this,
            "Transport data and walking map from the Shortest Path RuneLite plugin (BSD 2-Clause). " +
                "Levels come from the official hiscores; finished quests from WikiSync. Nothing reads the game.",
            11f,
            Ui.MUTED
        )
        credit.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8))
        content.addView(credit)
    }

    // ---------------------------------------------------------------- cards

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 14)
        c.setPadding(p, p, p, p)
        c.background = Ui.rounded(this, Ui.CARD_BG, 14, Ui.STROKE, 1)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 10)
        c.layoutParams = lp
        c.addView(Ui.text(this, title, 16f, Ui.GOLD, bold = true))
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

    private fun accountCard(): View {
        val c = card("Your account")
        note(c, "Optional. Your levels let the guide skip teleports and shortcuts you can't use yet.")

        val name = EditText(this)
        name.hint = "RuneScape name"
        name.setText(store.username)
        name.setSingleLine(true)
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        name.imeOptions = EditorInfo.IME_ACTION_DONE
        name.setTextColor(Ui.TEXT)
        name.setHintTextColor(Ui.MUTED)
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        name.typeface = Ui.typeface(this, bold = false)
        val p = Ui.dp(this, 12)
        name.setPadding(p, p, p, p)
        name.background = BevelDrawable(this, Ui.STONE_DARK, Ui.STONE_DARK, Ui.STONE_LIGHT, Ui.STROKE, raised = false)
        c.addView(spaced(name, 6))

        status = Ui.text(this, statusText(), 12f, Ui.TAN)
        status.setPadding(0, Ui.dp(this, 8), 0, 0)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val levels = Ui.button(this, "Load levels", true) {
            val rsn = name.text.toString().trim()
            if (rsn.isEmpty()) {
                status.text = "Type your RuneScape name first."
                return@button
            }
            store.username = rsn
            status.text = "Asking the hiscores…"
            AccountSync.fetchLevels(rsn) { outcome ->
                if (isDestroyed) return@fetchLevels
                when (outcome) {
                    is AccountSync.Outcome.Ok -> {
                        store.levels = outcome.value
                        store.levelsUpdated = System.currentTimeMillis()
                        status.text = statusText()
                    }
                    is AccountSync.Outcome.Error -> status.text = outcome.message
                }
            }
        }
        val quests = Ui.button(this, "Import quests", false) {
            val rsn = name.text.toString().trim()
            if (rsn.isEmpty()) {
                status.text = "Type your RuneScape name first."
                return@button
            }
            store.username = rsn
            status.text = "Asking WikiSync…"
            AccountSync.fetchWikiSync(rsn) { outcome ->
                if (isDestroyed) return@fetchWikiSync
                when (outcome) {
                    is AccountSync.Outcome.Ok -> {
                        store.applyWikiSync(outcome.value)
                        build()
                    }
                    is AccountSync.Outcome.Error -> status.text = outcome.message
                }
            }
        }
        row.addView(levels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val qlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        qlp.leftMargin = Ui.dp(this, 8)
        row.addView(quests, qlp)
        c.addView(spaced(row, 10))
        c.addView(status)
        note(c, "Import quests works if you've ever played with RuneLite's WikiSync plugin. Otherwise mark quests done on the quest list.")
        return c
    }

    private fun statusText(): String {
        val lv = store.levels
        val parts = ArrayList<String>()
        if (lv.isNotEmpty()) {
            val time = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(store.levelsUpdated))
            parts.add("Levels loaded ($time): Magic ${lv["Magic"] ?: "?"}, Agility ${lv["Agility"] ?: "?"}")
        } else {
            parts.add("No levels loaded: every level requirement is assumed met.")
        }
        val done = store.completedQuests.size
        parts.add(if (store.checkQuests) "$done quests marked as done." else "Quest requirements aren't checked yet.")
        return parts.joinToString("\n")
    }

    private fun switchRow(c: LinearLayout, label: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        val sw = Switch(this)
        sw.text = label
        sw.setTextColor(if (enabled) Ui.TEXT else Ui.MUTED)
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        sw.typeface = Ui.typeface(this, bold = false)
        sw.setShadowLayer(0.01f, Ui.dpf(this, 1f), Ui.dpf(this, 1f), Ui.DARK_TEXT)
        sw.isChecked = checked
        sw.isEnabled = enabled
        sw.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Ui.TAN, Ui.MUTED)
        )
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x99FF981F.toInt(), Ui.STONE_DARK)
        )
        sw.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
        sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        c.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (sub != null) {
            val t = Ui.text(this, sub, 11f, Ui.MUTED)
            t.setPadding(0, 0, Ui.dp(this, 48), Ui.dp(this, 4))
            c.addView(t)
        }
    }

    private fun basicsCard(): View {
        val c = card("Basics")
        switchRow(c, "Members", "Off limits routes to free-to-play spells and boats.", store.members) {
            store.members = it
            build()
        }

        c.addView(spaced(Ui.text(this, "Spellbook", 13f, Ui.TEXT, bold = true), 8))
        val books = listOf("Standard", "Ancient", "Lunar", "Arceuus")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, b) in books.withIndex()) {
            val active = store.spellbook == i
            val chip = Ui.chip(this, b, active)
            chip.setOnClickListener {
                store.spellbook = i
                build()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = Ui.dp(this, 4)
            row.addView(chip, lp)
        }
        c.addView(spaced(row, 6))

        c.addView(spaced(Ui.text(this, "Plan the first step from", 13f, Ui.TEXT, bold = true), 12))
        note(c, "Later steps start from where the step before them happened.")
        val places = TravelEngine.places(this)
        val spinner = Spinner(this)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, places.map { it.name }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Ui.TEXT)
                v.typeface = Ui.typeface(context, bold = false)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(places.indexOfFirst { it.name == store.startPlace }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                store.startPlace = places[position].name
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        c.addView(spaced(spinner, 6))
        return c
    }

    private fun modesCard(): View {
        val c = card("Ways to travel")
        note(c, "Switch off anything you haven't unlocked or don't carry.")
        for (mode in TravelMode.entries) {
            val usable = store.members || !mode.membersOnly
            switchRow(c, mode.label + if (!usable) " (members)" else "", hintFor(mode), store.isModeOn(mode), usable) {
                store.setMode(mode, it)
            }
        }
        return c
    }

    private fun hintFor(mode: TravelMode): String? = when (mode) {
        TravelMode.ITEMS -> "Rings of dueling, games necklaces, glories, tablets and so on. Only switch on if you carry them."
        TravelMode.FAIRY_RINGS -> "Needs Fairytale II started and a dramen or lunar staff."
        TravelMode.SPIRIT_TREES -> "Needs Tree Gnome Village."
        TravelMode.GLIDERS -> "Needs The Grand Tree."
        TravelMode.BALLOONS -> "Each balloon has to be unlocked with Enlightened Journey."
        TravelMode.MINIGAMES -> "Free teleports with a 20-minute cooldown."
        TravelMode.OBELISKS -> "Deep Wilderness. Risky."
        else -> null
    }

    // ---------------------------------------------------------------- your house

    /**
     * A grid of chips. Tapping one calls [onTap]; afterwards every chip is redrawn from [selected],
     * so the same grid works for pick-one and pick-many without rebuilding the page.
     */
    private fun chipGrid(
        items: List<Pair<String, String>>,
        columns: Int,
        selected: () -> Set<String>,
        onTap: (String) -> Unit
    ): View {
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val chips = ArrayList<Pair<String, TextView>>()
        fun refresh() {
            val on = selected()
            for ((key, chip) in chips) {
                val active = key in on
                chip.setTextColor(if (active) Ui.TAN else Ui.GOLD)
                chip.background = Ui.stoneButton(this, down = active)
            }
        }
        for (rowItems in items.chunked(columns)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (i in 0 until columns) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (i > 0) lp.leftMargin = Ui.dp(this, 4)
                val item = rowItems.getOrNull(i)
                if (item == null) {
                    row.addView(View(this), lp) // keep the columns lined up on a short last row
                    continue
                }
                val chip = Ui.chip(this, item.second, false)
                val hp = Ui.dp(this, 4)
                chip.setPadding(hp, chip.paddingTop, hp, chip.paddingBottom)
                chip.setOnClickListener {
                    onTap(item.first)
                    refresh()
                }
                chips.add(item.first to chip)
                row.addView(chip, lp)
            }
            val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (grid.childCount > 0) rlp.topMargin = Ui.dp(this, 4)
            grid.addView(row, rlp)
        }
        refresh()
        return grid
    }

    private fun heading(c: LinearLayout, text: String, topDp: Int = 12) {
        c.addView(spaced(Ui.text(this, text, 13f, Ui.TEXT, bold = true), topDp))
    }

    private fun portalLabel(name: String): String =
        if (name.startsWith("Respawn Portal (")) "Respawn: " + name.removePrefix("Respawn Portal (").removeSuffix(")")
        else name.removeSuffix(" Portal")

    private fun houseCard(): View {
        val c = card("Your house")
        note(c, "Routes can go through your player-owned house: teleport in, then use its portals, jewellery box or mounted items. The app can't see your house, so tick what you've built.")
        switchRow(c, "I have a player-owned house", null, store.houseOn) {
            store.houseOn = it
            build()
        }
        if (!store.houseOn) return c
        if (!store.members) {
            note(c, "Houses are members-only, so they're skipped while Members is off.")
        }

        heading(c, "House location")
        note(c, "Where your house portal is. Routes can walk out of it, or in through it.")
        c.addView(spaced(chipGrid(
            TravelProfile.House.LOCATIONS.map { it.first.toString() to it.second },
            3,
            { setOf(store.houseLocation.toString()) }
        ) { store.houseLocation = it.toInt() }, 6))

        heading(c, "Getting home")
        switchRow(c, "Teleport to House tablets", "Only if you carry them.", store.houseTablets) { store.houseTablets = it }
        switchRow(c, "Construction cape", "Teleports you home for free.", store.houseConCape) { store.houseConCape = it }
        note(c, "The Teleport to House spell is used whenever teleport spells are on and you're on the standard spellbook.")

        heading(c, "Portals and portal nexus")
        note(c, "Tick every destination in your portal chamber or nexus.")
        val names = TravelEngine.housePortalNames(this)
        val chosen = store.housePortals.count { it in names }
        val toggle = Ui.button(this, if (portalsOpen) "Hide portals ($chosen ticked)" else "Choose portals ($chosen ticked)", false) {
            portalsOpen = !portalsOpen
            build()
        }
        c.addView(spaced(toggle, 6))
        if (portalsOpen) {
            if (names.isEmpty()) {
                note(c, "The portal list couldn't be read.")
            } else {
                c.addView(spaced(chipGrid(names.map { it to portalLabel(it) }, 2, { store.housePortals }) { name ->
                    val s = store.housePortals.toMutableSet()
                    if (!s.add(name)) s.remove(name)
                    store.housePortals = s
                }, 6))
                note(c, "Respawn portals send you to your respawn point; tick only the one you've set.")
            }
        }

        heading(c, "Jewellery box")
        val tiers = listOf("None", "Basic", "Fancy", "Ornate")
        c.addView(spaced(chipGrid(
            tiers.mapIndexed { i, t -> i.toString() to t },
            4,
            { setOf(store.houseJewelleryBox.toString()) }
        ) { store.houseJewelleryBox = it.toInt() }, 6))
        note(c, "Basic: dueling and games teleports. Fancy adds combat and skills necklaces. Ornate adds glory and wealth rings.")

        heading(c, "Mounted items")
        for ((key, label) in TravelProfile.House.MOUNTED) {
            switchRow(c, label, null, key in store.houseMounted) { on ->
                val s = store.houseMounted.toMutableSet()
                if (on) s.add(key) else s.remove(key)
                store.houseMounted = s
            }
        }

        heading(c, "Garden")
        switchRow(c, "Fairy ring", "Also needs fairy rings switched on above.", store.houseFairyRing) { store.houseFairyRing = it }
        switchRow(c, "Spirit tree", "Also needs spirit trees switched on above.", store.houseSpiritTree) { store.houseSpiritTree = it }
        return c
    }

    private fun rulesCard(): View {
        val c = card("Rules")
        switchRow(
            c,
            "Check quest requirements",
            "Only use routes your finished quests allow. Mark quests done on the quest list, or import them above.",
            store.checkQuests
        ) {
            store.checkQuests = it
            status.text = statusText()
        }
        switchRow(
            c,
            "Use diary and unlock teleports",
            "Some teleports need an achievement diary or an unlock we can't check. Leave off to be safe.",
            store.assumeUnlocks
        ) { store.assumeUnlocks = it }
        switchRow(c, "Avoid the Wilderness", null, store.avoidWilderness) { store.avoidWilderness = it }
        return c
    }
}
