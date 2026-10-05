package com.questoverlay.location

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Where the player is: a tile on the surface (plane 0), how sure, and the camera direction. */
data class Fix(
    val tileX: Int,
    val tileY: Int,
    val region: String,
    val score: Float,
    /** Camera direction from the compass: degrees clockwise from screen-up that north points to. */
    val cameraAngle: Float,
    val at: Long
) {
    fun distanceTo(x: Int, y: Int): Int = maxOf(abs(x - tileX), abs(y - tileY))

    /** Compass bearing from here to (x, y): degrees clockwise from north. */
    fun bearingTo(x: Int, y: Int): Float {
        val deg = Math.toDegrees(atan2((x - tileX).toDouble(), (y - tileY).toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    /** The same bearing as it looks on screen, given the camera (what an arrow on the card should show). */
    fun screenAngleTo(x: Int, y: Int): Float = (bearingTo(x, y) + cameraAngle + 360f) % 360f

    fun straightLineTiles(x: Int, y: Int): Int = hypot((x - tileX).toDouble(), (y - tileY).toDouble()).toInt()
}

/** What the locator made of one look at the screen. */
data class LocatorStatus(val fix: Fix?, val message: String, val cameraAngle: Float?, val best: Candidate? = null, val margin: Float = 0f)

/**
 * Finds the player on the world map from the minimap, look after look.
 *  - Near a recent fix it only searches ±40 tiles (fast), falling back to the whole map when lost.
 *  - A place found by a whole-map search must be found again on the next look (within 5 tiles)
 *    before it counts, and must clearly beat the second-best place.
 *  - If the minimap is zoomed differently from the default, it tries a couple of other zooms and
 *    remembers the one that works ([zoom], saved by the caller via [onZoomLearned]).
 */
class LocatorEngine(
    private val regions: List<MapRegion>,
    var zoom: Float = 0.95f,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** True when [zoom] was learned before (saved), so it isn't second-guessed. */
    zoomKnown: Boolean = false,
    private val onZoomLearned: (Float) -> Unit = {}
) {
    var calibration: Calibration? = null
        private set
    private var calibratedFor = 0L
    var lastFix: Fix? = null
        private set
    private var pending: Candidate? = null
    private var localMisses = 0
    private var globalMisses = 0
    private var zoomConfirmed = zoomKnown

    fun reset() {
        lastFix = null
        pending = null
        localMisses = 0
        globalMisses = 0
    }

    /** Forget the minimap position (e.g. the player moved it, or rotated the phone). */
    fun recalibrate() {
        calibration = null
    }

    fun onFrame(px: Pixels, w: Int, h: Int): LocatorStatus {
        val sizeKey = w.toLong() shl 32 or h.toLong()
        val cal = calibration?.takeIf { calibratedFor == sizeKey } ?: Locator.calibrate(px, w, h).also {
            calibration = it
            calibratedFor = sizeKey
        }
        val angle = Locator.compassAngle(px, cal.compassX, cal.compassY, cal.unit)
            ?: return LocatorStatus(lastFix?.takeIf { fresh(it) }, "Can't see the compass", null)
        val t = Locator.template(px, cal, angle, cal.pixelsPerTile(zoom))
        if (t.validCount < t.n * t.n / 5) {
            return LocatorStatus(lastFix?.takeIf { fresh(it) }, "The minimap is mostly hidden or dark (an instance, a cave, or something over it)", angle)
        }
        val now = clock()

        // 1. Tracking: look near the last fix.
        val last = lastFix
        if (last != null && now - last.at < TRACK_MS) {
            val region = regions.firstOrNull { it.name == last.region }
            if (region != null) {
                val near = Locator.search(listOf(region), t, Candidate(last.score, region, last.tileX, last.tileY), 40).firstOrNull()
                if (near != null && near.score >= LOCAL_MIN) {
                    localMisses = 0
                    return accept(near, angle, now, "Tracking")
                }
            }
            localMisses++
            if (localMisses < 2) return LocatorStatus(last, "Lost you for a moment", angle)
        }

        // 2. Whole map.
        val found = Locator.search(regions, t)
        val best = found.firstOrNull() ?: return miss(angle, "No match on the map", null, 0f)
        val margin = best.score - (found.getOrNull(1)?.score ?: 0f)
        if (best.score < GLOBAL_MIN || margin < MARGIN) {
            pending = null
            return miss(angle, if (best.score < GLOBAL_MIN) "Not sure where this is" else "Several places look alike", best, margin)
        }
        val p = pending
        pending = best
        if (p == null || p.region !== best.region || abs(p.tileX - best.tileX) > 5 || abs(p.tileY - best.tileY) > 5) {
            return LocatorStatus(lastFix?.takeIf { fresh(it) }, "Checking…", angle, best, margin)
        }
        globalMisses = 0
        localMisses = 0
        if (!zoomConfirmed) {
            zoomConfirmed = true
            onZoomLearned(zoom)
        }
        return accept(best, angle, now, "Found you", margin)
    }

    private fun accept(c: Candidate, angle: Float, now: Long, msg: String, margin: Float = 0f): LocatorStatus {
        val fix = Fix(c.tileX, c.tileY, c.region.name, c.score, angle, now)
        lastFix = fix
        return LocatorStatus(fix, msg, angle, c, margin)
    }

    private fun miss(angle: Float, msg: String, best: Candidate?, margin: Float): LocatorStatus {
        globalMisses++
        // Still nothing after a few looks and the zoom was never confirmed: try another zoom.
        if (!zoomConfirmed && globalMisses % 3 == 0) {
            val i = ZOOMS.indexOf(zoom)
            zoom = ZOOMS[(i + 1).mod(ZOOMS.size)]
        }
        return LocatorStatus(lastFix?.takeIf { fresh(it) }, msg, angle, best, margin)
    }

    private fun fresh(f: Fix) = clock() - f.at < TRACK_MS

    companion object {
        const val TRACK_MS = 20_000L
        const val LOCAL_MIN = 0.33f
        const val GLOBAL_MIN = 0.30f
        const val MARGIN = 0.08f
        val ZOOMS = listOf(0.95f, 0.85f, 1.05f, 0.75f, 1.2f)
    }
}
