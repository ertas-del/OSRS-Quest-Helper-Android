package com.questoverlay.slayer

import android.content.Context

/** The task you're on. [remaining] is as of the last time the game told us (assignment or a gem/helm check). */
data class ActiveTask(val master: String, val name: String, val total: Int, val remaining: Int, val place: String?, val at: Long)

/** Your Slayer master, current task, task counts and points, kept on the phone. */
class SlayerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("slayer", Context.MODE_PRIVATE)

    /** The master you're using (the one new tasks are credited to). */
    var master: String
        get() = prefs.getString("master", "konar") ?: "konar"
        set(v) = prefs.edit().putString("master", v).apply()

    /** Tasks completed with each master, as far as we know (the game says "You've completed 235 tasks"). */
    fun count(masterId: String): Int = prefs.getInt("count_$masterId", 0)
    fun setCount(masterId: String, n: Int) = prefs.edit().putInt("count_$masterId", n).apply()

    /** Slayer reward points: the last total the game told us, plus points earned since. */
    var points: Int
        get() = prefs.getInt("points", -1)
        set(v) = prefs.edit().putInt("points", v).apply()

    var task: ActiveTask?
        get() {
            val name = prefs.getString("t_name", null) ?: return null
            return ActiveTask(
                prefs.getString("t_master", "konar") ?: "konar", name, prefs.getInt("t_total", 0), prefs.getInt("t_left", 0),
                prefs.getString("t_place", null), prefs.getLong("t_at", 0L)
            )
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("t_name").remove("t_master").remove("t_total").remove("t_left").remove("t_place").remove("t_at")
            else e.putString("t_name", v.name).putString("t_master", v.master).putInt("t_total", v.total).putInt("t_left", v.remaining)
                .putString("t_place", v.place).putLong("t_at", v.at)
            e.apply()
        }

    /** Nothing left to kill. */
    val taskDone: Boolean get() = task?.let { it.remaining <= 0 } ?: false

    /** Records what a game message said. Returns a short line for the card, or null if nothing changed. */
    fun apply(e: SlayerEvent, now: Long = System.currentTimeMillis()): String? = when (e) {
        is SlayerEvent.NewTask -> {
            val cur = task
            if (cur != null && cur.remaining > 0 && SlayerNames.close(SlayerNames.key(cur.name), SlayerNames.key(e.name)) && cur.total == e.count) null
            else {
                task = ActiveTask(master, e.name, e.count, e.count, e.place, now)
                "New task: ${e.count} ${e.name}" + (e.place?.let { " in $it" } ?: "")
            }
        }
        is SlayerEvent.Progress -> {
            val cur = task
            val place = e.place ?: cur?.place
            val total = if (cur != null && SlayerNames.close(SlayerNames.key(cur.name), SlayerNames.key(e.name))) maxOf(cur.total, e.remaining) else e.remaining
            if (cur != null && cur.remaining == e.remaining && SlayerNames.close(SlayerNames.key(cur.name), SlayerNames.key(e.name))) null
            else {
                task = ActiveTask(cur?.master ?: master, e.name, total, e.remaining, place, now)
                "${e.remaining} ${e.name} to go"
            }
        }
        is SlayerEvent.Completed -> {
            setCount(master, e.tasks)
            if (e.total != null) points = e.total
            task = task?.copy(remaining = 0)
            "Task done: ${e.tasks} with ${master.replaceFirstChar { it.uppercase() }}" + (e.points?.let { ", +$it points" } ?: "")
        }
    }
}
