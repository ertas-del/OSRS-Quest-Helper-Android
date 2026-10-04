package com.questoverlay.travel

import android.content.Context
import android.content.SharedPreferences

/** Saves the travel settings and what we know about the player's account. */
class TravelStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("travel", Context.MODE_PRIVATE)

    var username: String
        get() = prefs.getString("rsn", "") ?: ""
        set(v) = prefs.edit().putString("rsn", v.trim()).apply()

    var members: Boolean
        get() = prefs.getBoolean("members", true)
        set(v) = prefs.edit().putBoolean("members", v).apply()

    var spellbook: Int
        get() = prefs.getInt("spellbook", 0)
        set(v) = prefs.edit().putInt("spellbook", v.coerceIn(0, 3)).apply()

    var assumeUnlocks: Boolean
        get() = prefs.getBoolean("assume_unlocks", false)
        set(v) = prefs.edit().putBoolean("assume_unlocks", v).apply()

    var avoidWilderness: Boolean
        get() = prefs.getBoolean("avoid_wild", true)
        set(v) = prefs.edit().putBoolean("avoid_wild", v).apply()

    /** Only filter by quests once the player has told us which ones they've done. */
    var checkQuests: Boolean
        get() = prefs.getBoolean("check_quests", false)
        set(v) = prefs.edit().putBoolean("check_quests", v).apply()

    /** Where the first step of a quest is planned from. */
    var startPlace: String
        get() = prefs.getString("start_place", "Lumbridge (spawn)") ?: "Lumbridge (spawn)"
        set(v) = prefs.edit().putString("start_place", v).apply()

    fun isModeOn(mode: TravelMode): Boolean = prefs.getBoolean("mode_${mode.name}", mode.defaultOn)

    fun setMode(mode: TravelMode, on: Boolean) {
        prefs.edit().putBoolean("mode_${mode.name}", on).apply()
    }

    var levels: Map<String, Int>
        get() {
            val raw = prefs.getString("levels", "") ?: ""
            if (raw.isBlank()) return emptyMap()
            return raw.split(';').mapNotNull {
                val p = it.split(':')
                if (p.size == 2) p[1].toIntOrNull()?.let { lvl -> p[0] to lvl } else null
            }.toMap()
        }
        set(v) = prefs.edit().putString("levels", v.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()

    var levelsUpdated: Long
        get() = prefs.getLong("levels_time", 0L)
        set(v) = prefs.edit().putLong("levels_time", v).apply()

    /** Finished quests, stored in [QuestNames.canon] form so every data source agrees. */
    var completedQuests: Set<String>
        get() = prefs.getStringSet("quests_done_v2", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("quests_done_v2", v.map { QuestNames.canon(it) }.toSet()).apply()

    fun isQuestDone(name: String): Boolean = completedQuests.contains(QuestNames.canon(name))

    fun setQuestDone(name: String, done: Boolean) {
        val s = completedQuests.toMutableSet()
        if (done) s.add(QuestNames.canon(name)) else s.remove(QuestNames.canon(name))
        completedQuests = s
    }

    fun profile(): TravelProfile = TravelProfile(
        members = members,
        spellbook = spellbook,
        modes = TravelMode.entries.filter { isModeOn(it) }.toSet(),
        levels = levels,
        completedQuests = if (checkQuests) completedQuests.ifEmpty { setOf("\u0000none") } else emptySet(),
        assumeUnlocks = assumeUnlocks,
        avoidWilderness = avoidWilderness
    )

    fun startTile(context: Context): Int {
        val places = TravelEngine.places(context)
        return (places.firstOrNull { it.name == startPlace } ?: places.first()).tile
    }
}
