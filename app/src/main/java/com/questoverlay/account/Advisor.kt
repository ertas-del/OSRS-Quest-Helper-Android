package com.questoverlay.account

import com.questoverlay.Quest
import com.questoverlay.travel.QuestNames

/** One quest requirement, parsed from the text the quest data stores ("Agility 25 (boostable)"). */
sealed class Req {
    data class Skill(val skill: String, val level: Int, val boostable: Boolean) : Req()
    data class QuestDone(val quest: String, val startedIsEnough: Boolean) : Req()
    data class QuestPoints(val points: Int) : Req()
    data class Combat(val level: Int) : Req()
    data class Other(val text: String) : Req()

    companion object {
        val SKILLS = listOf(
            "Attack", "Hitpoints", "Mining", "Strength", "Agility", "Smithing", "Defence", "Herblore",
            "Fishing", "Ranged", "Thieving", "Cooking", "Prayer", "Crafting", "Firemaking", "Magic",
            "Fletching", "Woodcutting", "Runecraft", "Slayer", "Farming", "Construction", "Hunter", "Sailing"
        )
        private val SKILL_RE = Regex("^(\\w+) (\\d+)( \\(boostable\\))?$")
        private val QP_RE = Regex("^(\\d+) quest points$")
        private val COMBAT_RE = Regex("^Combat level (\\d+)$")

        fun parse(text: String): Req {
            SKILL_RE.find(text)?.let { m ->
                val skill = m.groupValues[1]
                if (skill in SKILLS) return Skill(skill, m.groupValues[2].toInt(), m.groupValues[3].isNotEmpty())
            }
            QP_RE.find(text)?.let { return QuestPoints(it.groupValues[1].toInt()) }
            COMBAT_RE.find(text)?.let { return Combat(it.groupValues[1].toInt()) }
            if (text.endsWith(" (started)")) return QuestDone(text.removeSuffix(" (started)"), true)
            return QuestDone(text, false)
        }
    }
}

/** Combat level from skill levels, using the game's formula. Null if a combat skill is unknown. */
object CombatLevel {
    fun of(levels: Map<String, Int>): Int? {
        fun lv(s: String) = levels[s]
        val att = lv("Attack") ?: return null
        val str = lv("Strength") ?: return null
        val def = lv("Defence") ?: return null
        val hp = lv("Hitpoints") ?: return null
        val pray = lv("Prayer") ?: return null
        val range = lv("Ranged") ?: return null
        val mage = lv("Magic") ?: return null
        val base = 0.25 * (def + hp + pray / 2)
        val melee = 0.325 * (att + str)
        val ranged = 0.325 * (range * 3 / 2)
        val magic = 0.325 * (mage * 3 / 2)
        return (base + maxOf(melee, ranged, magic)).toInt()
    }
}

/**
 * Works out what the player can do next from what we know about the account. Nothing here reads
 * the game: [levels] come from the hiscores or WikiSync, [done] and [started] from WikiSync or
 * from quests the player finished or marked done in Breadcrumbs.
 *
 * Unknown levels count as met, so a player who hasn't loaded levels still gets suggestions.
 */
class Advisor(
    private val quests: List<Quest>,
    private val levels: Map<String, Int>,
    done: Set<String>,
    started: Set<String>
) {
    private val done = done.map { QuestNames.canon(it) }.toSet()
    private val started = started.map { QuestNames.canon(it) }.toSet()

    fun isDone(q: Quest) = QuestNames.canon(q.name) in done
    fun isStarted(q: Quest) = !isDone(q) && QuestNames.canon(q.name) in started

    /** Quest points earned so far, and the most there are. */
    val questPoints: Int = quests.filter { isDone(it) }.sumOf { it.qp }
    val maxQuestPoints: Int = quests.sumOf { it.qp }
    val combat: Int? = CombatLevel.of(levels)

    /** How many quests list each quest as a requirement (directly). */
    private val unlockCount: Map<String, Int> = HashMap<String, Int>().also { m ->
        for (q in quests) for (r in q.requirements) {
            val req = Req.parse(r)
            if (req is Req.QuestDone) {
                val k = QuestNames.canon(req.quest)
                m[k] = (m[k] ?: 0) + 1
            }
        }
    }

    fun unlocks(q: Quest): Int = unlockCount[QuestNames.canon(q.name)] ?: 0

    /** What's still missing for [q], as short lines like "Agility 52 (you have 49)". Empty if nothing. */
    fun missing(q: Quest): List<String> {
        val out = ArrayList<String>()
        for (r in q.requirements) {
            when (val req = Req.parse(r)) {
                is Req.Skill -> {
                    val have = levels[req.skill] ?: continue
                    if (have < req.level) out.add("${req.skill} ${req.level} (you have $have)")
                }
                is Req.QuestDone -> {
                    val k = QuestNames.canon(req.quest)
                    val ok = k in done || (req.startedIsEnough && k in started)
                    if (!ok) out.add(if (req.startedIsEnough) "Start ${req.quest}" else req.quest)
                }
                is Req.QuestPoints -> if (questPoints < req.points) out.add("${req.points} quest points (you have $questPoints)")
                is Req.Combat -> {
                    val c = combat ?: continue
                    if (c < req.level) out.add("Combat level ${req.level} (you have $c)")
                }
                is Req.Other -> {}
            }
        }
        return out
    }

    /** Levels still to gain for [q], or null if anything other than skills is missing. */
    fun skillGap(q: Quest): Int? {
        var gap = 0
        for (r in q.requirements) {
            when (val req = Req.parse(r)) {
                is Req.Skill -> {
                    val have = levels[req.skill] ?: continue
                    if (have < req.level) gap += req.level - have
                }
                is Req.QuestDone -> {
                    val k = QuestNames.canon(req.quest)
                    if (!(k in done || (req.startedIsEnough && k in started))) return null
                }
                is Req.QuestPoints -> if (questPoints < req.points) return null
                is Req.Combat -> combat?.let { if (it < req.level) return null }
                is Req.Other -> {}
            }
        }
        return gap
    }

    /**
     * Quests you can do right now: quests you've started first, then the ones that open up the
     * most other quests, then the easiest.
     */
    fun ready(): List<Quest> = quests
        .filter { !isDone(it) && missing(it).isEmpty() }
        .sortedWith(
            compareBy<Quest>(
                { if (isStarted(it)) 0 else 1 },
                { -unlocks(it) },
                { DIFFICULTY.indexOf(it.difficulty).let { i -> if (i < 0) DIFFICULTY.size else i } },
                { it.name.lowercase().removePrefix("the ") }
            )
        )

    /** Quests held back only by a few levels (at most [maxGap] in total), closest first. */
    fun almost(maxGap: Int = 8): List<Pair<Quest, List<String>>> = quests
        .filter { !isDone(it) }
        .mapNotNull { q -> skillGap(q)?.takeIf { it in 1..maxGap }?.let { gap -> Triple(q, gap, missing(q)) } }
        .sortedWith(compareBy({ it.second }, { -unlocks(it.first) }))
        .map { it.first to it.third }

    companion object {
        val DIFFICULTY = listOf("Novice", "Intermediate", "Experienced", "Master", "Grandmaster", "Miniquest")
    }
}
