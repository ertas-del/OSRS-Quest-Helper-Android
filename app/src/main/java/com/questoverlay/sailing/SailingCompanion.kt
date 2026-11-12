package com.questoverlay.sailing

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.questoverlay.MaxHeightScrollView
import com.questoverlay.Ui
import com.questoverlay.location.Fix
import com.questoverlay.travel.TravelStore

/**
 * Floating sailing panel: route, ports, charts, and weather/current charting spots.
 * Distances are tiles from the latest minimap fix and update without rebuilding the card.
 */
class SailingCompanion(
    context: Context,
    private val store: SailingStore,
    initialTab: Int = 0,
    initialFix: Fix? = null,
    private val onTab: (Int) -> Unit = {},
    private val onPoint: (NavTarget) -> Unit,
    private val onStartRoute: (String, String) -> Unit,
    private val onChart: (Int) -> Unit,
    private val onClear: () -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private val data = try { SailingData.load(context) } catch (e: Exception) { null }
    private val level = TravelStore(context).levels["Sailing"]
    private val tabs = listOf("Route", "Ports", "Charts", "Weather")
    private var currentTab = initialTab.coerceIn(0, tabs.lastIndex)
    private var rawFix: Fix? = initialFix
    private val fresh: Fix? get() = rawFix?.takeIf { System.currentTimeMillis() - it.at < 120_000 }
    private val live = mutableListOf<LiveText>()

    private val body = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        background = Ui.rounded(context, Ui.PANEL_BG, 0, Ui.STROKE, 1)
        setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
        rebuild()
    }

    fun refresh(raw: Fix?) {
        rawFix = raw
        val f = fresh
        for (row in live) {
            row.view.text = row.text(f)
            row.view.setTextColor(row.color(f))
        }
    }

    private fun rebuild() {
        removeAllViews()
        live.clear()
        (body.parent as? ViewGroup)?.removeView(body)
        body.removeAllViews()
        if (data == null) {
            addView(Ui.text(context, "Could not load sailing data.", 14f, Ui.RED))
            return
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            Ui.text(context, "\u2693 Sailing", 16f, Ui.GOLD, bold = true),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(Ui.text(context, "Lvl ${level ?: "?"}", 13f, Ui.TAN, bold = true))
        val close = Ui.text(context, "\u2715", 16f, Ui.GOLD, bold = true)
        close.setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 2), 0)
        close.setOnClickListener { onClose() }
        header.addView(close)
        addView(header)

        val tabRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 6))
        }
        tabs.forEachIndexed { i, name ->
            val chip = Ui.chip(context, name, i == currentTab, 12f)
            chip.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i != tabs.lastIndex) marginEnd = Ui.dp(context, 4)
            }
            chip.setOnClickListener {
                currentTab = i
                onTab(i)
                rebuild()
            }
            tabRow.addView(chip)
        }
        addView(tabRow)

        body.removeAllViews()
        when (currentTab) {
            0 -> buildRoute()
            1 -> buildPorts()
            2 -> buildCharts()
            else -> buildWeather()
        }
        val scroll = MaxHeightScrollView(context, Ui.dp(context, 220))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(body)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refresh(rawFix)
    }

    private fun buildRoute() {
        val d = data ?: return
        val target = store.target
        val here = card()
        here.addView(Ui.text(context, "WHERE YOU ARE", 12f, Ui.TAN, bold = true))
        val nearest = Ui.text(context, "", 13f, Ui.GREEN)
        here.addView(nearest)
        live += LiveText(nearest, { f ->
            val hit = f?.let { SailPlanner.nearestPort(d.ports, it.tileX, it.tileY) }
            if (hit != null) "Nearest: ${hit.first.name} \u00B7 ${hit.second} tiles" else "Switch on \uD83D\uDC41 to see live distances"
        }, { f -> if (f == null) Ui.MUTED else Ui.GREEN })
        body.addView(here)

        if (target != null) {
            val active = card()
            active.addView(Ui.text(context, "POINTING AT", 12f, Ui.TAN, bold = true))
            active.addView(Ui.text(context, target.name, 14f, Ui.TEXT, bold = true))
            val dist = Ui.text(context, "", 13f, Ui.GREEN)
            active.addView(dist)
            live += LiveText(dist, { f ->
                if (f == null) "Distance unknown" else "${f.distanceTo(target.x, target.y)} tiles"
            }, { f -> if (f == null) Ui.MUTED else Ui.GREEN })
            if (target.routeKey.isNotEmpty()) upcoming(active, target)
            val stop = Ui.text(context, "Stop pointing", 13f, Ui.GOLD, bold = true)
            stop.setPadding(0, Ui.dp(context, 6), 0, 0)
            stop.setOnClickListener { onClear() }
            active.addView(stop)
            body.addView(active)
        }

        val hereName = fresh?.let { SailPlanner.nearestPort(d.ports, it.tileX, it.tileY) }?.takeIf { it.second <= 200 }?.first?.name
        val levelOf = d.ports.associate { it.name to it.level }
        val legs = d.routes.flatMap { listOf(it.from to it.to, it.to to it.from) }
            .distinct()
            .sortedWith(compareBy({ if (it.first == hereName) 0 else 1 }, { it.first }, { it.second }))
        if (legs.isNotEmpty()) {
            body.addView(section(if (hereName != null) "Routes \u00B7 $hereName first" else "Routes"))
            for ((from, to) in legs) {
            val pts = SailPlanner.points(d.routes, from, to) ?: continue
            val need = levelOf[to]
            val have = level
            val locked = have != null && need != null && have < need
            val row = card()
            row.addView(Ui.text(context, "$from \u2192 $to", 14f, if (locked) Ui.MUTED else Ui.TEXT, bold = true))
            val sub = Ui.text(context, "", 12f, Ui.MUTED)
            row.addView(sub)
            live += LiveText(sub, { f ->
                val port = d.ports.find { it.name == from }
                val away = if (f != null && port != null) "${f.distanceTo(port.x, port.y)} tiles \u00B7 " else ""
                away + "${pts.size - 1} waypoints" +
                    (if (need != null) " \u00B7 Sailing $need" else "") +
                    (if (locked) " \uD83D\uDD12" else "")
            }, { _ -> if (locked) Ui.MUTED else Ui.GREEN })
            if (!locked) row.setOnClickListener { onStartRoute(from, to) }
            body.addView(row)
            }
        }
    }

    private fun upcoming(parent: LinearLayout, target: NavTarget) {
        val d = data ?: return
        val parts = target.routeKey.split('>')
        if (parts.size != 2) return
        val pts = SailPlanner.points(d.routes, parts[0], parts[1]) ?: return
        val last = pts.size - 1
        if (last < 1) return
        val start = store.routeIndex.coerceIn(1, last)
        for (i in start..minOf(last, start + 3)) {
            val (x, y) = pts[i]
            val label = if (i == last) "${parts[1]} \u00B7 last stretch" else "Waypoint $i of $last"
            val line = Ui.text(context, "", 12f, Ui.TEXT)
            parent.addView(line)
            live += LiveText(line, { f ->
                val away = f?.distanceTo(x, y)?.let { " \u00B7 $it tiles" } ?: ""
                label + away
            }, { _ -> Ui.TEXT })
        }
    }

    private fun buildPorts() {
        val d = data ?: return
        val ports = d.ports.sortedBy { p -> fresh?.distanceTo(p.x, p.y) ?: Int.MAX_VALUE }
        for (p in ports) {
            val req = p.level
            val have = level
            val open = have != null && req != null && have >= req
            val locked = have != null && req != null && have < req
            val row = card()
            row.addView(Ui.text(context, p.name, 14f, if (open) Ui.GREEN else if (locked) Ui.MUTED else Ui.TEXT, bold = true))
            val sub = Ui.text(context, "", 12f, Ui.MUTED)
            row.addView(sub)
            live += LiveText(sub, { f ->
                val tiles = f?.distanceTo(p.x, p.y)?.let { "$it tiles" } ?: "\u2014"
                val lvl = when {
                    p.level == null -> "level unknown"
                    open -> "level ${p.level} \u2713"
                    locked -> {
                        val need = req ?: 0
                        val got = have ?: need
                        "level $need \u00B7 ${need - got} to go"
                    }
                    else -> "level ${p.level}"
                }
                "$tiles  \u00B7  $lvl"
            }, { f ->
                val near = f != null && f.distanceTo(p.x, p.y) < 100
                if (near && !locked) Ui.GREEN else Ui.MUTED
            })
            row.setOnClickListener { onPoint(NavTarget(p.name, p.x, p.y)) }
            body.addView(row)
        }
    }

    private fun buildCharts() {
        val d = data ?: return
        val todo = d.charts.filter { it.id !in store.charted }
        body.addView(section("Sea charting  ${store.charted.size} / ${d.charts.size}"))
        if (todo.isEmpty()) {
            body.addView(Ui.text(context, "Every charting spot is marked done.", 13f, Ui.GREEN))
            return
        }
        val sorted = todo.sortedBy { c -> fresh?.distanceTo(c.x, c.y) ?: Int.MAX_VALUE }
        body.addView(Ui.text(context, "${sorted.size} left \u00B7 nearest first. Tap to point, \u2713 when charted.", 12f, Ui.MUTED))
        for (c in sorted.take(8)) chartRow(c)
    }

    private fun buildWeather() {
        val d = data ?: return
        body.addView(Ui.text(context, "Charting spots for weather and currents. Not a live forecast.", 12f, Ui.MUTED))
        spotList("Weather", d.charts.filter { it.kind == "Weather" })
        spotList("Currents", d.charts.filter { it.kind == "Current duck" })
    }

    private fun spotList(title: String, spots: List<Chart>) {
        val todo = spots.filter { it.id !in store.charted }
            .sortedBy { c -> fresh?.distanceTo(c.x, c.y) ?: Int.MAX_VALUE }
        body.addView(section("$title  ${spots.size - todo.size} / ${spots.size}"))
        if (todo.isEmpty()) {
            body.addView(Ui.text(context, "All $title spots charted.", 13f, Ui.GREEN))
            return
        }
        for (c in todo.take(4)) chartRow(c)
    }

    private fun chartRow(c: Chart) {
        val row = card()
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(Ui.text(context, c.title, 13f, Ui.TEXT, bold = true))
        val sub = Ui.text(context, "", 12f, Ui.GREEN)
        col.addView(sub)
        live += LiveText(sub, { f ->
            val tiles = f?.distanceTo(c.x, c.y)?.let { "$it tiles" } ?: "\u2014"
            if (c.toX != null) "$tiles \u00B7 ends ${c.toX}, ${c.toY}" else tiles
        }, { f -> if (f == null) Ui.MUTED else Ui.GREEN })
        row.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val mark = Ui.text(context, "\u2713", 16f, Ui.GREEN, bold = true)
        mark.setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4))
        mark.setOnClickListener { onChart(c.id) }
        row.addView(mark)
        col.setOnClickListener { onPoint(NavTarget(c.title, c.x, c.y, chartId = c.id)) }
        body.addView(row)
    }

    private fun section(label: String): TextView {
        val t = Ui.text(context, label, 12f, Ui.TAN, bold = true)
        t.setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
        return t
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        background = Ui.rounded(context, Ui.CARD_BG, 0, Ui.STROKE, 1)
        setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Ui.dp(context, 6)
        layoutParams = lp
    }

    private data class LiveText(val view: TextView, val text: (Fix?) -> String, val color: (Fix?) -> Int)
}
