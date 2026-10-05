package com.questoverlay.travel

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Reads public account data. Nothing here touches the game:
 *  - levels come from Jagex's own public hiscores
 *  - finished quests and diary progress come from WikiSync, which is filled in when you play on
 *    RuneLite (PC)
 */
object AccountSync {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Hiscores order. Lines after the skills are activities and bosses, which we skip. */
    private val SKILLS = listOf(
        "Overall", "Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic",
        "Cooking", "Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing",
        "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecraft", "Hunter",
        "Construction", "Sailing"
    )

    sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        data class Error(val message: String) : Outcome<Nothing>()
    }

    fun fetchLevels(username: String, onDone: (Outcome<Map<String, Int>>) -> Unit) {
        val name = username.trim()
        worker.execute {
            val outcome: Outcome<Map<String, Int>> = try {
                val url = "https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player=" + enc(name)
                val (code, body) = get(url)
                when {
                    code == 404 -> Outcome.Error("\"$name\" isn't on the hiscores. Check the spelling, or your levels may be too low to be ranked.")
                    code != 200 -> Outcome.Error("The hiscores answered with error $code. Try again later.")
                    else -> {
                        val levels = HashMap<String, Int>()
                        val lines = body.lines()
                        for ((i, skill) in SKILLS.withIndex()) {
                            val parts = lines.getOrNull(i)?.split(',') ?: break
                            if (parts.size != 3) break // reached the activity rows
                            val lvl = parts[1].toIntOrNull() ?: continue
                            // Unranked skills come back as -1: we don't know the level, so don't filter on it.
                            if (lvl >= 1) levels[skill] = lvl
                        }
                        // "Overall" is the total level; it isn't a skill, but the account screen shows it.
                        if (levels.keys.none { it != "Overall" }) Outcome.Error("Couldn't read the hiscores reply.") else Outcome.Ok(levels)
                    }
                }
            } catch (e: Exception) {
                Outcome.Error("No connection to the hiscores (${e.javaClass.simpleName}).")
            }
            main.post { onDone(outcome) }
        }
    }

    /** One achievement diary tier from WikiSync, e.g. Ardougne Hard: 7 of 10 tasks. */
    data class DiaryTier(val region: String, val tier: String, val complete: Boolean, val done: Int, val total: Int)

    /** What WikiSync knows about an account. Quest names are as WikiSync spells them. */
    data class WikiSyncData(
        val finished: Set<String>,
        val started: Set<String>,
        val levels: Map<String, Int>,
        val diaries: List<DiaryTier>
    )

    /** Quests, levels and diaries from WikiSync. Only works if the player uses RuneLite's WikiSync plugin. */
    fun fetchWikiSync(username: String, onDone: (Outcome<WikiSyncData>) -> Unit) {
        val name = username.trim()
        worker.execute {
            val outcome: Outcome<WikiSyncData> = try {
                val url = "https://sync.runescape.wiki/runelite/player/" + enc(name).replace("+", "%20") + "/STANDARD"
                val (code, body) = get(url)
                if (code != 200) {
                    Outcome.Error(
                        if (code == 400) "No WikiSync data for \"$name\". It's only there if you've played with RuneLite's WikiSync plugin on."
                        else "WikiSync answered with error $code. Try again later."
                    )
                } else {
                    Outcome.Ok(parseWikiSync(JSONObject(body)))
                }
            } catch (e: Exception) {
                Outcome.Error("No connection to WikiSync (${e.javaClass.simpleName}).")
            }
            main.post { onDone(outcome) }
        }
    }

    fun parseWikiSync(json: JSONObject): WikiSyncData {
        val finished = HashSet<String>()
        val started = HashSet<String>()
        json.optJSONObject("quests")?.let { q ->
            for (key in q.keys()) when (q.optInt(key, 0)) {
                2 -> finished.add(key)
                1 -> started.add(key)
            }
        }
        val levels = HashMap<String, Int>()
        json.optJSONObject("levels")?.let { l ->
            for (key in l.keys()) {
                val lvl = l.optInt(key, 0)
                if (lvl > 0) levels[key.lowercase().replaceFirstChar { it.uppercase() }] = lvl
            }
        }
        val diaries = ArrayList<DiaryTier>()
        json.optJSONObject("achievement_diaries")?.let { regions ->
            for (region in regions.keys()) {
                val tiers = regions.optJSONObject(region) ?: continue
                for (tier in tiers.keys()) {
                    val t = tiers.optJSONObject(tier) ?: continue
                    val tasks = t.optJSONArray("tasks")
                    var doneCount = 0
                    val total = tasks?.length() ?: 0
                    for (i in 0 until total) if (tasks!!.optBoolean(i, false)) doneCount++
                    diaries.add(DiaryTier(region, tier, t.optBoolean("complete", false), doneCount, total))
                }
            }
        }
        return WikiSyncData(finished, started, levels, diaries)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Breadcrumbs/0.2 (Android quest guide for OSRS mobile)")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            c.disconnect()
        }
    }
}
