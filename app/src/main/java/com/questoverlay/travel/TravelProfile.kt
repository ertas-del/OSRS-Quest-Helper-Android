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
    val avoidWilderness: Boolean = true,
    /** The player's own house, or null if they haven't got one (or don't want it used). */
    val house: House? = null
) {
    /**
     * What's built in the player-owned house. The app can't see the house, so the player ticks these.
     * [location] is the game's house-location number (see [LOCATIONS]).
     */
    data class House(
        val location: Int = 1,
        val portals: Set<String> = emptySet(),
        /** 0 none, 1 basic, 2 fancy, 3 ornate */
        val jewelleryBox: Int = 0,
        /** glory, mythical, xeric, digsite */
        val mounted: Set<String> = emptySet(),
        val fairyRing: Boolean = false,
        val spiritTree: Boolean = false,
        /** Carries Teleport to House tablets. */
        val tablets: Boolean = true,
        /** Has a Construction cape (or the max cape) on them. */
        val conCape: Boolean = false
    ) {
        fun allows(tag: String): Boolean = when {
            tag == "arrive" -> true
            tag.startsWith("outside:") -> tag.removePrefix("outside:").toIntOrNull() == location
            tag.startsWith("home:") -> tag.removePrefix("home:").toIntOrNull() == location
            tag.startsWith("portal:") -> tag.removePrefix("portal:") in portals
            tag.startsWith("box:") -> jewelleryBox >= BOX_TIERS.indexOf(tag.removePrefix("box:")).coerceAtLeast(1)
            tag.startsWith("mount:") -> tag.removePrefix("mount:") in mounted
            tag == "fairy" -> fairyRing
            tag == "spirit" -> spiritTree
            else -> false
        }

        companion object {
            val BOX_TIERS = listOf("none", "basic", "fancy", "ornate")

            /** House locations, by the game's own number for them, with where the house portal stands. */
            val LOCATIONS = listOf(
                1 to "Rimmington", 2 to "Taverley", 3 to "Pollnivneach", 4 to "Rellekka",
                5 to "Brimhaven", 6 to "Yanille", 8 to "Hosidius", 9 to "Prifddinas", 13 to "Aldarin"
            )
            val MOUNTED = listOf(
                "glory" to "Amulet of glory", "mythical" to "Mythical cape",
                "xeric" to "Xeric's talisman", "digsite" to "Digsite pendant"
            )
        }
    }

    fun allows(link: Link): Boolean {
        if (link.poh.isNotEmpty()) {
            val h = house ?: return false
            if (!members) return false
            if (!h.allows(link.poh)) return false
            // The house fairy ring and spirit tree still need fairy rings / spirit trees in general.
            if (link.poh == "fairy" && TravelMode.FAIRY_RINGS !in modes) return false
            if (link.poh == "spirit" && TravelMode.SPIRIT_TREES !in modes) return false
            if (link.poh == "arrive" || link.poh.startsWith("outside:")) {
                val ok = when {
                    link.type == "SPELL" -> TravelMode.SPELLS in modes
                    link.name.startsWith("Construction cape") -> h.conCape
                    else -> h.tablets
                }
                if (!ok) return false
            }
            // Getting home by spell still needs the right spellbook and level.
            if (link.spellbook >= 0 && link.spellbook != spellbook) return false
            if (link.needsUnlock && !assumeUnlocks) return false
            return levelsAndQuestsOk(link)
        }
        // Doors, stairs and ladders are always part of walking, but can still be quest- or level-locked.
        if (link.type != "TRANSPORT") {
            val mode = TravelMode.of(link.type) ?: return false
            if (mode !in modes) return false
            if (!members && !freeToPlay(link)) return false
            if (link.spellbook >= 0 && link.spellbook != spellbook) return false
            if (link.needsUnlock && !assumeUnlocks && link.type !in IGNORE_UNLOCK) return false
        }
        // Wilderness avoidance is decided per search (it's lifted when you start or end there).
        return levelsAndQuestsOk(link)
    }

    private fun levelsAndQuestsOk(link: Link): Boolean {
        if (levels.isNotEmpty()) {
            for ((skill, lvl) in link.skills) {
                val have = levels[skill] ?: continue
                if (have < lvl) return false
            }
        }
        if (completedQuests.isNotEmpty()) {
            for (q in link.quests) if (QuestNames.canon(q) !in completedQuests) return false
        }
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
