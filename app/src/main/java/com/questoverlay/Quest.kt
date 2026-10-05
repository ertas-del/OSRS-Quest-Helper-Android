package com.questoverlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Direction of travel for a step. The first eight are compass bearings. */
enum class Dir(val key: String, val label: String, val degrees: Float) {
    N("N", "Head north", 0f),
    NE("NE", "Head north-east", 45f),
    E("E", "Head east", 90f),
    SE("SE", "Head south-east", 135f),
    S("S", "Head south", 180f),
    SW("SW", "Head south-west", 225f),
    W("W", "Head west", 270f),
    NW("NW", "Head north-west", 315f),
    UP("UP", "Climb up", -1f),
    DOWN("DOWN", "Climb down", -1f),
    HERE("HERE", "Right around here", -1f),
    NONE("NONE", "", -1f);

    val isCompass: Boolean get() = degrees >= 0f

    companion object {
        fun from(key: String?): Dir =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: NONE
    }
}

data class Item(val name: String, val qty: Int, val note: String, val recommended: Boolean) {
    val label: String get() = if (qty > 1) "$qty× $name" else name
}

/** A map tile in game coordinates. */
data class Tile(val x: Int, val y: Int, val plane: Int) {
    /** Opens the community OSRS world map centred on this tile. */
    val mapUrl: String get() = "https://mejrs.github.io/osrs?m=-1&z=3&p=$plane&x=$x&y=$y"
}

data class Step(
    val text: String,
    val section: String,
    val dir: Dir,
    val distance: Int,
    val chat: List<String>,
    val bring: List<String>,
    val tile: Tile?,
    /** Who a "Talk to ..." step is about, for recognising the conversation on screen. */
    val npc: String = ""
) {
    /** "Head north-east · about 60 tiles" */
    val directionLabel: String
        get() = when {
            dir.isCompass && distance > 0 -> "${dir.label} · about ${roundTiles(distance)} tiles"
            else -> dir.label
        }

    private fun roundTiles(d: Int): Int = when {
        d < 30 -> d
        d < 200 -> (d + 5) / 10 * 10
        else -> (d + 25) / 50 * 50
    }
}

data class Quest(
    val id: String,
    val name: String,
    val type: String,
    val difficulty: String,
    val requirements: List<String>,
    val wikiUrl: String,
    val items: List<Item>,
    val steps: List<Step>,
    /** Quest points awarded (0 for miniquests). */
    val qp: Int = 0
) {
    val requiredItems: List<Item> get() = items.filter { !it.recommended }
    val recommendedItems: List<Item> get() = items.filter { it.recommended }
}

object QuestRepository {

    @Volatile
    private var cache: List<Quest>? = null

    /** Parses assets/quests.json once per process; the activity and the overlay share it. */
    fun load(context: Context): List<Quest> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val text = context.applicationContext.assets.open("quests.json")
                .bufferedReader().use { it.readText() }
            val quests = JSONObject(text).getJSONArray("quests")
            val result = ArrayList<Quest>(quests.length())
            for (i in 0 until quests.length()) {
                val q = parseQuest(quests.getJSONObject(i))
                if (q.steps.isNotEmpty()) result.add(q)
            }
            cache = result
            return result
        }
    }

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    private fun parseQuest(o: JSONObject): Quest {
        val items = ArrayList<Item>()
        val itemArray = o.optJSONArray("items")
        if (itemArray != null) {
            for (i in 0 until itemArray.length()) {
                val io = itemArray.getJSONObject(i)
                items.add(
                    Item(
                        name = io.getString("name"),
                        qty = io.optInt("qty", 1).coerceAtLeast(1),
                        note = io.optString("note", ""),
                        recommended = io.optBoolean("rec", false)
                    )
                )
            }
        }

        val steps = ArrayList<Step>()
        val stepArray = o.getJSONArray("steps")
        for (i in 0 until stepArray.length()) {
            val so = stepArray.getJSONObject(i)
            val mapArr = so.optJSONArray("map")
            val tile = if (mapArr != null && mapArr.length() == 3) {
                Tile(mapArr.getInt(0), mapArr.getInt(1), mapArr.getInt(2))
            } else {
                null
            }
            steps.add(
                Step(
                    text = so.getString("text"),
                    section = so.optString("section", ""),
                    dir = Dir.from(so.optString("dir", "NONE")),
                    distance = so.optInt("dist", 0),
                    chat = strings(so.optJSONArray("chat")),
                    bring = strings(so.optJSONArray("bring")),
                    tile = tile,
                    npc = so.optString("npc", "")
                )
            )
        }

        // Older hand-written files used a single "requirements" string.
        val reqArr = o.optJSONArray("requirements")
        val requirements = if (reqArr != null) {
            strings(reqArr)
        } else {
            val single = o.optString("requirements", "")
            if (single.isBlank() || single.equals("None", ignoreCase = true)) emptyList() else listOf(single)
        }

        return Quest(
            id = o.getString("id"),
            name = o.getString("name"),
            type = o.optString("type", ""),
            difficulty = o.optString("difficulty", ""),
            requirements = requirements,
            wikiUrl = o.optString("wikiUrl", ""),
            items = items,
            steps = steps,
            qp = o.optInt("qp", 0)
        )
    }
}
