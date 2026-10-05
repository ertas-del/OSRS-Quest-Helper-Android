package com.questoverlay.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reads screen pixels as 0xAARRGGBB; 0 outside what was captured. */
fun interface Pixels {
    fun at(x: Int, y: Int): Int
}

/**
 * A piece of the world map at 1 pixel per tile, drawn in minimap colours, north up.
 * Pixel (0, 0) is tile ([tileX0], [tileYTop]); x grows east, y grows south.
 */
class MapRegion(val name: String, val width: Int, val height: Int, val rgb: IntArray, val tileX0: Int, val tileYTop: Int) {
    /** The same map shrunk 4× (block averages), one array per colour channel. */
    val cw = width / 4
    val ch = height / 4
    val cr = FloatArray(cw * ch)
    val cg = FloatArray(cw * ch)
    val cb = FloatArray(cw * ch)

    init {
        for (y in 0 until ch) for (x in 0 until cw) {
            var r = 0f; var g = 0f; var b = 0f
            for (dy in 0 until 4) for (dx in 0 until 4) {
                val p = rgb[(y * 4 + dy) * width + x * 4 + dx]
                r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
            }
            val i = y * cw + x
            cr[i] = r / 16f; cg[i] = g / 16f; cb[i] = b / 16f
        }
    }

    fun tileX(px: Float): Int = (tileX0 + px).roundToInt()
    fun tileY(py: Float): Int = (tileYTop - py).roundToInt()
    fun pxOf(tileX: Int): Int = tileX - tileX0
    fun pyOf(tileY: Int): Int = tileYTop - tileY
    fun contains(tileX: Int, tileY: Int): Boolean = pxOf(tileX) in 0 until width && pyOf(tileY) in 0 until height
}

/** Where the minimap and compass are on screen, in screen pixels. */
data class Calibration(val minimapX: Float, val minimapY: Float, val minimapRadius: Float, val compassX: Float, val compassY: Float, val unit: Float) {
    /** Minimap pixels per game tile at the default minimap zoom, scaled for this screen. */
    fun pixelsPerTile(zoom: Float): Float = 4f / zoom * unit / 1080f
}

/** The north-up minimap at 1 px per tile: colours (r, g, b) and which pixels to trust. */
class Template(val n: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray, val ok: BooleanArray) {
    val validCount: Int get() = ok.count { it }
}

data class Candidate(val score: Float, val region: MapRegion, val tileX: Int, val tileY: Int)

object Locator {

    // ------------------------------------------------------------------ calibration

    /**
     * Finds the minimap's dark border near where it sits on the S26/S22+ (0.197 screen-heights in
     * from the right, 0.181 down), and puts the compass at its usual offset from it.
     */
    fun calibrate(px: Pixels, w: Int, h: Int): Calibration {
        val u = minOf(w, h).toFloat()
        val cx0 = w - 0.197f * u
        val cy0 = 0.181f * u
        val step = maxOf(1, (3 * u / 1080f).roundToInt())
        val reach = (24 * u / 1080f).roundToInt()
        val angles = 96
        val cosA = FloatArray(angles) { cos(2 * PI * it / angles).toFloat() }
        val sinA = FloatArray(angles) { sin(2 * PI * it / angles).toFloat() }
        var best = -1f
        var bx = cx0; var by = cy0; var br = 0.153f * u
        var dy = -reach
        while (dy <= reach) {
            var dx = -reach
            while (dx <= reach) {
                var r = 0.145f * u
                while (r < 0.165f * u) {
                    var dark = 0; var seen = 0
                    for (k in 0 until angles) {
                        val x = (cx0 + dx + r * cosA[k]).toInt()
                        val y = (cy0 + dy + r * sinA[k]).toInt()
                        if (x < 0 || y < 0 || x >= w || y >= h) continue
                        seen++
                        if (luma(px.at(x, y)) < 60f) dark++
                    }
                    val s = if (seen > 0) dark.toFloat() / seen else 0f
                    if (s > best) { best = s; bx = cx0 + dx; by = cy0 + dy; br = r }
                    r += 0.004f * u
                }
                dx += step
            }
            dy += step
        }
        return Calibration(bx, by, br, bx - 0.152f * u, by - 0.119f * u, u)
    }

    // ------------------------------------------------------------------ compass

