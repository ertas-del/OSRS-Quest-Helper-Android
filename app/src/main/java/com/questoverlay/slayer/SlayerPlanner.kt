package com.questoverlay.slayer

/** Points maths and "can this master give me that?" checks. Pure, so it's tested on the desktop. */
object SlayerPlanner {
    /** Task number → multiplier, biggest first. */
    private val BONUS = listOf(1000 to 50, 250 to 35, 100 to 25, 50 to 15, 10 to 5)

    fun multiplier(taskNumber: Int): Int = if (taskNumber <= 0) 1 else BONUS.firstOrNull { taskNumber % it.first == 0 }?.second ?: 1

    fun pointsFor(base: Int, taskNumber: Int): Int = base * multiplier(taskNumber)

    /** The next [howMany] bonus tasks after [done] tasks completed, as (task number, points). */
    fun upcoming(base: Int, done: Int, howMany: Int = 3): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var n = done + 1
        while (out.size < howMany && n < done + 5000) {
            val m = multiplier(n)
            if (m > 1) out.add(n to base * m)
            n++
        }
        return out
    }

    enum class Access { OPEN, ABILITY, LOCKED }

    data class Eligible(val row: TaskRow, val access: Access, val why: String)

    /**
     * Whether a master can hand you [row]. Unknown levels / quest lists count as met (like the quest
     * advisor), a "Slayer reward" you may not have bought is [Access.ABILITY] (still counted as possible).
     */
    fun classify(
        row: TaskRow, slayer: Int?, combat: Int?, levels: Map<String, Int>,
        questDone: (String) -> Boolean?, haveAbility: (String) -> Boolean? = { null }
    ): Eligible {
        if (slayer != null && row.slayer > slayer) return Eligible(row, Access.LOCKED, "Slayer ${row.slayer}")
        if (combat != null && row.combat > combat) return Eligible(row, Access.LOCKED, "Combat ${row.combat}")
        var ability: String? = null
        for (clause in row.needs.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (clause.startsWith("Ability:")) {
                val a = clause.removePrefix("Ability:").trim()
                when (haveAbility(a)) {
                    false -> return Eligible(row, Access.LOCKED, "Slayer reward: $a")
                    null -> ability = a
                    true -> {}
                }
                continue
            }
            val skill = Regex("""^([A-Za-z]+) (\d+)$""").find(clause)
            if (skill != null) {
                val have = levels[skill.groupValues[1]]
                if (have != null && have < skill.groupValues[2].toInt()) return Eligible(row, Access.LOCKED, clause)
                continue
            }
            // Quests: "A or B", "(partial)", "(started)" and "(for somewhere)" qualifiers.
            if (clause.contains("(for ")) continue
            val options = clause.split(" or ").map { it.replace(Regex("""\s*\((partial|started)\)"""), "").trim() }
            val states = options.map { questDone(it) }
            if (states.all { it == false }) return Eligible(row, Access.LOCKED, options.joinToString(" or "))
        }
        return if (ability != null) Eligible(row, Access.ABILITY, "Needs the \"$ability\" Slayer reward") else Eligible(row, Access.OPEN, "")
    }

    /** Chance of each possible task (by id): weight over the weight of everything not locked. */
    fun chances(list: List<Eligible>): Map<String, Float> {
        val open = list.filter { it.access != Access.LOCKED }
        val total = open.sumOf { it.row.weight }.toFloat()
        if (total <= 0f) return emptyMap()
        return open.associate { it.row.id to it.row.weight / total }
    }
}
