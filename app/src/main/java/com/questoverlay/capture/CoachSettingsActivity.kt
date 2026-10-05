package com.questoverlay.capture

import android.content.Intent
import com.questoverlay.ScreenActivity
import com.questoverlay.Ui
import com.questoverlay.farming.FarmingActivity
import com.questoverlay.location.WhereActivity
import com.questoverlay.sailing.SailingActivity
import com.questoverlay.slayer.SlayerActivity
import com.questoverlay.travel.TravelStore

/** Everything the coach does besides quests: vitals warnings and links to the other tools. */
class CoachSettingsActivity : ScreenActivity() {

    override fun build() {
        title("Coach", "These work while 👁 Auto-check is on. Voice follows the 🔊 / 🔇 button on the card.")
        val vs = VitalsStore(this)
        val levels = TravelStore(this).levels
        val c = card("HP, prayer and run warnings")
        note(c, "Reads the numbers on the orbs next to the minimap. A warning shows on the card and is spoken.")
        switchRow(c, "Warn me", null, vs.enabled) {
            vs.enabled = it
            rebuild()
        }
        if (vs.enabled) {
            val maxHp = levels["Hitpoints"]
            sliderRow(c, vs.hpPercent, 10, 90, { "Low HP below $it%" + (maxHp?.let { m -> " (${m * it / 100} of $m)" } ?: "") }) { vs.hpPercent = it }
            sliderRow(c, vs.prayerPoints, 0, 50, { if (it == 0) "Prayer: off" else "Low prayer at $it points or less" }) { vs.prayerPoints = it }
            sliderRow(c, vs.runPercent, 0, 50, { if (it == 0) "Run energy: off" else "Low run energy below $it%" }) { vs.runPercent = it }
            if (maxHp == null) note(c, "Add your account on the account screen so it knows your max HP (it assumes 99 until then).", Ui.TAN)
        }

        val tools = card("Tools")
        tools.addView(spaced(Ui.button(this, "Farming timers", false) { startActivity(Intent(this, FarmingActivity::class.java)) }, 4))
        tools.addView(spaced(Ui.button(this, "Kill counts & pets", false) { startActivity(Intent(this, PetsActivity::class.java)) }, 8))
        tools.addView(spaced(Ui.button(this, "Where am I (minimap)", false) { startActivity(Intent(this, WhereActivity::class.java)) }, 8))
        tools.addView(spaced(Ui.button(this, "Sailing", false) { startActivity(Intent(this, SailingActivity::class.java)) }, 8))
        tools.addView(spaced(Ui.button(this, "Slayer", false) { startActivity(Intent(this, SlayerActivity::class.java)) }, 8))
    }
}