    /**
     * Camera direction: degrees clockwise from screen-up that north points to. The three small
     * red ticks on the compass rim (E, S, W, 90° apart) give the exact angle; the red/blue needle
     * says which way round is north. Null when the compass can't be read.
     */
    fun compassAngle(px: Pixels, cx: Float, cy: Float, unit: Float): Float? {
        val r = (0.035f * unit).roundToInt()
        var rx = 0.0; var ry = 0.0; var rn = 0
        var bx = 0.0; var by = 0.0; var bn = 0
        var s4 = 0.0; var c4 = 0.0; var tn = 0
        val x0 = cx.toInt(); val y0 = cy.toInt()
        for (yy in -r until r) for (xx in -r until r) {
            val p = px.at(x0 + xx, y0 + yy)
            val R = (p shr 16) and 255; val G = (p shr 8) and 255; val B = p and 255
            val d = sqrt((xx * xx + yy * yy).toDouble())
            val red = R > 140 && G < 100 && B < 100 && R - G > 70
            val blue = B > 120 && R < 90 && G < 90
            if (d <= 0.45 * r) {
                if (red) { rx += xx; ry += yy; rn++ }
                if (blue) { bx += xx; by += yy; bn++ }
            } else if (d > 0.5 * r && d < 0.92 * r && red) {
                val phi = atan2(xx.toDouble(), -yy.toDouble())
                s4 += sin(4 * phi); c4 += cos(4 * phi); tn++
            }
        }
        if (rn < 5 || bn < 5) return null
        val coarse = atan2(rx / rn - bx / bn, -(ry / rn - by / bn))
        val angle = if (tn < 3) coarse else {
            val a4 = atan2(s4, c4) / 4
            val k = ((coarse - a4) / (PI / 2)).roundToInt()
            a4 + k * PI / 2
        }
        return ((Math.toDegrees(angle) % 360 + 360) % 360).toFloat()
    }

    // ------------------------------------------------------------------ template

