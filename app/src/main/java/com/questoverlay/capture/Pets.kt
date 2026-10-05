package com.questoverlay.capture

import kotlin.math.pow

/**
 * A boss pet and its base drop rate (1 in [rate] per kill or completion), from the OSRS Wiki as of
 * mid-2026. [names] are the boss names the game uses in its kill-count message.
 */
data class Pet(val pet: String, val boss: String, val rate: Int, val names: List<String>, val note: String = "") {
    /** Chance of having had the pet drop by [kc] kills: 1 − (1 − 1/rate)^kc. */
    fun chanceBy(kc: Int): Double = if (kc <= 0) 0.0 else 1.0 - (1.0 - 1.0 / rate).pow(kc)
}

object Pets {
    val ALL: List<Pet> = listOf(
        Pet("Vorki", "Vorkath", 3000, listOf("vorkath")),
        Pet("Pet snakeling", "Zulrah", 4000, listOf("zulrah")),
        Pet("Pet general graardor", "General Graardor", 5000, listOf("general graardor")),
        Pet("Pet k'ril tsutsaroth", "K'ril Tsutsaroth", 5000, listOf("kril tsutsaroth")),
        Pet("Pet zilyana", "Commander Zilyana", 5000, listOf("commander zilyana")),
        Pet("Pet kree'arra", "Kree'arra", 5000, listOf("kreearra")),
        Pet("Nexling", "Nex", 500, listOf("nex"), "Rolled for the MVP"),
        Pet("Pet dark core", "Corporeal Beast", 5000, listOf("corporeal beast")),
        Pet("Pet dagannoth rex", "Dagannoth Rex", 5000, listOf("dagannoth rex")),
        Pet("Pet dagannoth prime", "Dagannoth Prime", 5000, listOf("dagannoth prime")),
        Pet("Pet dagannoth supreme", "Dagannoth Supreme", 5000, listOf("dagannoth supreme")),
        Pet("Kalphite princess", "Kalphite Queen", 3000, listOf("kalphite queen")),
        Pet("Prince black dragon", "King Black Dragon", 3000, listOf("king black dragon")),
        Pet("Pet chaos elemental", "Chaos Elemental", 300, listOf("chaos elemental")),
        Pet("Pet chaos elemental", "Chaos Fanatic", 1000, listOf("chaos fanatic")),
        Pet("Callisto cub", "Callisto", 1500, listOf("callisto")),
        Pet("Callisto cub", "Artio", 2800, listOf("artio")),
        Pet("Venenatis spiderling", "Venenatis", 1500, listOf("venenatis")),
        Pet("Venenatis spiderling", "Spindel", 2800, listOf("spindel")),
        Pet("Vet'ion jr.", "Vet'ion", 1500, listOf("vetion")),
        Pet("Vet'ion jr.", "Calvar'ion", 2800, listOf("calvarion")),
        Pet("Scorpia's offspring", "Scorpia", 2000, listOf("scorpia")),
        Pet("Baby mole", "Giant Mole", 3000, listOf("giant mole")),
        Pet("Pet kraken", "Kraken", 3000, listOf("kraken")),
        Pet("Pet smoke devil", "Thermonuclear smoke devil", 3000, listOf("thermonuclear smoke devil")),
        Pet("Hellpuppy", "Cerberus", 3000, listOf("cerberus")),
        Pet("Abyssal orphan", "Abyssal Sire", 2560, listOf("abyssal sire")),
        Pet("Ikkle hydra", "Alchemical Hydra", 3000, listOf("alchemical hydra")),
        Pet("Noon", "Grotesque Guardians", 3000, listOf("grotesque guardians")),
        Pet("Sraracha", "Sarachnis", 3000, listOf("sarachnis")),
        Pet("Skotos", "Skotizo", 65, listOf("skotizo")),
        Pet("Little nightmare", "The Nightmare", 800, listOf("nightmare", "the nightmare"), "Team size changes it"),
        Pet("Little nightmare", "Phosani's Nightmare", 1400, listOf("phosanis nightmare")),
        Pet("Lil' zik", "Theatre of Blood", 650, listOf("theatre of blood")),
        Pet("Smolcano", "Zalcano", 2250, listOf("zalcano")),
        Pet("Youngllef", "The Gauntlet", 2000, listOf("gauntlet", "the gauntlet")),
        Pet("Youngllef", "The Corrupted Gauntlet", 800, listOf("corrupted gauntlet", "the corrupted gauntlet")),
        Pet("TzRek-Jad", "TzTok-Jad", 200, listOf("tztok jad", "tztokjad"), "1/100 on a Jad slayer task"),
        Pet("Jal-nib-rek", "TzKal-Zuk", 100, listOf("tzkal zuk", "tzkalzuk"), "1/75 on a Zuk slayer task"),
        Pet("Muphin", "Phantom Muspah", 2500, listOf("phantom muspah")),
        Pet("Baron", "Duke Sucellus", 2500, listOf("duke sucellus")),
        Pet("Butch", "Vardorvis", 3000, listOf("vardorvis")),
        Pet("Lil'viathan", "The Leviathan", 2500, listOf("the leviathan", "leviathan")),
        Pet("Wisp", "The Whisperer", 2000, listOf("the whisperer", "whisperer")),
        Pet("Nid", "Araxxor", 3000, listOf("araxxor")),
        Pet("Huberte", "The Hueycoatl", 400, listOf("the hueycoatl", "hueycoatl")),
        Pet("Moxi", "Amoxliatl", 3000, listOf("amoxliatl"))
    )

    /** The pet entry for a boss name as the kill-count message spells it (OCR slips allowed). */
    fun forBoss(name: String): Pet? {
        val n = Fuzzy.norm(name)
        ALL.firstOrNull { p -> p.names.any { it == n } }?.let { return it }
        return ALL.filter { p -> p.names.any { Fuzzy.ratio(it, n) >= 0.85 } }
            .maxByOrNull { p -> p.names.maxOf { Fuzzy.ratio(it, n) } }
    }

    /** "12.3%" or "<0.1%". */
    fun percent(p: Double): String = when {
        p < 0.001 -> "<0.1%"
        p > 0.999 -> ">99.9%"
        else -> String.format(java.util.Locale.US, "%.1f%%", p * 100)
    }
}

/**
 * Keeps the highest kill count seen per boss. Readings must agree twice (one misread digit can't
 * set a wild count) and never go down, since the game's count only grows.
 */
class KillCountTracker(initial: Map<String, Int> = emptyMap()) {
    private val counts = HashMap(initial)
    private val pending = HashMap<String, Int>()

    val all: Map<String, Int> get() = counts

    /** Feeds this look's kill counts. Returns the bosses whose count went up, with the new count. */
    fun update(read: List<KillCount>): List<Pair<String, Int>> {
        val changed = ArrayList<Pair<String, Int>>()
        for (kc in read) {
            val key = Pets.forBoss(kc.boss)?.boss ?: kc.boss.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            val old = counts[key]
            if (old != null && kc.count <= old) continue
            if (pending[key] == kc.count) {
                counts[key] = kc.count
                pending.remove(key)
                changed.add(key to kc.count)
            } else {
                pending[key] = kc.count
            }
        }
        return changed
    }
}
