package com.questoverlay

import android.content.Context
import android.content.SharedPreferences

/** Remembers quest progress, ticked items, overlay position and opacity. */
class ProgressStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var activeQuestId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().putString(KEY_ACTIVE, value).apply()
        }

    var opacity: Float
        get() = prefs.getFloat(KEY_OPACITY, 0.92f).coerceIn(0.4f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_OPACITY, value.coerceIn(0.4f, 1f)).apply()
        }

    val overlayX: Int?
        get() = if (prefs.contains(KEY_X)) prefs.getInt(KEY_X, 0) else null

    val overlayY: Int?
        get() = if (prefs.contains(KEY_Y)) prefs.getInt(KEY_Y, 0) else null

    fun savePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /**
     * Index of the step you are on (equal to the step count once finished).
     * The step's text is saved too, so if a data update inserts or reorders steps,
     * you stay on the same step rather than the same number.
     */
    fun stepIndex(q: Quest): Int {
        val saved = prefs.getInt("v2_step_${q.id}", 0)
        if (saved <= 0) return 0
        if (saved >= q.steps.size) {
            return if (prefs.getString("v2_steptext_${q.id}", null) == DONE) q.steps.size
            else q.steps.size.coerceAtMost(saved)
        }
        val text = prefs.getString("v2_steptext_${q.id}", null) ?: return saved
        if (q.steps[saved].text == text) return saved
        val moved = q.steps.indexOfFirst { it.text == text }
        return if (moved >= 0) moved else saved
    }

    fun setStepIndex(q: Quest, index: Int) {
        val i = index.coerceIn(0, q.steps.size)
        val text = if (i >= q.steps.size) DONE else q.steps[i].text
        prefs.edit().putInt("v2_step_${q.id}", i).putString("v2_steptext_${q.id}", text).apply()
    }

    /** Positions in q.items that are ticked. Saved by item name so updates don't shuffle ticks. */
    fun checkedItems(q: Quest): Set<Int> {
        val names = prefs.getStringSet("v2_items_${q.id}", null) ?: return emptySet()
        return q.items.indices.filter { names.contains(q.items[it].name) }.toSet()
    }

    fun setItemChecked(q: Quest, index: Int, checked: Boolean) {
        val name = q.items.getOrNull(index)?.name ?: return
        val current = (prefs.getStringSet("v2_items_${q.id}", null) ?: emptySet()).toMutableSet()
        if (checked) current.add(name) else current.remove(name)
        prefs.edit().putStringSet("v2_items_${q.id}", current).apply()
    }

    fun reset(questId: String) {
        prefs.edit()
            .remove("v2_step_$questId")
            .remove("v2_steptext_$questId")
            .remove("v2_items_$questId")
            .apply()
    }

    private companion object {
        const val FILE = "quest_progress"
        const val DONE = "\u0000done"
        const val KEY_ACTIVE = "active_quest"
        const val KEY_OPACITY = "opacity"
        const val KEY_X = "overlay_x"
        const val KEY_Y = "overlay_y"
    }
}
