package com.questoverlay.sailing

import android.content.Intent
import android.widget.LinearLayout
import com.questoverlay.OverlayService
import com.questoverlay.ScreenActivity
import com.questoverlay.Ui
import com.questoverlay.location.LocationState
import com.questoverlay.travel.TravelStore

/**
 * Sailing helper: which ports your level opens, sailing routes between ports, and the sea charting
 * checklist (nearest first when Breadcrumbs knows where you are). "Point" sets the card's arrow.
 */
class SailingActivity : ScreenActivity() {
    private var showAllCharts = false
    private var showRoutes = false

    override fun build() {
        val data = try { SailingData.load(this) } catch (e: Exception) {
            title("Sailing", "Couldn't load the Sailing data.")
            return
        }
        val store = SailingStore(this)
        val level = TravelStore(this).levels["Sailing"]
        title(
            "Sailing",
            (if (level != null) "Sailing level $level. " else "Add your account to see what your Sailing level opens. ") +
                "Tap Point to aim the arrow on the floating card; with 👁 on it turns with your camera and counts down the tiles."
        )

        store.target?.let { t ->
            val c = card("Pointing at")
            rowWithButton(c, t.name, "Tile ${t.x}, ${t.y}", "Stop") {
                store.target = null
                refreshOverlay()
                rebuild()
            }
        }

        val afk = card("While you AFK")
        note(afk, "Salvaging or sailing AFK? Turn on AFK alerts on the main screen: it buzzes when the cargo hold is full (and anything else you tell it to watch for).")

        // Ports.
        val pc = card("Ports")
        for (p in data.ports) {
            val open = level != null && p.level != null && level >= p.level
            val sub = when {
                p.level == null -> "Level not known"
                open -> "Level ${p.level} ✓"
                level != null -> "Level ${p.level} (${p.level - level} to go)"
                else -> "Level ${p.level}"
            }
            rowWithButton(pc, p.name, sub, "Point", if (open) Ui.GREEN else Ui.TEXT) {
                point(store, NavTarget(p.name, p.x, p.y))
            }
        }

        // Routes.
        val rc = card("Sailing routes")
        note(rc, "Waypoints between ports. Pointing at a route steers you waypoint by waypoint.")
        if (!showRoutes) {
            rc.addView(spaced(Ui.button(this, "Show ${data.routes.size} routes", false) { showRoutes = true; rebuild() }, 6))
        } else {
            for (r in data.routes) {
                rowWithButton(rc, "${r.from} → ${r.to}", "${r.points.size} waypoints", "Point") {
                    store.routeIndex = 1
                    val first = r.points.getOrElse(1) { r.points.last() }
                    point(store, NavTarget("${r.to} (waypoint 1 of ${r.points.size - 1})", first.first, first.second, routeKey = "${r.from}>${r.to}"))
                }
            }
        }

        // Sea charting.
        val charted = store.charted
        val cc = card("Sea charting  ${charted.size} / ${data.charts.size}")
        val fix = LocationState.status?.fix?.takeIf { System.currentTimeMillis() - it.at < 120_000 }
        note(cc, if (fix != null) "Nearest to you first." else "Switch on 👁 to sort these by distance from you.")
        val todo = data.charts.filter { it.id !in charted }
        val sorted = if (fix != null) todo.sortedBy { fix.distanceTo(it.x, it.y) } else todo
        val shown = if (showAllCharts) sorted else sorted.take(25)
        for (c in shown) chartRow(cc, store, c, fix?.distanceTo(c.x, c.y))
        if (!showAllCharts && sorted.size > shown.size) {
            cc.addView(spaced(Ui.button(this, "Show all ${sorted.size}", false) { showAllCharts = true; rebuild() }, 6))
        }
        if (charted.isNotEmpty()) {
            val done = card("Charted")
            for (c in data.charts.filter { it.id in charted }) {
                rowWithButton(done, c.title, null, "Undo", Ui.GREEN) {
                    store.charted = store.charted - c.id
                    rebuild()
                }
            }
        }
        val credit = Ui.text(this, "Charting spots from the Sailing RuneLite plugin; ports and routes from the Port Tasks plugin (both BSD 2-Clause).", 11f, Ui.MUTED)
        credit.setPadding(0, Ui.dp(this, 14), 0, 0)
        content.addView(credit)
    }

    private fun chartRow(c: LinearLayout, store: SailingStore, chart: Chart, dist: Int?) {
        val sub = buildString {
            append("Tile ${chart.x}, ${chart.y}")
            if (dist != null) append(" · $dist tiles away")
            if (chart.toX != null) append(" · ends at ${chart.toX}, ${chart.toY}")
        }
        rowWithButtons(c, chart.title, sub, listOf(
            "Point" to { point(store, NavTarget(chart.title, chart.x, chart.y, chartId = chart.id)) },
            "✓" to {
                store.charted = store.charted + chart.id
                if (store.target?.chartId == chart.id) store.target = null
                rebuild()
            }
        ))
    }

    private fun point(store: SailingStore, t: NavTarget) {
        store.target = t
        refreshOverlay()
        rebuild()
    }

    private fun refreshOverlay() {
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
    }
}
