package com.questoverlay.capture

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.questoverlay.ScreenActivity
import com.questoverlay.Ui
import com.questoverlay.travel.AccountSync
import com.questoverlay.travel.TravelStore
import kotlin.math.ceil
import kotlin.math.ln

/** Boss kill counts (from chat and the hiscores) and your odds of having had each pet by now. */
class PetsActivity : ScreenActivity() {
    private lateinit var store: KillCountStore
    private var loading = false

    override fun onResume() {
        store = KillCountStore(this)
        super.onResume()
    }

    override fun build() {
        title(
            "Kill counts & pets",
            "Counts come from the game's “Your Vorkath kill count is…” messages while 👁 is on, " +
                "and from the hiscores. Odds are 1 − (1 − 1/rate)^kills: how likely you were to have had the pet by now."
        )
        val load = card()
        val name = TravelStore(this).username
        load.addView(Ui.button(this, if (loading) "Loading…" else "Load kill counts from the hiscores", name.isNotBlank() && !loading) {
            if (name.isBlank()) {
                Toast.makeText(this, "Add your RuneScape name on the account screen first.", Toast.LENGTH_LONG).show()
                return@button
            }
            loading = true
            rebuild()
            AccountSync.fetchBossCounts(name) { outcome ->
                loading = false
                when (outcome) {
                    is AccountSync.Outcome.Ok -> {
                        store.merge(outcome.value)
                        Toast.makeText(this, "Loaded ${outcome.value.size} boss counts.", Toast.LENGTH_SHORT).show()
                    }
                    is AccountSync.Outcome.Error -> Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                }
                rebuild()
            }
        })
        if (name.isBlank()) note(load, "Add your RuneScape name on the account screen to load counts from the hiscores.")

        val counts = store.counts
        val got = store.gotPets
        val withPet = Pets.ALL.map { it to (counts[it.boss] ?: 0) }.sortedByDescending { it.second }
        val c = card("Pets")
        note(c, "Tap a boss to type its kill count; tap “Got it” when the pet drops.")
        for ((pet, kc) in withPet) {
            val have = pet.pet in got
            val half = ceil(ln(0.5) / ln(1.0 - 1.0 / pet.rate)).toInt()
            val sub = buildString {
                append("${pet.pet} · 1/${pet.rate}")
                if (pet.note.isNotBlank()) append(" (${pet.note})")
                append("\n")
                if (have) append("Got it ✓")
                else if (kc > 0) append("$kc kills · ${Pets.percent(pet.chanceBy(kc))} chance by now · half of players have it by $half")
                else append("No kills yet · half of players have it by $half kills")
            }
            rowWithButton(c, pet.boss, sub, if (have) "Undo" else "Got it", if (have) Ui.GREEN else Ui.TEXT) {
                store.gotPets = if (have) got - pet.pet else got + pet.pet
                rebuild()
            }
            c.getChildAt(c.childCount - 1).setOnClickListener { editCount(pet.boss, kc) }
        }

        val others = counts.filterKeys { k -> Pets.ALL.none { it.boss == k } }
        if (others.isNotEmpty()) {
            val o = card("Other kill counts")
            for ((boss, kc) in others.entries.sortedByDescending { it.value }) note(o, "$boss: $kc", Ui.TEXT, 13f)
        }
        val credit = Ui.text(this, "Pet rates are the base rates from the OSRS Wiki (mid-2026); some change with team size or tasks.", 11f, Ui.MUTED)
        credit.setPadding(0, Ui.dp(this, 14), 0, 0)
        content.addView(credit)
    }

    private fun editCount(boss: String, current: Int) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        if (current > 0) input.setText(current.toString())
        AlertDialog.Builder(this)
            .setTitle("$boss kill count")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                store.set(boss, input.text.toString().toIntOrNull() ?: 0)
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
