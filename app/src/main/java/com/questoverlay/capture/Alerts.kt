package com.questoverlay.capture

/**
 * Something on screen worth interrupting you for while you AFK, e.g. "The cargo hold is full".
 * A rule fires when one of its [patterns] shows up in a line of text read off the screen.
 */
data class AlertRule(
    val id: String,
    val label: String,
    val patterns: List<String>,
    val builtIn: Boolean,
    /** False while the exact in-game wording is still a best guess. */
    val confirmed: Boolean = true
)

object BuiltInAlerts {
    val CARGO_FULL = AlertRule(
        "cargo_full", "Cargo hold full",
        listOf("The cargo hold is full", "I can't salvage anything", "cargo hold is full"),
        builtIn = true
    )
    val INVENTORY_FULL = AlertRule(
        "inventory_full", "Inventory full",
        listOf(
            "inventory is too full", "not have enough inventory space", "don't have enough inventory space",
            "your inventory is full", "you have no free inventory space"
        ),
        builtIn = true, confirmed = false
    )
    val SHIPWRECK_GONE = AlertRule(
        "shipwreck_gone", "Shipwreck finished",
        listOf(
            "shipwreck sinks", "the shipwreck sinks", "fully salvaged", "nothing left to salvage",
            "there is nothing left", "the wreck sinks", "salvage spot is depleted"
        ),
        builtIn = true, confirmed = false
    )
    val ALL = listOf(CARGO_FULL, INVENTORY_FULL, SHIPWRECK_GONE)
}

/**
 * Decides which alerts to raise from one screenshot's text. The chat box keeps old messages, so
 * an alert only fires when its message *appears*, not every time it is still visible:
 * a match has to be missing from the last [forgetFrames] screenshots to count as new. Each rule
 * then waits [cooldownMs] before it can fire again. The first screenshot after starting only
 * learns what's already on screen. Plain Kotlin, tested on the desktop.
 */
class AlertEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cooldownMs: Long = 45_000,
    private val forgetFrames: Int = 3
) {
    private val recent = ArrayDeque<Set<String>>()
    private val lastFired = HashMap<String, Long>()
    private var primed = false

    /** Forget everything (Auto-check restarted): the next screenshot only primes. */
    fun reset() {
        recent.clear()
        primed = false
    }

    /** The rules that fire for this screenshot, each at most once. */
    fun check(lines: List<String>, rules: List<AlertRule>): List<Pair<AlertRule, String>> {
        val now = clock()
        val keys = HashSet<String>()
        val fired = LinkedHashMap<String, Pair<AlertRule, String>>()
        for (line in lines) {
            val n = Fuzzy.norm(line)
            if (n.isEmpty()) continue
            for (rule in rules) {
                if (!matches(n, rule)) continue
                val key = rule.id + "|" + n
                keys.add(key)
                if (!primed) continue
                if (recent.any { key in it }) continue
                val last = lastFired[rule.id]
                if (last != null && now - last < cooldownMs) continue
                if (rule.id !in fired) fired[rule.id] = rule to line.trim()
            }
        }
        for (id in fired.keys) lastFired[id] = now
        recent.addLast(keys)
        while (recent.size > forgetFrames) recent.removeFirst()
        primed = true
        return fired.values.toList()
    }

    companion object {
        /** Whether a normalised line contains one of the rule's phrases, allowing small misreads. */
        fun matches(normLine: String, rule: AlertRule): Boolean {
            val line = letters(normLine)
            return rule.patterns.any { contains(line, letters(Fuzzy.norm(it))) }
        }

        /** The text reader's favourite mix-ups: 0/o, 1/l, 5/s. Alert phrases are words, so read digits as letters. */
        private fun letters(s: String): String =
            s.replace('0', 'o').replace('1', 'l').replace('5', 's')

        fun contains(hay: String, needle: String): Boolean {
            if (needle.isEmpty()) return false
            if (hay.contains(needle)) return true
            if (needle.length < 8 || hay.length < needle.length - 2) return false
            // Slide the phrase along the line and allow ~15% of letters to be misread.
            var best = 0.0
            for (len in (needle.length - 2)..(needle.length + 2)) {
                if (len > hay.length) break
                for (i in 0..hay.length - len) {
                    val r = Fuzzy.ratio(hay.substring(i, i + len), needle)
                    if (r > best) best = r
                }
            }
            return best >= 0.85
        }
    }
}
