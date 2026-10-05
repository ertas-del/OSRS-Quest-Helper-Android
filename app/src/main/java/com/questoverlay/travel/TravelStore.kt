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

    /** Quests WikiSync says you've started but not finished, in [QuestNames.canon] form. */
    var startedQuests: Set<String>
        get() = prefs.getStringSet("quests_started", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("quests_started", v.map { QuestNames.canon(it) }.toSet()).apply()

    /** Diary progress from WikiSync. */
    var diaries: List<AccountSync.DiaryTier>
        get() {
            val raw = prefs.getString("diaries", "") ?: ""
            if (raw.isBlank()) return emptyList()
            return raw.split('\n').mapNotNull { line ->
                val p = line.split('|')
                if (p.size != 5) null
                else AccountSync.DiaryTier(p[0], p[1], p[2] == "1", p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0)
            }
        }
        set(v) = prefs.edit().putString(
            "diaries",
            v.joinToString("\n") { "${it.region}|${it.tier}|${if (it.complete) 1 else 0}|${it.done}|${it.total}" }
        ).apply()

    /** WikiSync progress for one diary tier ("Kourend & Kebos", "Hard"), if synced. */
    fun diaryTier(region: String, tier: String): AccountSync.DiaryTier? {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val r = key(region)
        val t = key(tier)
        return diaries.firstOrNull { key(it.region) == r && key(it.tier) == t }
    }

    var wikiSyncUpdated: Long
        get() = prefs.getLong("wikisync_time", 0L)
        set(v) = prefs.edit().putLong("wikisync_time", v).apply()

    /**
     * Saves a WikiSync import. Finished quests are added to what you've marked done in the app
     * (never removed), and levels are only used when the hiscores haven't been loaded.
     */
    fun applyWikiSync(data: AccountSync.WikiSyncData) {
        completedQuests = completedQuests + data.finished.map { QuestNames.canon(it) }
        startedQuests = data.started
        diaries = data.diaries
        checkQuests = true
        if (data.levels.isNotEmpty() && levels.keys.none { it != "Overall" }) levels = data.levels
        wikiSyncUpdated = System.currentTimeMillis()
    }

    fun isQuestDone(name: String): Boolean = completedQuests.contains(QuestNames.canon(name))

    fun setQuestDone(name: String, done: Boolean) {
        val s = completedQuests.toMutableSet()
        if (done) s.add(QuestNames.canon(name)) else s.remove(QuestNames.canon(name))
        completedQuests = s
    }

    // ---------------------------------------------------------------- player-owned house

    var houseOn: Boolean
        get() = prefs.getBoolean("house_on", false)
        set(v) = prefs.edit().putBoolean("house_on", v).apply()

    /** The game's house-location number (see [TravelProfile.House.LOCATIONS]). */
    var houseLocation: Int
        get() = prefs.getInt("house_loc", 1)
        set(v) = prefs.edit().putInt("house_loc", v).apply()

    var housePortals: Set<String>
        get() = prefs.getStringSet("house_portals", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("house_portals", v.toSet()).apply()

    /** 0 none, 1 basic, 2 fancy, 3 ornate */
    var houseJewelleryBox: Int
        get() = prefs.getInt("house_box", 0)
        set(v) = prefs.edit().putInt("house_box", v.coerceIn(0, 3)).apply()

    var houseMounted: Set<String>
        get() = prefs.getStringSet("house_mounts", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("house_mounts", v.toSet()).apply()

    var houseFairyRing: Boolean
        get() = prefs.getBoolean("house_fairy", false)
        set(v) = prefs.edit().putBoolean("house_fairy", v).apply()

    var houseSpiritTree: Boolean
        get() = prefs.getBoolean("house_spirit", false)
        set(v) = prefs.edit().putBoolean("house_spirit", v).apply()

    var houseTablets: Boolean
        get() = prefs.getBoolean("house_tabs", true)
        set(v) = prefs.edit().putBoolean("house_tabs", v).apply()

    var houseConCape: Boolean
        get() = prefs.getBoolean("house_cape", false)
        set(v) = prefs.edit().putBoolean("house_cape", v).apply()

    fun house(): TravelProfile.House? = if (!houseOn) null else TravelProfile.House(
        location = houseLocation,
        portals = housePortals,
        jewelleryBox = houseJewelleryBox,
        mounted = houseMounted,
        fairyRing = houseFairyRing,
        spiritTree = houseSpiritTree,
        tablets = houseTablets,
        conCape = houseConCape
    )

    fun profile(): TravelProfile = TravelProfile(
        members = members,
        spellbook = spellbook,
        modes = TravelMode.entries.filter { isModeOn(it) }.toSet(),
        levels = levels,
        completedQuests = if (checkQuests) completedQuests.ifEmpty { setOf("\u0000none") } else emptySet(),
        assumeUnlocks = assumeUnlocks,
        avoidWilderness = avoidWilderness,
        house = house()
    )

    fun startTile(context: Context): Int {
        val places = TravelEngine.places(context)
        return (places.firstOrNull { it.name == startPlace } ?: places.first()).tile
    }
}
