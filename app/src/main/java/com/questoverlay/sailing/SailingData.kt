package com.questoverlay.sailing

import android.content.Context
import org.json.JSONObject

data class Port(val name: String, val level: Int?, val x: Int, val y: Int)
data class SailRoute(val from: String, val to: String, val points: List<Pair<Int, Int>>)
data class Chart(val id: Int, val kind: String, val label: String, val x: Int, val y: Int, val toX: Int?, val toY: Int?) {
    val title: String get() = "$kind · $label"
}

/** Ports, sailing routes and sea-charting spots (assets/sailing.json, from two BSD RuneLite plugins). */
class SailingData(val ports: List<Port>, val routes: List<SailRoute>, val charts: List<Chart>) {
    companion object {
        @Volatile private var cache: SailingData? = null

        fun load(context: Context): SailingData {
            cache?.let { return it }
            val o = JSONObject(context.applicationContext.assets.open("sailing.json").bufferedReader().use { it.readText() })
            val ports = o.getJSONArray("ports").let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    Port(p.getString("name"), if (p.isNull("level")) null else p.getInt("level"), p.getInt("x"), p.getInt("y"))
                }
            }
            val routes = o.getJSONArray("routes").let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    val pts = r.getJSONArray("points")
                    SailRoute(r.getString("from"), r.getString("to"), (0 until pts.length()).map { k ->
                        val pt = pts.getJSONArray(k)
                        pt.getInt(0) to pt.getInt(1)
                    })
                }
            }
            val charts = o.getJSONArray("charts").let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    val to = c.optJSONArray("to")
                    Chart(c.getInt("id"), c.getString("kind"), c.getString("label"), c.getInt("x"), c.getInt("y"), to?.getInt(0), to?.getInt(1))
                }
            }
            return SailingData(ports, routes, charts).also { cache = it }
        }
    }
}

/** Something to steer towards with the card's arrow: a port, a charting spot, or a route's next waypoint. */
data class NavTarget(val name: String, val x: Int, val y: Int, val chartId: Int = -1, val routeKey: String = "")

/** Charting progress and the current arrow target, kept on the phone. */
class SailingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sailing", Context.MODE_PRIVATE)

    var charted: Set<Int>
        get() = prefs.getStringSet("charted", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("charted", v.map { it.toString() }.toSet()).apply()

    var target: NavTarget?
        get() {
            val name = prefs.getString("t_name", null) ?: return null
            return NavTarget(name, prefs.getInt("t_x", 0), prefs.getInt("t_y", 0), prefs.getInt("t_chart", -1), prefs.getString("t_route", "") ?: "")
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("t_name") else e.putString("t_name", v.name).putInt("t_x", v.x).putInt("t_y", v.y).putInt("t_chart", v.chartId).putString("t_route", v.routeKey)
            e.apply()
        }

    /** For routes: how far along the waypoints you are. */
    var routeIndex: Int
        get() = prefs.getInt("t_route_i", 0)
        set(v) = prefs.edit().putInt("t_route_i", v).apply()
}
