package com.questoverlay.slayer

import com.questoverlay.capture.Fuzzy

/** What a Slayer message from the game says. */
sealed class SlayerEvent {
    /** The master's dialogue: "Your new task is to kill 150 aberrant spectres in the Catacombs of Kourend." */
    data class NewTask(val count: Int, val name: String, val place: String?) : SlayerEvent()
    /** Checking with a gem or helm: "You're assigned to kill X; only 117 more to go." */
    data class Progress(val name: String, val place: String?, val remaining: Int) : SlayerEvent()
    /** "You've completed 235 tasks and received 18 points, giving you a total of 1,234; return to a Slayer master." */
    data class Completed(val tasks: Int, val points: Int?, val total: Int?) : SlayerEvent()
}

object SlayerChat {
    private val NEW = Regex("""your new task is to kill (\d+) (.+)""")
    private val GEM = Regex("""youre assigned to kill (.+?) only (\d+) more to go""")
    private val DONE = Regex("""youve completed (?:at least )?(\d+) (?:wilderness )?tasks?(?: and received (\d+) points? giving you a total of ([\d ]+))?""")
    private val TAIL = Regex(""" (click here to continue|please wait|if you want|you can).*$""")
    private val PLACE = Regex("""^(.+?) in (?:the )?(.+)$""")

    private fun split(s: String): Pair<String, String?> {
        val m = PLACE.find(s) ?: return s to null
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }

    /** Reads one chat message or the master's dialogue text. Null if it isn't a Slayer message. */
    fun parse(text: String): SlayerEvent? {
        val n = Fuzzy.norm(text).replace(Regex("(?<=\\d) (?=\\d{3}\\b)"), "")
        GEM.find(n)?.let {
            val (name, place) = split(it.groupValues[1])
            val left = it.groupValues[2].toIntOrNull() ?: return null
            return SlayerEvent.Progress(name, place, left)
        }
        NEW.find(n)?.let {
            val count = it.groupValues[1].toIntOrNull() ?: return null
            val (name, place) = split(it.groupValues[2].replace(TAIL, "").trim())
            if (name.isEmpty() || name.length > 40) return null
            return SlayerEvent.NewTask(count, name, place)
        }
        DONE.find(n)?.let {
            val tasks = it.groupValues[1].toIntOrNull() ?: return null
            return SlayerEvent.Completed(tasks, it.groupValues[2].toIntOrNull(), it.groupValues[3].filter { c -> c.isDigit() }.toIntOrNull())
        }
        return null
    }
}
