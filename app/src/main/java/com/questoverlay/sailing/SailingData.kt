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

/** Pure helpers for planning a sailing route from the data; used by the floating card. */
object SailPlanner {
    /** Waypoints for from→to, using the reverse of a stored route when only the other direction exists. Index 0 is the start port. */
    fun points(routes: List<SailRoute>, from: String, to: String): List<Pair<Int, Int>>? {
        routes.firstOrNull { it.from == from && it.to == to }?.let { return it.points }
        return routes.firstOrNull { it.from == to && it.to == from }?.points?.reversed()
    }

    fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Int = maxOf(Math.abs(x1 - x2), Math.abs(y1 - y2))

    /** The port nearest (x, y) and how far away it is. */
    fun nearestPort(ports: List<Port>, x: Int, y: Int): Pair<Port, Int>? =
        ports.map { it to distance(it.x, it.y, x, y) }.minByOrNull { it.second }

    /** Which waypoint to aim at when joining a route at (x, y): the nearest one, or the one after it if you're already on it. */
    fun firstTarget(points: List<Pair<Int, Int>>, x: Int, y: Int): Int {
        if (points.size < 2) return 0
        var best = 0
        var bestD = Int.MAX_VALUE
        points.forEachIndexed { i, p ->
            val d = distance(p.first, p.second, x, y)
            if (d < bestD) { bestD = d; best = i }
        }
        return (if (bestD <= ARRIVE) best + 1 else best).coerceIn(1, points.size - 1)
    }

    /** The arrow target for waypoint [i] (1..size-1) of a route, or the destination once past the last one. */
    fun target(to: String, from: String, points: List<Pair<Int, Int>>, i: Int): NavTarget {
        val last = points.size - 1
        val key = "$from>$to"
        return if (i <= last) {
            NavTarget(if (i == last) "$to (last stretch)" else "$to (waypoint $i of $last)", points[i].first, points[i].second, routeKey = key)
        } else {
            NavTarget("$to (arrived)", points[last].first, points[last].second)
        }
    }

    /** Close enough to count as having reached a waypoint. */
    const val ARRIVE = 8
}

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
