package com.questoverlay.capture

import android.content.Context
import org.json.JSONObject

/** Kill counts per boss and which pets you've got, kept on the phone. */
class KillCountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("killcounts", Context.MODE_PRIVATE)

    var counts: Map<String, Int>
        get() = read("counts")
        set(v) = write("counts", v)

    /** Pets marked as received, by pet name. */
    var gotPets: Set<String>
        get() = prefs.getStringSet("pets", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("pets", v).apply()

    /** Keeps the higher of the stored and new count for each boss (counts never go down). */
    fun merge(newCounts: Map<String, Int>) {
        val m = counts.toMutableMap()
        for ((k, v) in newCounts) {
            val key = Pets.forBoss(k)?.boss ?: k
            if (v > (m[key] ?: 0)) m[key] = v
        }
        counts = m
    }

    fun set(boss: String, count: Int) {
        val m = counts.toMutableMap()
        if (count <= 0) m.remove(boss) else m[boss] = count
        counts = m
    }

    private fun read(key: String): Map<String, Int> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getInt(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun write(key: String, v: Map<String, Int>) {
        val o = JSONObject()
        for ((k, n) in v) o.put(k, n)
        prefs.edit().putString(key, o.toString()).apply()
    }
}
