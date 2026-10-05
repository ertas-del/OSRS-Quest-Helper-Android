package com.questoverlay.capture

import kotlin.math.max
import kotlin.math.min

/**
 * One line of text read off the screen, with where it was. Coordinates are fractions of the
 * captured image (0..1), so they don't depend on the capture resolution.
 */
data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Forgiving text comparison: OCR on the game's pixel font drops and swaps letters. */
object Fuzzy {
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun norm(s: String): String = s.lowercase()
        .replace('’', '\'')
        .replace("'", "")
        .replace(NON_ALNUM, " ")
        .trim()

    fun words(s: String): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    /** 1.0 = identical, 0.0 = nothing alike (normalised Levenshtein). */
    fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    /**
     * Whether [name] appears in [text], allowing a letter or two to be misread. Compares the
     * name against every run of the same number of words in the text.
     */
    fun containsName(text: String, name: String, threshold: Double = 0.8): Boolean {
        val nameWords = words(name).filter { it != "the" }
        if (nameWords.isEmpty()) return false
        val target = nameWords.joinToString(" ")
        val tw = words(text).filter { it != "the" }
        if (tw.size < nameWords.size) return ratio(tw.joinToString(" "), target) >= threshold
        for (i in 0..tw.size - nameWords.size) {
            val window = tw.subList(i, i + nameWords.size).joinToString(" ")
            if (ratio(window, target) >= threshold) return true
        }
        return false
    }

    /** Whether two phrases say the same thing (dialogue options), allowing small misreads. */
    fun samePhrase(a: String, b: String, threshold: Double = 0.8): Boolean {
        val na = norm(a)
        val nb = norm(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (ratio(na, nb) >= threshold) return true
        // OCR sometimes cuts a long option short, or adds the option number in front ("1 Yes").
        val shorter = if (na.length <= nb.length) na else nb
        val longer = if (na.length <= nb.length) nb else na
        return shorter.length >= 6 && longer.contains(shorter)
    }
}

/** What one screenshot tells us. */
data class ScreenFacts(
    /** The "quest complete" scroll or chat message is showing. */
    val questComplete: Boolean = false,
    /** A dialogue box is open (someone talking, or options to pick). */
    val dialogueOpen: Boolean = false,
    /** Which of the names we were looking for is speaking in the dialogue box, if any. */
    val speaker: String? = null,
    /** The lines of a "Select an option" box, in order. */
    val options: List<OcrLine> = emptyList(),
    /** Names we were looking for that appear in a tap menu ("Talk-to Cook"). */
    val menuNames: Set<String> = emptySet(),
    /** Game messages in the chat box ("You pick some wheat."), timestamps removed, wrapped lines joined. */
    val messages: List<String> = emptyList(),
    /** "Well done! You have completed an easy task in the Ardougne area." */
    val diaryTask: DiaryTaskDone? = null,
    /** "Your Vorkath kill count is: 413." Several can be on screen at once. */
    val killCounts: List<KillCount> = emptyList(),
    /** The chat messages with their times ("1829"), for telling a new message from an old one. */
    val timedMessages: List<Pair<String?, String>> = emptyList()
)

/**
 * A diary message: one task done, or (allTasks) the whole tier. [key] includes the chat time, so
 * the same wording seen again later counts as a new message.
 */
data class DiaryTaskDone(val tier: String, val region: String, val allTasks: Boolean = false, val key: String = "")

data class KillCount(val boss: String, val count: Int)

/**
 * Reads the chat box. On the phone it sits top-left and every line starts with a time
 * ("[18:29] You plant the maple sapling…"); long messages wrap onto a second line without one.
 */
object Chat {
    // A little junk may come first (the chat's scroll bar gets read as "|" or "#").
    private val TIMESTAMP = Regex("""^[^\w\[(]{0,3}\w?[^\w\[(]{0,2}[\[(|]?\s*\d{1,2}\s*[:;.]\s*\d{2}\s*[\])|]?\s*""")
    private val MANGLED_TIME = Regex("""^.{0,8}?\]\s*""")
    private val GAME_STARTS = listOf("you ", "your ", "well done", "congratulations", "oh dear", "the ")

    /** "[18:29] You plant…" → (true, "You plant…"). */
    fun stripTime(text: String): Pair<Boolean, String> {
        val m = TIMESTAMP.find(text) ?: return false to text.trim()
        // A bare number on its own isn't a timestamp ("99" next to the HP orb).
        if (m.value.none { it == ':' || it == ';' || it == '.' }) return false to text.trim()
        return true to text.substring(m.range.last + 1).trim()
    }

