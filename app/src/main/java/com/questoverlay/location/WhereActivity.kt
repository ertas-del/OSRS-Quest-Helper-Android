package com.questoverlay.location

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.questoverlay.ScreenActivity
import com.questoverlay.Ui
import com.questoverlay.travel.Packed
import com.questoverlay.travel.TravelEngine

/**
 * "Where am I": what the minimap reader last worked out, a piece of the map with your dot, and
 * its settings. Updates every couple of seconds while Auto-check is on.
 */
class WhereActivity : ScreenActivity() {
    private val tick = object : Runnable {
        override fun run() {
            rebuild()
            main.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 2000)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    override fun build() {
        title(
            "Where am I",
            "With 👁 Auto-check on, Breadcrumbs matches your minimap against the world map to find where you're " +
                "standing. Routes then start from there, and the arrow on the card points the way from where you are."
        )
        val store = LocatorStore(this)
        val st = LocationState.status
        val c = card("Right now")
        val age = (System.currentTimeMillis() - LocationState.updatedAt) / 1000
        if (st == null || age > 30) {
            note(c, if (st == null) "Nothing yet. Switch on 👁 on the floating card and play for a moment." else "Last look was ${age}s ago (Auto-check is off or paused).")
        }
        if (st != null) {
            val fix = st.fix
            if (fix != null) {
                c.addView(Ui.text(this, "Tile ${fix.tileX}, ${fix.tileY}", 18f, Ui.GREEN, bold = true))
                nearest(fix)?.let { note(c, it, Ui.TAN, 13f) }
                note(c, "Match %.2f · camera %.0f° · %s".format(fix.score, fix.cameraAngle, st.message))
                c.addView(spaced(mapPicture(fix), 8))
                c.addView(spaced(Ui.button(this, "Open the world map here", false) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mejrs.github.io/osrs?m=-1&z=3&p=0&x=${fix.tileX}&y=${fix.tileY}")))
                    } catch (e: Exception) {
                    }
                }, 8))
            } else {
                c.addView(Ui.text(this, st.message, 16f, Ui.TAN, bold = true))
                st.best?.let { note(c, "Best guess %d, %d (match %.2f, only %.2f ahead of the next place)".format(it.tileX, it.tileY, it.score, st.margin)) }
                st.cameraAngle?.let { note(c, "Camera %.0f°".format(it)) }
            }
        }

        val s = card("Settings")
        switchRow(s, "Find me from the minimap", "Uses a little more battery while 👁 is on.", store.enabled) { store.enabled = it }
        switchRow(s, "Ask “Looks done?” when I reach a step's spot", null, store.arrivalCheck) { store.arrivalCheck = it }
        val cal = LocationState.calibration
        if (cal != null) {
            note(s, "Minimap found at %.0f, %.0f (radius %.0f px); compass at %.0f, %.0f.".format(cal.minimapX, cal.minimapY, cal.minimapRadius, cal.compassX, cal.compassY))
        }
        val zoom = if (store.zoom > 0f) store.zoom else LocationState.zoom
        note(s, if (store.zoom > 0f) "Minimap zoom learned: %.2f".format(zoom) else "Minimap zoom: still learning (it tries a few on its own).")
        if (store.zoom > 0f) {
            s.addView(spaced(Ui.button(this, "Re-learn the minimap zoom", false) {
                store.zoom = 0f
                rebuild()
            }, 6))
        }

        val h = card("Good to know")
        note(h, "• Works on the surface and in Prifddinas. Caves, dungeons, upstairs, instances and your house aren't on its map, so it says “not sure” there instead of guessing.")
        note(h, "• Open fields and the sea look the same everywhere, so it can take a few steps to be sure.")
        note(h, "• Keep other overlays (Game Booster, edge panels) off the minimap.")
        val credit = Ui.text(this, "World map by mejrs (layers_osrs), rendered from the game's map data.", 11f, Ui.MUTED)
        credit.setPadding(0, Ui.dp(this, 14), 0, 0)
        content.addView(credit)
    }

    private fun nearest(fix: Fix): String? {
        val places = try { TravelEngine.places(this) } catch (e: Exception) { return null }
        val p = places.filter { Packed.z(it.tile) == 0 }.minByOrNull { fix.distanceTo(Packed.x(it.tile), Packed.y(it.tile)) } ?: return null
        val d = fix.distanceTo(Packed.x(p.tile), Packed.y(p.tile))
        if (d > 120) return null
        return if (d <= 5) "At ${p.name}" else "$d tiles from ${p.name}"
    }

    /** 100×100 tiles of map around you, drawn 3× bigger, with a red dot where you are. */
    private fun mapPicture(fix: Fix): ImageView {
        val iv = ImageView(this)
        val region = WorldMaps.loaded?.firstOrNull { it.name == fix.region }
        val span = 100
        val scale = 3
        val bmp = Bitmap.createBitmap(span * scale, span * scale, Bitmap.Config.ARGB_8888)
        if (region != null) {
            val x0 = region.pxOf(fix.tileX) - span / 2
            val y0 = region.pyOf(fix.tileY) - span / 2
            val row = IntArray(span * scale)
            for (y in 0 until span * scale) {
                val my = y0 + y / scale
                for (x in 0 until span * scale) {
                    val mx = x0 + x / scale
                    row[x] = if (mx in 0 until region.width && my in 0 until region.height) region.rgb[my * region.width + mx] or 0xFF000000.toInt() else 0xFF000000.toInt()
                }
                bmp.setPixels(row, 0, span * scale, 0, y, span * scale, 1)
            }
            val c = span * scale / 2
            for (dy in -6..6) for (dx in -6..6) {
                val d2 = dx * dx + dy * dy
                if (d2 <= 36) bmp.setPixel(c + dx, c + dy, if (d2 >= 20) 0xFF000000.toInt() else 0xFFFF2B2B.toInt())
            }
        }
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return iv
    }
}