    private fun luma(p: Int): Float = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)

    /**
     * Which minimap pixels show the ground: inside the circle, and not under a map icon (round
     * discs with a dark rim) or a dot (players white, NPCs yellow, items red).
     */
    fun groundMask(crop: IntArray, size: Int, rad: Int): BooleanArray {
        val dark = BooleanArray(size * size) { luma(crop[it]) < 45f }
        val bad = BooleanArray(size * size)
        val samples = 24
        for (r in intArrayOf(10, 12, 14)) {
            val ox = IntArray(samples) { (r * cos(2 * PI * it / samples)).roundToInt() }
            val oy = IntArray(samples) { (r * sin(2 * PI * it / samples)).roundToInt() }
            val need = (0.6 * samples).toInt() + 1
            for (y in r until size - r) for (x in r until size - r) {
                var c = 0
                for (k in 0 until samples) if (dark[(y + oy[k]) * size + x + ox[k]]) c++
                if (c >= need) disc(bad, size, x, y, r + 4)
            }
        }
        // Small bright dots, grown by 2 pixels.
        val dot = BooleanArray(size * size)
        for (i in 0 until size * size) {
            val p = crop[i]
            val R = (p shr 16) and 255; val G = (p shr 8) and 255; val B = p and 255
            if (luma(p) > 235f) { dot[i] = true; continue }
            val v = maxOf(R, G, B); val mn = minOf(R, G, B)
            if (v <= 200) continue
            val s = (v - mn) * 255 / v
            if (s <= 150) continue
            val hue = hueHalfDegrees(R, G, B, v, mn)
            if (hue < 12 || hue > 165 || (hue in 21..34)) dot[i] = true
        }
        for (y in 0 until size) for (x in 0 until size) if (dot[y * size + x]) {
            for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) bad[yy * size + xx] = true
            }
        }
        val ok = BooleanArray(size * size)
        val lim = (rad - 6) * (rad - 6)
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - rad; val dy = y - rad
            ok[y * size + x] = dx * dx + dy * dy <= lim && !bad[y * size + x]
        }
        return ok
    }

    /** OpenCV-style hue, 0..179. */
    private fun hueHalfDegrees(r: Int, g: Int, b: Int, v: Int, mn: Int): Int {
        val d = (v - mn).toFloat()
        if (d == 0f) return 0
        var h = when (v) {
            r -> 60f * (g - b) / d
            g -> 120f + 60f * (b - r) / d
            else -> 240f + 60f * (r - g) / d
        }
        if (h < 0) h += 360f
        return (h / 2f).toInt()
    }

    private fun disc(a: BooleanArray, size: Int, cx: Int, cy: Int, r: Int) {
        val r2 = r * r
        for (dy in -r..r) {
            val y = cy + dy
            if (y < 0 || y >= size) continue
            for (dx in -r..r) {
                val x = cx + dx
                if (x < 0 || x >= size || dx * dx + dy * dy > r2) continue
                a[y * size + x] = true
            }
        }
    }

    /**
     * Turns the minimap north-up at 1 pixel per tile: each template pixel averages a 4×4 grid of
     * samples across the [ppt]×[ppt] screen pixels it covers, rotated by the camera [angle].
     */
    fun template(px: Pixels, cal: Calibration, angle: Float, ppt: Float): Template {
        val rad = (cal.minimapRadius * 0.92f).toInt()
        val size = 2 * rad
        val x0 = cal.minimapX.toInt() - rad
        val y0 = cal.minimapY.toInt() - rad
        val crop = IntArray(size * size) { px.at(x0 + it % size, y0 + it / size) }
        val ground = groundMask(crop, size, rad)
        val n = (size / ppt).toInt()
        val r = FloatArray(n * n); val g = FloatArray(n * n); val b = FloatArray(n * n)
        val ok = BooleanArray(n * n)
        val th = Math.toRadians(angle.toDouble())
        val c = cos(th).toFloat(); val s = sin(th).toFloat()
        val k = 4
        val offs = FloatArray(k) { (it + 0.5f) / k - 0.5f }
        for (v in 0 until n) for (u in 0 until n) {
            var ar = 0f; var ag = 0f; var ab = 0f; var good = 0
            for (oy in offs) for (ox in offs) {
                val tx = (u + 0.5f + ox - n / 2f) * ppt
                val ty = (v + 0.5f + oy - n / 2f) * ppt
                val sx = (c * tx - s * ty + rad).toInt()
                val sy = (s * tx + c * ty + rad).toInt()
                if (sx < 0 || sy < 0 || sx >= size || sy >= size) continue
                val i = sy * size + sx
                if (!ground[i]) continue
                val p = crop[i]
                ar += (p shr 16) and 255; ag += (p shr 8) and 255; ab += p and 255
                good++
            }
            if (good >= k * k * 3 / 4) {
                val i = v * n + u
                r[i] = ar / good; g[i] = ag / good; b[i] = ab / good; ok[i] = true
            }
        }
        return Template(n, r, g, b, ok)
    }

    // ------------------------------------------------------------------ matching

    /** Zero-mean template values and their positions in a map row layout of width [stride]. */
    private class Prepared(val off: IntArray, val dx: IntArray, val dy: IntArray, val tr: FloatArray, val tg: FloatArray, val tb: FloatArray, val tNorm: Double, val size: Int)

    private fun prepare(r: FloatArray, g: FloatArray, b: FloatArray, ok: BooleanArray, n: Int, stride: Int): Prepared? {
        val idx = (0 until n * n).filter { ok[it] }
        if (idx.size < 20) return null
        val mr = idx.sumOf { r[it].toDouble() } / idx.size
        val mg = idx.sumOf { g[it].toDouble() } / idx.size
        val mb = idx.sumOf { b[it].toDouble() } / idx.size
        val tr = FloatArray(idx.size) { (r[idx[it]] - mr).toFloat() }
        val tg = FloatArray(idx.size) { (g[idx[it]] - mg).toFloat() }
        val tb = FloatArray(idx.size) { (b[idx[it]] - mb).toFloat() }
        var norm = 0.0
        for (i in idx.indices) norm += tr[i] * tr[i] + tg[i] * tg[i] + tb[i] * tb[i]
        if (norm <= 0.0) return null
        return Prepared(
            IntArray(idx.size) { (idx[it] / n) * stride + idx[it] % n },
            IntArray(idx.size) { idx[it] % n },
            IntArray(idx.size) { idx[it] / n },
            tr, tg, tb, norm, n
        )
    }

    /** Masked normalised correlation (like OpenCV's TM_CCOEFF_NORMED with a mask) at one spot of the small map. */
    private fun nccCoarse(m: MapRegion, t: Prepared, base: Int): Float {
        var si = 0.0; var si2 = 0.0; var sti = 0.0
        val cnt = t.off.size
        var sr = 0.0; var sg = 0.0; var sb = 0.0
        for (k in 0 until cnt) {
            val i = base + t.off[k]
            val r = m.cr[i]; val g = m.cg[i]; val b = m.cb[i]
            sr += r; sg += g; sb += b
            si2 += r * r + g * g + b * b
            sti += t.tr[k] * r + t.tg[k] * g + t.tb[k] * b
        }
        si = (sr * sr + sg * sg + sb * sb) / cnt
        val iNorm = si2 - si
        if (iNorm <= 1e-6) return -1f
        return (sti / sqrt(t.tNorm * iNorm)).toFloat()
    }

    private fun nccFull(m: MapRegion, t: Prepared, x0: Int, y0: Int): Float {
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var si2 = 0.0; var sti = 0.0
        val cnt = t.off.size
        val base = y0 * m.width + x0
        for (k in 0 until cnt) {
            val p = m.rgb[base + t.off[k]]
            val r = ((p shr 16) and 255).toFloat(); val g = ((p shr 8) and 255).toFloat(); val b = (p and 255).toFloat()
            sr += r; sg += g; sb += b
            si2 += (r * r + g * g + b * b).toDouble()
            sti += (t.tr[k] * r + t.tg[k] * g + t.tb[k] * b).toDouble()
        }
        val iNorm = si2 - (sr * sr + sg * sg + sb * sb) / cnt
        if (iNorm <= 1e-6) return -1f
        return (sti / sqrt(t.tNorm * iNorm)).toFloat()
    }

    /** The template shrunk 4× (block averages; a block counts only if all 16 pixels are trusted). */
    private fun coarse(t: Template): Template {
        val n = t.n / 4
        val r = FloatArray(n * n); val g = FloatArray(n * n); val b = FloatArray(n * n)
        val ok = BooleanArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var all = true; var ar = 0f; var ag = 0f; var ab = 0f
            for (dy in 0 until 4) for (dx in 0 until 4) {
                val i = (y * 4 + dy) * t.n + x * 4 + dx
                if (!t.ok[i]) { all = false; break }
                ar += t.r[i]; ag += t.g[i]; ab += t.b[i]
            }
            if (all) {
                val i = y * n + x
                r[i] = ar / 16; g[i] = ag / 16; b[i] = ab / 16; ok[i] = true
            }
        }
        return Template(n, r, g, b, ok)
    }

    /**
     * Searches [regions] for the template. With [near] (a recent fix) it only looks within
     * [radius] tiles of it; otherwise everywhere. Coarse pass on the 4× smaller map, then full
     * resolution around the best spots. Returns the best few distinct places, best first.
     */
    fun search(regions: List<MapRegion>, t: Template, near: Candidate? = null, radius: Int = 40): List<Candidate> {
        val tc = coarse(t)
        val coarseHits = ArrayList<Triple<Float, MapRegion, Int>>() // score, region, coarse index
        for (m in regions) {
            if (near != null && near.region !== m) continue
            val p = prepare(tc.r, tc.g, tc.b, tc.ok, tc.n, m.cw) ?: return emptyList()
            var xs = 0; var xe = m.cw - tc.n; var ys = 0; var ye = m.ch - tc.n
            if (near != null) {
                val cx = (m.pxOf(near.tileX) - t.n / 2) / 4
                val cy = (m.pyOf(near.tileY) - t.n / 2) / 4
                val rr = radius / 4 + 1
                xs = maxOf(0, cx - rr); xe = minOf(xe, cx + rr); ys = maxOf(0, cy - rr); ye = minOf(ye, cy + rr)
            }
            if (xe < xs || ye < ys) continue
            val w = xe - xs + 1
            val scores = FloatArray(w * (ye - ys + 1))
            for (y in ys..ye) for (x in xs..xe) {
                // Skip unmapped (black) areas quickly.
                val centre = (y + tc.n / 2) * m.cw + x + tc.n / 2
                scores[(y - ys) * w + (x - xs)] = if (m.cr[centre] + m.cg[centre] + m.cb[centre] < 3f) -1f
                    else nccCoarse(m, p, y * m.cw + x)
            }
            // The 12 best peaks, at least 3 coarse pixels apart.
            repeat(12) {
                var bi = -1; var bs = -2f
                for (i in scores.indices) if (scores[i] > bs) { bs = scores[i]; bi = i }
                if (bi < 0 || bs <= -1f) return@repeat
                val px = bi % w; val py = bi / w
                coarseHits.add(Triple(bs, m, (py + ys) * m.cw + (px + xs)))
                for (yy in maxOf(0, py - 3)..minOf(scores.size / w - 1, py + 3))
                    for (xx in maxOf(0, px - 3)..minOf(w - 1, px + 3)) scores[yy * w + xx] = -2f
            }
        }
        coarseHits.sortByDescending { it.first }
        val fine = ArrayList<Candidate>()
        val prepared = HashMap<MapRegion, Prepared?>()
        for ((_, m, ci) in coarseHits.take(12)) {
            val p = prepared.getOrPut(m) { prepare(t.r, t.g, t.b, t.ok, t.n, m.width) } ?: continue
            val lx = (ci % m.cw) * 4; val ly = (ci / m.cw) * 4
            var bs = -2f; var bx = 0; var by = 0
            for (y in maxOf(0, ly - 8)..minOf(m.height - t.n, ly + 8)) for (x in maxOf(0, lx - 8)..minOf(m.width - t.n, lx + 8)) {
                val s = nccFull(m, p, x, y)
                if (s > bs) { bs = s; bx = x; by = y }
            }
            if (bs > -2f) fine.add(Candidate(bs, m, m.tileX(bx + t.n / 2f), m.tileY(by + t.n / 2f)))
        }
        fine.sortByDescending { it.score }
        val out = ArrayList<Candidate>()
        for (f in fine) if (out.none { it.region === f.region && abs(it.tileX - f.tileX) <= 15 && abs(it.tileY - f.tileY) <= 15 }) out.add(f)
        return out.take(3)
    }
}