    /**
     * Chat lines, oldest first. [skip] are line indexes to ignore (the dialogue box).
     * Lines with a timestamp are chat wherever they are; lines without one count only if they
     * continue the line above (a wrapped message) or read like a game message on the left side.
     */
    fun messages(lines: List<OcrLine>, skip: Set<Int> = emptySet()): List<String> = timed(lines, skip).map { it.second }

    /** Like [messages], with each message's time ("18:29") when it had one. */
    fun timed(lines: List<OcrLine>, skip: Set<Int> = emptySet()): List<Pair<String?, String>> {
        val left = lines.indices.filter { it !in skip && lines[it].left < 0.5f }.sortedBy { lines[it].top }
        val out = ArrayList<Pair<String?, String>>()
        // Wrapped messages fill the chat box to its right edge before wrapping.
        val timedLines = left.filter { stripTime(lines[it].text).first }
        val chatLeft = timedLines.minOfOrNull { lines[it].left } ?: 0f
        val chatRight = timedLines.maxOfOrNull { lines[it].right } ?: 0f
        var lastIdx = -1
        for (i in left) {
            val l = lines[i]
            var (hasTime, text) = stripTime(l.text)
            val time = if (hasTime) TIMESTAMP.find(l.text)?.value?.filter { it.isDigit() } else null
            if (!hasTime) {
                // A timestamp read too badly to parse ("Figg] tou plant…") still starts a new message.
                MANGLED_TIME.find(text)?.let {
                    hasTime = true
                    text = text.substring(it.range.last + 1).trim()
                }
            }
            if (text.isEmpty()) continue
            val prev = if (lastIdx >= 0) lines[lastIdx] else null
            val continues = prev != null && !hasTime && out.isNotEmpty() &&
                prev.right - chatLeft >= (chatRight - chatLeft) * 0.85f &&
                l.top - prev.bottom < (prev.bottom - prev.top) * 0.9f && l.top > prev.top &&
                kotlin.math.abs(l.left - prev.left) < 0.03f
            when {
                continues -> out[out.size - 1] = out.last().first to (out.last().second + " " + text)
                hasTime -> out.add(time to text)
                GAME_STARTS.any { Fuzzy.norm(text).startsWith(it.trim()) } && Fuzzy.words(text).size >= 3 -> out.add(null to text)
                else -> { lastIdx = -1; continue }
            }
            lastIdx = i
        }
        return out
    }

    private val DIARY = Regex("""completed an? (easy|medium|hard|elite) task in the (.+?) area""")
    private val DIARY_ALL = Regex("""completed all (?:of )?the (easy|medium|hard|elite) tasks in the (.+?) area""")

    fun diaryTask(message: String, time: String? = null): DiaryTaskDone? {
        val n = Fuzzy.norm(message)
        DIARY_ALL.find(n)?.let { return DiaryTaskDone(it.groupValues[1], it.groupValues[2], true, "${time ?: ""}|$n") }
        val m = DIARY.find(n) ?: return null
        return DiaryTaskDone(m.groupValues[1], m.groupValues[2], false, "${time ?: ""}|$n")
    }

    /** "Lumbridge & Draynor" and the OCR'd "lumbridge draynor" are the same region. */
    fun sameRegion(a: String, b: String): Boolean {
        val x = Fuzzy.norm(a).replace(" ", "")
        val y = Fuzzy.norm(b).replace(" ", "")
        return x == y || Fuzzy.ratio(x, y) >= 0.8
    }

    private val KC = Regex("""your (.+?) (?:kill|chest|completion|completed|success) count is (\d[\d ,]*)""")
    private val KC2 = Regex("""your completed (.+?) count is (\d[\d ,]*)""")

    fun killCount(message: String): KillCount? {
        val n = Fuzzy.norm(message).replace(Regex("(?<=\\d) (?=\\d)"), "")
        val m = KC2.find(n) ?: KC.find(n) ?: return null
        val count = m.groupValues[2].filter { it.isDigit() }.toIntOrNull() ?: return null
        val boss = m.groupValues[1].trim()
        if (boss.isEmpty() || boss.length > 40) return null
        return KillCount(boss, count)
    }
}

/**
 * Turns lines of text read off the game screen into [ScreenFacts]. It knows the game's wording
 * ("Click here to continue", "Select an option", "Talk-to") and roughly where things sit, but
 * mostly it looks for the names and phrases the current quest step expects.
 */
object ScreenSense {

