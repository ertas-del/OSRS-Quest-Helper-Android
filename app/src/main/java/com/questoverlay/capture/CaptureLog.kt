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
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var lastLookAt: Long = 0L
        private set

    @Synchronized
    fun frame(lines: List<OcrLine>, facts: ScreenFacts, decision: String?) {
        lastLookAt = System.currentTimeMillis()
        val stamp = time.format(Date(lastLookAt))
        val seen = lines.sortedBy { it.top }.joinToString(" | ") { it.text.trim() }.take(400)
        val read = buildList {
            if (facts.questComplete) add("quest complete")
            if (facts.dialogueOpen) add("dialogue" + (facts.speaker?.let { " with $it" } ?: ""))
            if (facts.options.isNotEmpty()) add("${facts.options.size} options")
            if (facts.menuNames.isNotEmpty()) add("menu: " + facts.menuNames.joinToString())
            if (facts.messages.isNotEmpty()) add("chat: " + facts.messages.last())
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

    @Synchronized
    fun clear() = entries.clear()

    private fun add(s: String) {
        entries.addLast(s)
        while (entries.size > MAX) entries.removeFirst()
    }
}
