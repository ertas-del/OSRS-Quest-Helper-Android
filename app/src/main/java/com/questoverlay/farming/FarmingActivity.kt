package com.questoverlay.farming

import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.LinearLayout
import com.questoverlay.ScreenActivity
import com.questoverlay.Ui
import com.questoverlay.travel.TravelStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Farming timers: what's growing, when it's ready, and a button to start one by hand. */
class FarmingActivity : ScreenActivity() {
    private lateinit var store: FarmingStore
    private val tick = object : Runnable {
        override fun run() {
            rebuild()
            main.postDelayed(this, 30_000)
        }
    }

    override fun onResume() {
        store = FarmingStore(this)
        store.tidy()
        super.onResume()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 30_000)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    override fun build() {
        title(
            "Farming timers",
            "With 👁 Auto-check on, a timer starts by itself when the game says “You plant…”. " +
                "You get a notification when it's ready. Start one by hand below for anything it missed."
        )
        growingCard()
        startCard()
        settingsCard()
        val credit = Ui.text(this, "Growth times from RuneLite's time-tracking plugin (BSD 2-Clause). " +
            "Crops grow on fixed farming ticks, so a timer can be a few minutes off if your account's ticks are offset.", 11f, Ui.MUTED)
        credit.setPadding(0, Ui.dp(this, 14), 0, 0)
        content.addView(credit)
    }

    private fun growingCard() {
        val c = card("Growing")
        val now = System.currentTimeMillis()
        val timers = store.timers.sortedBy { it.readyAt }
        if (timers.isEmpty()) {
            note(c, "Nothing growing yet.")
            return
        }
        for (t in timers) {
            val crop = Farming.crop(t.crop)
            val left = t.readyAt - now
            val sub = buildString {
                if (left <= 0) append("Ready ✓") else append("Ready in ${duration(left)} · ${clock(t.readyAt)}")
                if (crop?.patch == Patch.HESPORI) {
                    append("\nHespori grows in 3 stages of 640 minutes on fixed farming ticks: ready 21–32 h after planting. It can't get diseased.")
                } else if (crop?.secateursHelp == true && left > 0) {
                    append("\nBring magic secateurs (+10%).")
                }
            }
            rowWithButton(c, t.label, sub, "Remove", if (left <= 0) Ui.GREEN else Ui.TEXT) {
                store.remove(t.id)
                rebuild()
            }
        }
    }

    private fun startCard() {
        val c = card("Start a timer")
        note(c, "Pick the patch, then the crop.")
        val patches = Farming.CROPS.map { it.patch }.distinct()
        var row: LinearLayout? = null
        for ((i, p) in patches.withIndex()) {
            if (i % 3 == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                c.addView(spaced(row, 4))
            }
            val chip = Ui.chip(this, p.label.removeSuffix(" patch").replaceFirstChar { it.uppercase() }, false)
            chip.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8))
            chip.setOnClickListener { pickCrop(p) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i % 3 > 0) lp.leftMargin = Ui.dp(this, 4)
            row!!.addView(chip, lp)
        }
        // Keep the last row's chips the same width as the others.
        val r = row
        if (r != null) {
            while (r.childCount < 3) {
                val lp = LinearLayout.LayoutParams(0, 1, 1f)
                lp.leftMargin = Ui.dp(this, 4)
                r.addView(android.view.View(this), lp)
            }
        }
    }

    private fun pickCrop(p: Patch) {
        val crops = Farming.CROPS.filter { it.patch == p }
        val labels = crops.map { "${it.name}  (${duration(it.maxMinutes * 60_000L)} max)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("What did you plant?")
            .setItems(labels) { _, which ->
                store.plant(crops[which])
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun settingsCard() {
        val c = card("Settings")
        switchRow(c, "Start timers from chat", "Needs 👁 Auto-check on while you plant.", store.autoStart) { store.autoStart = it }
        switchRow(
            c, "I always carry magic secateurs",
            "Turns off the “Check your magic secateurs” reminders (text and voice).", store.alwaysSecateurs
        ) { store.alwaysSecateurs = it }
        val travel = TravelStore(this)
        if (travel.completedQuests.isNotEmpty() && !travel.isQuestDone("Fairytale I - Growing Pains")) {
            note(c, "You haven't done Fairytale I – Growing Pains yet. It gives magic secateurs: +10% herbs, allotments, hops and more.", Ui.TAN)
        }
    }

    private fun duration(ms: Long): String {
        val mins = (ms + 59_999) / 60_000
        return when {
            mins < 60 -> "$mins min"
            mins < 48 * 60 -> "${mins / 60} h ${mins % 60} min"
            else -> "${mins / (24 * 60)} days"
        }
    }

    private fun clock(at: Long): String {
        val sameDay = (at - System.currentTimeMillis()) < 12 * 3600_000L
        return SimpleDateFormat(if (sameDay) "h:mm a" else "EEE h:mm a", Locale.getDefault()).format(Date(at))
    }
}
