package com.questoverlay.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What Auto-check read and decided recently, for the "What it read" box on the main screen.
 * Kept in memory only, never written to storage.
 */
object CaptureLog {
    private const val MAX = 60
    private val entries = ArrayDeque<String>()
    /** Individual lines from the last few screenshots, newest first, for "Alert me on this". */
    private val lines = LinkedHashSet<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var lastLookAt: Long = 0L
        private set

    @Synchronized
    fun frame(lines: List<OcrLine>, facts: ScreenFacts, decision: String?) {
        lastLookAt = System.currentTimeMillis()
        val stamp = time.format(Date(lastLookAt))
        val seen = lines.sortedBy { it.top }.joinToString(" | ") { it.text.trim() }.take(400)
        for (l in lines) {
            val t = l.text.trim()
            if (t.length < 4) continue
            this.lines.remove(t)
            this.lines.add(t)
        }
        while (this.lines.size > 40) this.lines.remove(this.lines.first())
        val read = buildList {
            if (facts.questComplete) add("quest complete")
            if (facts.dialogueOpen) add("dialogue" + (facts.speaker?.let { " with $it" } ?: ""))
            if (facts.options.isNotEmpty()) add("${facts.options.size} options")
            if (facts.menuNames.isNotEmpty()) add("menu: " + facts.menuNames.joinToString())
            if (facts.messages.isNotEmpty()) add("chat: " + facts.messages.last())
            facts.diaryTask?.let { add("diary: ${it.tier} task in ${it.region}" + if (it.allTasks) " (all done)" else "") }
            for (kc in facts.killCounts) add("kc: ${kc.boss} ${kc.count}")
        }
        add("$stamp  saw: ${seen.ifEmpty { "(no text)" }}")
        if (read.isNotEmpty() || decision != null) {
            add("$stamp  thinks: " + (read + listOfNotNull(decision)).joinToString("; "))
        }
    }

    @Synchronized
    fun note(text: String) = add("${time.format(Date())}  $text")

    @Synchronized
    fun recent(n: Int = 20): List<String> = entries.toList().takeLast(n)

    /** Lines read recently (newest first), each one a candidate for "Alert me on this". */
    @Synchronized
    fun recentLines(): List<String> = lines.toList().asReversed()

    @Synchronized
    fun clear() {
        entries.clear()
        lines.clear()
    }

    private fun add(s: String) {
        entries.addLast(s)
        while (entries.size > MAX) entries.removeFirst()
    }
}
