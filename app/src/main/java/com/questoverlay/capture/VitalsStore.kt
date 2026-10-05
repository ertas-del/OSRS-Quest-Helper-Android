package com.questoverlay.capture

import android.content.Context

/** The player's vitals-warning choices. Max HP and prayer come from the hiscores levels. */
class VitalsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vitals", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
    var hpPercent: Int
        get() = prefs.getInt("hp", 50)
        set(v) = prefs.edit().putInt("hp", v).apply()
    var prayerPoints: Int
        get() = prefs.getInt("prayer", 10)
        set(v) = prefs.edit().putInt("prayer", v).apply()
    var runPercent: Int
        get() = prefs.getInt("run", 0)
        set(v) = prefs.edit().putInt("run", v).apply()

    fun settings(levels: Map<String, Int>) = VitalsSettings(
        enabled = enabled,
        hpPercent = hpPercent,
        prayerPoints = prayerPoints,
        runPercent = runPercent,
        maxHp = levels["Hitpoints"],
        maxPrayer = levels["Prayer"]
    )
}