    private val CONTINUE = "click here to continue"
    private val SELECT = "select an option"

    fun read(lines: List<OcrLine>, expectedNames: Collection<String>, questName: String): ScreenFacts {
        if (lines.isEmpty()) return ScreenFacts()
        val norms = lines.map { Fuzzy.norm(it.text) }

        // Quest complete: chat "Congratulations, you've completed a quest: X" or the scroll
        // "You have completed X!". The quest's own name must be there too: the chat box keeps
        // old messages, so the last quest's message is often still showing.
        var complete = false
        for ((i, n) in norms.withIndex()) {
            if (n.contains("completed a quest") || n.contains("you have completed") ||
                n.contains("quest complete") || n.contains("congratulations")
            ) {
                // The quest's name is on this line or the next one.
                val around = n + " " + norms.getOrElse(i + 1) { "" }
                if (Fuzzy.containsName(around, questName, 0.78)) {
                    complete = true
                    break
                }
            }
        }

        // Dialogue box: anchored on "Click here to continue" or "Select an option".
        val continueLine = lines.indices.firstOrNull { Fuzzy.ratio(norms[it], CONTINUE) >= 0.75 || norms[it].contains("here to continue") }
            ?.let { lines[it] }
        val selectIdx = lines.indices.firstOrNull { Fuzzy.ratio(norms[it], SELECT) >= 0.75 }
        val selectLine = selectIdx?.let { lines[it] }

        // The box spans roughly a quarter of the screen above its bottom line.
        val anchor = continueLine ?: selectLine
        val boxLines: List<Int> = if (anchor == null) emptyList() else {
            val bottom = if (continueLine != null) continueLine.bottom else anchor.bottom + 0.30f
            val top = (if (continueLine != null) continueLine.top else anchor.top) - 0.30f
            lines.indices.filter { lines[it].centerY in top..bottom }
        }

        // The speaker's name sits on its own short line at the top of the box. Only short lines
        // are checked, so a word like "look" in what they say can't pass for "Cook".
        var speaker: String? = null
        if (boxLines.isNotEmpty()) {
            loop@ for (name in expectedNames) {
                val nameWords = Fuzzy.words(name).size
                val threshold = if (Fuzzy.norm(name).length <= 5) 0.75 else 0.8
                for (i in boxLines) {
                    if (Fuzzy.words(norms[i]).size > nameWords + 2) continue
                    if (Fuzzy.containsName(norms[i], name, threshold)) {
                        speaker = name
                        break@loop
                    }
                }
            }
        }

        // Options: the lines under "Select an option", top to bottom, until a gap.
        val options = ArrayList<OcrLine>()
        if (selectLine != null) {
            val below = lines.withIndex()
                .filter { (i, l) -> i != selectIdx && l.top >= selectLine.bottom - 0.005f && l.top <= selectLine.bottom + 0.35f }
                .map { it.value }
                .sortedBy { it.top }
            var lastBottom = selectLine.bottom
            for (l in below) {
                if (l.top - lastBottom > 0.08f) break // too big a gap: no longer part of the list
                if (Fuzzy.norm(l.text).isEmpty()) continue
                options.add(l)
                lastBottom = l.bottom
                if (options.size == 5) break
            }
        }

        // Tap menus: "Talk-to Cook", "Talk to Cook".
        val menuNames = HashSet<String>()
        for (n in norms) {
            if (n.startsWith("talk to") || n.startsWith("talkto") || n.startsWith("talk-to")) {
                for (name in expectedNames) if (Fuzzy.containsName(n, name)) menuNames.add(name)
            }
        }

        // Chat messages: anywhere on the left half outside the dialogue box (top-left on phones).
        val timed = Chat.timed(lines, boxLines.toHashSet())
        val messages = timed.map { it.second }

        return ScreenFacts(
            questComplete = complete,
            dialogueOpen = continueLine != null || selectLine != null,
            speaker = speaker,
            options = options,
            menuNames = menuNames,
            messages = messages,
            diaryTask = timed.asReversed().firstNotNullOfOrNull { (t, m) -> Chat.diaryTask(m, t) },
            killCounts = messages.mapNotNull { Chat.killCount(it) },
            timedMessages = timed
        )
    }
}
