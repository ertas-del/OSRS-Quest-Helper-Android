package com.questoverlay.location

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.SharedPreferences

/**
 * The world map pictures bundled with the app (assets/worldmap), loaded the first time "Where am I"
 * is needed: about 30 MB of memory while loaded. Map tiles by mejrs (layers_osrs), rendered from
 * the game's own map data; personal use.
 */
object WorldMaps {
    @Volatile private var cache: List<MapRegion>? = null

    /** Loads (or returns) the map. Slow the first time (~1 s): call it off the main thread. */
    fun load(context: Context): List<MapRegion> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val assets = context.applicationContext.assets
            val out = ArrayList<MapRegion>()
            assets.open("worldmap/regions.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#") || line.isBlank()) continue
                    val c = line.split('\t')
                    if (c.size < 6) continue
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val bmp = assets.open("worldmap/" + c[1]).use { BitmapFactory.decodeStream(it, null, opts) } ?: continue
                    val w = bmp.width
                    val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    out.add(MapRegion(c[0], w, h, px, c[2].toInt(), c[3].toInt()))
                }
            }
            cache = out
            return out
        }
    }

    val loaded: List<MapRegion>? get() = cache

    /** Frees the memory when "Where am I" is switched off. */
    fun release() {
        cache = null
    }
}

/** The locator's saved settings. */
class LocatorStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("locator", Context.MODE_PRIVATE)

    /** Read the minimap while Auto-check is on. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** The minimap zoom that matched last time (0 = not learned yet). */
    var zoom: Float
        get() = prefs.getFloat("zoom", 0f)
        set(v) = prefs.edit().putFloat("zoom", v).apply()

    /** Ask "Looks done?" on reaching a step's spot. */
    var arrivalCheck: Boolean
        get() = prefs.getBoolean("arrival", true)
        set(v) = prefs.edit().putBoolean("arrival", v).apply()
}

/** The latest result, shared with the "Where am I" screen. */
object LocationState {
    @Volatile var status: LocatorStatus? = null
    @Volatile var updatedAt: Long = 0L
    @Volatile var calibration: Calibration? = null
    @Volatile var zoom: Float = 0f
}
