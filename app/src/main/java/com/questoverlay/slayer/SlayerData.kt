package com.questoverlay.slayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SlayerMaster(val id: String, val name: String, val where: String, val slayer: Int, val combat: Int, val points: Int, val diaryPoints: Int, val note: String)

/** One line of a master's task table. [points], [xp] and [qty] are Mortimer's modifiers (text ranges). */
data class TaskRow(
    val id: String, val name: String, val slayer: Int, val combat: Int, val lo: Int, val hi: Int, val weight: Int,
    val needs: String, val places: List<String>, val points: String?, val xp: String?, val qty: String?, val hasCard: Boolean
) {
    val kills: String get() = if (lo == hi) "$lo" else "$lo-$hi"
}

data class MonsterLoc(val name: String, val how: String, val multi: Boolean?, val cannon: Boolean?, val note: String?)
data class GearTier(val label: String, val items: List<String>)

/** Everything the monster card shows. Gear and food are suggestions; max hits and routes come from the wiki. */
data class MonsterCard(
    val id: String, val name: String, val maxHit: Int?, val attackStyles: List<String>, val protectPrayer: String?, val weakness: String?,
    val locations: List<MonsterLoc>, val mustBring: List<String>, val gearStyle: String?, val tiers: List<GearTier>,
    val foodAmount: String, val foodNote: String?, val tips: List<String>, val source: String?
)

/** Slayer masters, their task tables and the monster cards (assets/slayer.json, built by tools/build_slayer.py). */
class SlayerData(
    val masters: List<SlayerMaster>,
    val tables: Map<String, List<TaskRow>>,
    val cards: List<MonsterCard>,
    /** Every Nth task pays the master's per-task points times this. */
    val bonusEvery: List<Pair<Int, Int>>
) {
    fun master(id: String): SlayerMaster? = masters.firstOrNull { it.id == id }
    fun card(id: String): MonsterCard? = cards.firstOrNull { it.id == id }

    /** The table row a task name from the game ("Black demons") belongs to, for [masterId] first, then any master. */
    fun matchRow(masterId: String, name: String): TaskRow? =
        SlayerNames.pick(tables[masterId].orEmpty(), name) ?: tables.values.firstNotNullOfOrNull { SlayerNames.pick(it, name) }

    fun matchCard(name: String): MonsterCard? {
        val n = SlayerNames.key(name)
        return cards.firstOrNull { SlayerNames.key(it.name) == n } ?: cards.firstOrNull { SlayerNames.close(SlayerNames.key(it.name), n) }
    }

    companion object {
        @Volatile private var cache: SlayerData? = null

        fun load(context: Context): SlayerData {
            cache?.let { return it }
            val o = JSONObject(context.applicationContext.assets.open("slayer.json").bufferedReader().use { it.readText() })
            return parse(o).also { cache = it }
        }

        fun parse(o: JSONObject): SlayerData {
            fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            fun optStr(j: JSONObject, k: String): String? = if (j.isNull(k) || !j.has(k)) null else j.getString(k)
            fun optBool(j: JSONObject, k: String): Boolean? = if (j.isNull(k) || !j.has(k)) null else j.getBoolean(k)
            val masters = o.getJSONArray("masters").let { a ->
                (0 until a.length()).map {
                    val m = a.getJSONObject(it)
                    SlayerMaster(m.getString("id"), m.getString("name"), m.getString("where"), m.optInt("slayer"), m.optInt("combat"),
                        m.optInt("points"), m.optInt("diary"), m.optString("note", ""))
                }
            }
            val tables = HashMap<String, List<TaskRow>>()
            val t = o.getJSONObject("tables")
            for (k in t.keys()) {
                val a = t.getJSONArray(k)
                tables[k] = (0 until a.length()).map {
                    val r = a.getJSONObject(it)
                    TaskRow(r.getString("id"), r.getString("name"), r.optInt("slayer"), r.optInt("combat"), r.optInt("lo"), r.optInt("hi"),
                        r.optInt("weight"), r.optString("needs", ""), strs(r.optJSONArray("places")), optStr(r, "points"), optStr(r, "xp"),
                        optStr(r, "qty"), r.optBoolean("card"))
                }
            }
            val cards = o.getJSONArray("monsters").let { a ->
                (0 until a.length()).map {
                    val m = a.getJSONObject(it)
                    val locs = m.optJSONArray("locations")?.let { l ->
                        (0 until l.length()).map { i ->
                            val x = l.getJSONObject(i)
                            MonsterLoc(x.optString("name"), x.optString("how"), optBool(x, "multi"), optBool(x, "cannon"), optStr(x, "note"))
                        }
                    } ?: emptyList()
                    val gear = m.optJSONObject("gear")
                    val tiers = gear?.optJSONArray("tiers")?.let { g ->
                        (0 until g.length()).map { i -> g.getJSONObject(i).let { GearTier(it.optString("label"), strs(it.optJSONArray("items"))) } }
                    } ?: emptyList()
                    val food = m.optJSONObject("food")
                    MonsterCard(
                        m.getString("id"), m.getString("name"), if (m.isNull("maxHit")) null else m.optInt("maxHit"), strs(m.optJSONArray("attackStyles")),
                        optStr(m, "protectPrayer"), optStr(m, "weakness"), locs, strs(m.optJSONArray("mustBring")),
                        gear?.let { optStr(it, "style") }, tiers, food?.optString("amount") ?: "", food?.let { optStr(it, "note") },
                        strs(m.optJSONArray("tips")), optStr(m, "source")
                    )
                }
            }
            val bonus = o.getJSONArray("bonusEvery").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getInt(0) to p.getInt(1) } } }
            return SlayerData(masters, tables, cards, bonus)
        }
    }
}

/** Loose name matching: "Black demons", "black demon" and an OCR'd "blackdemons" are the same task. */
object SlayerNames {
    fun key(s: String): String = s.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z0-9]+"), " ").trim()
        .split(' ').joinToString(" ") { w -> if (w.length > 3 && w.endsWith("s")) w.dropLast(1) else w }

    fun close(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val x = a.replace(" ", "")
        val y = b.replace(" ", "")
        if (x == y) return true
        // One or two letters misread.
        if (kotlin.math.abs(x.length - y.length) > 2) return false
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[y.length] <= maxOf(1, x.length / 8)
    }

    fun pick(rows: List<TaskRow>, name: String): TaskRow? {
        val n = key(name)
        return rows.firstOrNull { key(it.name) == n } ?: rows.firstOrNull { close(key(it.name), n) }
    }
}
