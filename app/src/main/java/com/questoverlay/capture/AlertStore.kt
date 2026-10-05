package com.questoverlay.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Which AFK alerts are switched on, and the ones you added with "Alert me on this". */
class AlertStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    fun isOn(rule: AlertRule): Boolean = prefs.getBoolean("on_${rule.id}", true)

    fun setOn(rule: AlertRule, on: Boolean) = prefs.edit().putBoolean("on_${rule.id}", on).apply()

    var custom: List<AlertRule>
        get() {
            val raw = prefs.getString("custom", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    AlertRule(o.getString("id"), o.getString("label"), listOf(o.getString("pattern")), builtIn = false)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            for (r in v) arr.put(JSONObject().put("id", r.id).put("label", r.label).put("pattern", r.patterns.firstOrNull() ?: ""))
            prefs.edit().putString("custom", arr.toString()).apply()
        }

    fun addCustom(label: String, pattern: String) {
        val p = pattern.trim()
        if (p.isEmpty()) return
        val rule = AlertRule("custom_" + System.currentTimeMillis(), label.trim().ifEmpty { p.take(40) }, listOf(p), builtIn = false)
        custom = custom + rule
    }

    fun removeCustom(id: String) {
        custom = custom.filter { it.id != id }
    }

    /** The rules to check right now. */
    fun active(): List<AlertRule> =
        if (!enabled) emptyList()
        else BuiltInAlerts.ALL.filter { isOn(it) } + custom.filter { isOn(it) }
}
