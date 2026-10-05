package com.questoverlay.capture

/** The parts of a quest step the coach needs. */
data class StepInfo(val index: Int, val text: String, val npc: String, val chat: List<String>)

/** Where the player is in the quest right now. */
data class CoachContext(
    val questId: String,
    val questName: String,
    val stepCount: Int,
    /** The current step, or null when the quest is already finished. */
    val step: StepInfo?,
    /** The next couple of steps, for noticing you've already moved on. */
    val upcoming: List<StepInfo>
) {
    val key: String get() = "$questId#${step?.index ?: stepCount}"

    /** Names worth looking for on screen: this step's NPC first, then the next steps'. */
    val names: List<String>
        get() = (listOfNotNull(step?.npc) + upcoming.map { it.npc }).filter { it.isNotBlank() }.distinct()
}

/** A question for the player, shown as a banner on the card. */
data class Suggestion(val kind: Kind, val text: String, val targetIndex: Int) {
    enum class Kind { TICK, SKIP }
}

/** What the coach wants done after one screenshot. */
data class CoachOutput(
    /** A short status line for the card ("Talking to Cook ✓"). */
    val status: String? = null,
    /** A new question to ask, or null to keep whatever is showing. */
    val suggestion: Suggestion? = null,
    /** The dialogue option to outline on screen, and its text. */
    val highlight: OcrLine? = null,
    val pick: String? = null,
    /** The quest-complete scroll was seen: finish the quest. */
    val completeQuest: Boolean = false,
    /** Something to say out loud (the caller applies mute). */
    val speak: String? = null
)

/**
 * Remembers what it has seen across screenshots, so one misread frame doesn't tick anything:
 * a thing has to be seen in [confirmFrames] screenshots in a row before the coach acts on it.
 * Plain Kotlin, so it is tested on the desktop.
 */
class Coach(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val confirmFrames: Int = 2,
    private val speakCooldownMs: Long = 20_000
) {
    private var stepKey: String? = null

    // Per step.
    private var talkingFrames = 0
    private var talkedToNpc = false
    private var closedFrames = 0
    private var aheadIndex = -1
    private var aheadFrames = 0
    private var offered = HashSet<String>()

    // Across steps.
    private var completeFrames = 0
    private val completedQuests = HashSet<String>()
    private val dismissed = HashSet<String>()
    private val lastSpoken = HashMap<String, Long>()

    /** The player answered ✕ to a suggestion: don't ask the same thing again for this step. */
    fun dismiss(ctx: CoachContext, s: Suggestion) {
        dismissed.add("${ctx.key}|${s.kind}|${s.targetIndex}")
    }

    fun onFrame(facts: ScreenFacts, ctx: CoachContext): CoachOutput {
        if (ctx.key != stepKey) {
            stepKey = ctx.key
            talkingFrames = 0
            talkedToNpc = false
            closedFrames = 0
            aheadIndex = -1
            aheadFrames = 0
            offered = HashSet()
        }

        // 1. Quest complete: finish it (once per quest).
        completeFrames = if (facts.questComplete) completeFrames + 1 else 0
        if (completeFrames >= confirmFrames && ctx.step != null && completedQuests.add(ctx.questId)) {
            return CoachOutput(
                status = "Quest complete!",
                completeQuest = true,
                speak = say("complete:${ctx.questId}", "Quest complete!")
            )
        }

        val step = ctx.step ?: return CoachOutput()
        var status: String? = null
        var suggestion: Suggestion? = null
        var speak: String? = null

        // 2. Talking to this step's NPC, then the conversation closes: "Looks done?"
        val npc = step.npc
        if (npc.isNotBlank()) {
            if (facts.speaker == npc) {
                talkingFrames++
                closedFrames = 0
                if (talkingFrames >= confirmFrames) talkedToNpc = true
            } else if (!facts.dialogueOpen) {
                if (talkedToNpc) closedFrames++
                talkingFrames = 0
            }
            if (talkedToNpc) status = "Talking to $npc ✓"
            if (talkedToNpc && closedFrames >= confirmFrames) {
                suggestion = offer(ctx, Suggestion(Suggestion.Kind.TICK, "Looks done: you talked to $npc. Tick it?", step.index + 1))
            }
            if (npc in facts.menuNames && status == null) status = "That's $npc ✓"
        }

        // 3. Already talking to a later step's NPC: offer to skip ahead.
        val ahead = ctx.upcoming.firstOrNull { it.npc.isNotBlank() && it.npc != npc && it.npc == facts.speaker }
        if (ahead != null) {
            aheadFrames = if (ahead.index == aheadIndex) aheadFrames + 1 else 1
            aheadIndex = ahead.index
            if (aheadFrames >= confirmFrames && suggestion == null) {
                suggestion = offer(ctx, Suggestion(Suggestion.Kind.SKIP, "You're talking to ${ahead.npc}. Skip to step ${ahead.index + 1}?", ahead.index))
            }
        } else {
            aheadFrames = 0
        }

        // 4. Dialogue options: outline the one this step wants.
        var highlight: OcrLine? = null
        var pick: String? = null
        if (facts.options.isNotEmpty() && step.chat.isNotEmpty()) {
            loop@ for (want in step.chat) {
                for (opt in facts.options) {
                    if (Fuzzy.samePhrase(opt.text, want)) {
                        highlight = opt
                        pick = want
                        break@loop
                    }
                }
            }
            if (pick != null) speak = say("pick:${ctx.key}:$pick", "Choose: $pick")
        }

        // 5. A game message that matches what the step asks for ("You pick some wheat.").
        if (suggestion == null && npc.isBlank()) {
            val match = facts.messages.firstOrNull { messageMatches(it, step.text) }
            if (match != null) {
                suggestion = offer(ctx, Suggestion(Suggestion.Kind.TICK, "Looks done: “$match”. Tick it?", step.index + 1))
            }
        }

        if (suggestion != null && speak == null) speak = say("suggest:${ctx.key}:${suggestion.kind}", "Step looks done")
        if (pick != null && status == null) status = "Pick: $pick"
        return CoachOutput(status = status, suggestion = suggestion, highlight = highlight, pick = pick, speak = speak)
    }

    /** Each question is asked once per step, and never again after ✕. */
    private fun offer(ctx: CoachContext, s: Suggestion): Suggestion? {
        val id = "${ctx.key}|${s.kind}|${s.targetIndex}"
        if (id in dismissed || !offered.add(id)) return null
        return s
    }

    private fun say(key: String, text: String): String? {
        val now = clock()
        val last = lastSpoken[key]
        if (last != null && now - last < speakCooldownMs) return null
        lastSpoken[key] = now
        return text
    }

    companion object {
        private val STOP = setOf(
            "the", "a", "an", "to", "of", "in", "on", "at", "and", "with", "from", "your", "you", "for", "into",
            "some", "it", "this", "that", "them", "then", "there", "here", "again", "north", "south", "east",
            "west", "near", "inside", "outside", "just", "one", "any", "all", "get", "use", "can", "will",
            "have", "has", "are", "is", "was", "be", "by", "or", "up", "down", "out", "back"
        )

        private fun keyWords(s: String): Set<String> =
            Fuzzy.words(s).filter { it.length >= 3 && it !in STOP }.map { it.take(5) }.toSet()

        /** A message matches a step when they share at least two meaningful words. */
        fun messageMatches(message: String, stepText: String): Boolean {
            val m = keyWords(message)
            if (m.isEmpty()) return false
            return (m intersect keyWords(stepText)).size >= 2
        }
    }
}
