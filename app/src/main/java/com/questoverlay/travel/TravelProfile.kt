package com.questoverlay.travel

/** Ways of travelling the player can switch on or off. */
enum class TravelMode(val label: String, val types: Set<String>, val membersOnly: Boolean, val defaultOn: Boolean) {
    SPELLS("Teleport spells", setOf("SPELL"), false, true),
    HOME("Home teleport", setOf("HOME_TELEPORT"), false, true),
    ITEMS("Teleport jewellery & items", setOf("TELEPORT_ITEM", "QUETZAL_WHISTLE"), true, false),
    FAIRY_RINGS("Fairy rings", setOf("FAIRY_RING"), true, false),
    SPIRIT_TREES("Spirit trees", setOf("SPIRIT_TREE"), true, true),
    GLIDERS("Gnome gliders", setOf("GNOME_GLIDER"), true, true),
    CARPETS("Magic carpets", setOf("MAGIC_CARPET"), true, true),
    SHIPS("Ships, boats & canoes", setOf("SHIP", "BOAT", "CANOE"), false, true),
    CHARTER("Charter ships", setOf("CHARTER_SHIP"), true, true),
    MINECARTS("Minecarts", setOf("MINECART"), true, true),
    BALLOONS("Hot air balloons", setOf("HOT_AIR_BALLOON"), true, false),
    QUETZALS("Quetzals (Varlamore)", setOf("QUETZAL"), true, true),
    MUSHTREES("Magic mushtrees (Fossil Island)", setOf("MAGIC_MUSHTREE"), true, true),
    MINIGAMES("Minigame (grouping) teleports", setOf("MINIGAME"), true, false),
    SHORTCUTS("Agility shortcuts", setOf("AGILITY_SHORTCUT"), true, true),
    PORTALS("Portals & levers", setOf("PORTAL", "LEVER"), true, true),
    OBELISKS("Wilderness obelisks", setOf("OBELISK"), true, false);

    companion object {
        private val byType: Map<String, TravelMode> =
            entries.flatMap { m -> m.types.map { it to m } }.toMap()

        fun of(type: String): TravelMode? = byType[type]
    }
}

data class TravelProfile(
    val members: Boolean = true,
    /** 0 standard, 1 ancient, 2 lunar, 3 arceuus */
    val spellbook: Int = 0,
    val modes: Set<TravelMode> = TravelMode.entries.filter { it.defaultOn }.toSet(),
    /** Skill name (e.g. "Magic") to level. Empty means "unknown": nothing is filtered by level. */
    val levels: Map<String, Int> = emptyMap(),
    /** [QuestNames.canon] names of quests you have finished. Empty means "unknown": nothing is filtered. */
    val completedQuests: Set<String> = emptySet(),
    /** Use links that need diaries or unlocks we can't check. */
    val assumeUnlocks: Boolean = false,
    val avoidWilderness: Boolean = true
) {
    fun allows(link: Link): Boolean {
        // Doors, stairs and ladders are always part of walking, but can still be quest- or level-locked.
        if (link.type != "TRANSPORT") {
            val mode = TravelMode.of(link.type) ?: return false
            if (mode !in modes) return false
            if (!members && !freeToPlay(link)) return false
            if (link.spellbook >= 0 && link.spellbook != spellbook) return false
            if (link.needsUnlock && !assumeUnlocks && link.type !in IGNORE_UNLOCK) return false
        }
        if (levels.isNotEmpty()) {
            for ((skill, lvl) in link.skills) {
                val have = levels[skill] ?: continue
                if (have < lvl) return false
            }
        }
        if (completedQuests.isNotEmpty()) {
            for (q in link.quests) if (QuestNames.canon(q) !in completedQuests) return false
        }
        // Wilderness avoidance is decided per search (it's lifted when you start or end there).
        return true
    }

    private fun freeToPlay(link: Link): Boolean = when (link.type) {
        "SPELL" -> link.name in F2P_SPELLS
        "HOME_TELEPORT" -> link.spellbook <= 0
        "SHIP", "BOAT" -> link.name in F2P_BOATS
        else -> false
    }

    companion object {
        /** These carry cooldown or progress checks that are almost always satisfied. */
        private val IGNORE_UNLOCK = setOf("TRANSPORT", "HOME_TELEPORT", "MINIGAME", "MINECART")
        private val F2P_SPELLS = setOf("Varrock Teleport", "Lumbridge Teleport", "Falador Teleport")
        /** The Port Sarim - Karamja boats are the only free-to-play sailing routes. */
        private val F2P_BOATS = setOf("Musa Point", "Port Sarim")
    }
}

object Wilderness {
    fun contains(packed: Int): Boolean {
        val x = Packed.x(packed)
        val y = Packed.y(packed)
        // Surface Wilderness and the dungeons underneath it.
        if (x in 2944..3391 && y in 3525..3967) return true
        if (x in 2944..3391 && y in 9918..10367) return true
        return false
    }
}

/** Different data sources spell some quests differently; compare them in one canonical form. */
object QuestNames {
    private val NON_ALNUM = Regex("[^a-z0-9]")
    private val ALIASES = mapOf(
        "deserttreasurei" to "deserttreasure",
        "deserttreasureiithefallenempire" to "deserttreasureii",
        "perilousmoons" to "perilousmoon",
        "recipefordisasterkingawowogei" to "recipefordisasterfreeingkingawowogei",
        "recipefordisasterpiratepete" to "recipefordisasterfreeingpiratepete",
        "recipefordisasterevildave" to "recipefordisasterfreeingevildave",
        "recipefordisastersiramikvarze" to "recipefordisasterfreeingsiramikvarze",
        "recipefordisasterskrachuglogwee" to "recipefordisasterfreeingskrachuglogwee",
        "recipefordisasterwartfaceandbentnoze" to "recipefordisasterfreeingthegoblingenerals",
        "recipefordisasterlumbridgeguide" to "recipefordisasterfreeingthelumbridgeguide",
        "recipefordisastermountaindwarf" to "recipefordisasterfreeingthemountaindwarf"
    )

    fun canon(name: String): String {
        val n = NON_ALNUM.replace(name.lowercase(), "")
        return ALIASES[n] ?: n
    }
}
