package com.questoverlay.puzzles

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One puzzle's help, loaded from assets/puzzles.json (built by tools/build_puzzles.py). */
data class Puzzle(
    val id: String,
    val quest: String,
    val title: String,
    val kind: String,          // "guide" or "solver"
    val solver: String,        // solver name when kind == "solver"
    val match: List<Regex>,    // step texts this puzzle belongs to
    val intro: String,
    val steps: List<String>,
    val notes: List<String>,
    val grid: List<String>,
    val config: JSONObject
) {
    fun matches(stepText: String): Boolean = match.any { it.containsMatchIn(stepText) }
}

object PuzzleRepository {
    @Volatile private var cache: Map<String, List<Puzzle>>? = null

    /** Puzzles grouped by quest id. */
    fun byQuest(context: Context): Map<String, List<Puzzle>> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val map = LinkedHashMap<String, MutableList<Puzzle>>()
            try {
                val text = context.applicationContext.assets.open("puzzles.json").bufferedReader().use { it.readText() }
                val arr = JSONObject(text).getJSONArray("puzzles")
                for (i in 0 until arr.length()) {
                    val p = parse(arr.getJSONObject(i)) ?: continue
                    map.getOrPut(p.quest) { ArrayList() }.add(p)
                }
            } catch (e: Exception) {
                // No puzzle help is better than a crash.
            }
            cache = map
            return map
        }
    }

    fun forQuest(context: Context, questId: String): List<Puzzle> = byQuest(context)[questId] ?: emptyList()

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }

    private fun parse(o: JSONObject): Puzzle? {
        val id = o.optString("id", "")
        if (id.isEmpty()) return null
        return Puzzle(
            id = id,
            quest = o.optString("quest", ""),
            title = o.optString("title", id),
            kind = o.optString("kind", "guide"),
            solver = o.optString("solver", ""),
            match = strings(o.optJSONArray("match")).mapNotNull {
                try {
                    Regex(it, RegexOption.IGNORE_CASE)
                } catch (e: Exception) {
                    null
                }
            },
            intro = o.optString("intro", ""),
            steps = strings(o.optJSONArray("steps")),
            notes = strings(o.optJSONArray("notes")),
            grid = strings(o.optJSONArray("grid")),
            config = o.optJSONObject("config") ?: JSONObject()
        )
    }
}
