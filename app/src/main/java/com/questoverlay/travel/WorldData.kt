package com.questoverlay.travel

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Walkability of every game tile, from the Shortest Path plugin's collision map.
 *
 * Each zip entry "rx_ry" is one 64x64 region stored as a java.util.BitSet byte array:
 * for every plane, tile and flag: bit = ((z * 64 + localY) * 64 + localX) * 2 + flag,
 * where flag 0 = "can move north from here" and flag 1 = "can move east from here".
 */
class CollisionMap private constructor(
    private val regions: HashMap<Int, ByteArray>
) {
    fun can(x: Int, y: Int, z: Int, flag: Int): Boolean {
        if (x < 0 || y < 0 || z < 0) return false
        val data = regions[regionKey(x shr 6, y shr 6)] ?: return false
        val bit = ((z * 64 + (y and 63)) * 64 + (x and 63)) * 2 + flag
        val byteIndex = bit ushr 3
        if (byteIndex >= data.size) return false
        return (data[byteIndex].toInt() ushr (bit and 7)) and 1 == 1
    }

    /** False for instances and quest-only areas the map doesn't cover. */
    fun hasRegion(x: Int, y: Int): Boolean = regions.containsKey(regionKey(x shr 6, y shr 6))

    fun n(x: Int, y: Int, z: Int) = can(x, y, z, 0)
    fun s(x: Int, y: Int, z: Int) = can(x, y - 1, z, 0)
    fun e(x: Int, y: Int, z: Int) = can(x, y, z, 1)
    fun w(x: Int, y: Int, z: Int) = can(x - 1, y, z, 1)

    fun ne(x: Int, y: Int, z: Int) = n(x, y, z) && e(x, y + 1, z) && e(x, y, z) && n(x + 1, y, z)
    fun nw(x: Int, y: Int, z: Int) = n(x, y, z) && w(x, y + 1, z) && w(x, y, z) && n(x - 1, y, z)
    fun se(x: Int, y: Int, z: Int) = s(x, y, z) && e(x, y - 1, z) && e(x, y, z) && s(x + 1, y, z)
    fun sw(x: Int, y: Int, z: Int) = s(x, y, z) && w(x, y - 1, z) && w(x, y, z) && s(x - 1, y, z)

    /** A tile you can't step off in any direction (often an NPC/object spot or a transport landing). */
    fun isBlocked(x: Int, y: Int, z: Int) = !n(x, y, z) && !s(x, y, z) && !e(x, y, z) && !w(x, y, z)

    companion object {
        private fun regionKey(rx: Int, ry: Int) = (rx shl 16) or ry

        fun load(input: InputStream): CollisionMap {
            val regions = HashMap<Int, ByteArray>(4096)
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val parts = entry.name.split('_')
                    if (parts.size >= 2) {
                        val rx = parts[0].toIntOrNull()
                        val ry = parts[1].toIntOrNull()
                        if (rx != null && ry != null) regions[regionKey(rx, ry)] = zip.readBytes()
                    }
                }
            }
            return CollisionMap(regions)
        }
    }
}

/** One way of getting from A to B that isn't plain walking. */
class Link(
    val type: String,
    /** Packed origin tile, or -1 when it can be used from anywhere (spells, jewellery). */
    val origin: Int,
    val destination: Int,
    val ticks: Int,
    val skills: List<Pair<String, Int>>,
    val quests: List<String>,
    val items: String,
    val name: String,
    val spellbook: Int,
    val needsUnlock: Boolean
) {
    val fromAnywhere: Boolean get() = origin < 0
}

class TransportTable(val links: List<Link>) {
    /** Links leaving each packed tile. */
    val byOrigin: HashMap<Int, IntArray>
    /** Indexes of links usable from anywhere. */
    val anywhere: IntArray

    init {
        val map = HashMap<Int, MutableList<Int>>()
        val any = ArrayList<Int>()
        for ((i, l) in links.withIndex()) {
            if (l.fromAnywhere) any.add(i) else map.getOrPut(l.origin) { ArrayList(2) }.add(i)
        }
        byOrigin = HashMap(map.size * 2)
        for ((k, v) in map) byOrigin[k] = v.toIntArray()
        anywhere = any.toIntArray()
    }

    companion object {
        fun load(input: InputStream): TransportTable {
            val links = ArrayList<Link>(15000)
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val c = line.split('\t')
                    if (c.size < 10) continue
                    val origin = if (c[1] == "*") -1 else parseTile(c[1]) ?: continue
                    val dest = parseTile(c[2]) ?: continue
                    val skills = c[4].split(';').mapNotNull {
                        val p = it.split(':')
                        if (p.size == 2) p[1].toIntOrNull()?.let { lvl -> p[0] to lvl } else null
                    }
                    val quests = c[5].split(';').filter { it.isNotBlank() }
                    links.add(
                        Link(
                            type = c[0],
                            origin = origin,
                            destination = dest,
                            ticks = (c[3].toIntOrNull() ?: 1).coerceIn(1, 200),
                            skills = skills,
                            quests = quests,
                            items = c[6],
                            name = c[7],
                            spellbook = c[9].toIntOrNull() ?: -1,
                            needsUnlock = c.getOrNull(10) == "1"
                        )
                    )
                }
            }
            return TransportTable(links)
        }

        private fun parseTile(s: String): Int? {
            val p = s.split(',')
            if (p.size != 3) return null
            val x = p[0].toIntOrNull() ?: return null
            val y = p[1].toIntOrNull() ?: return null
            val z = p[2].toIntOrNull() ?: return null
            return Packed.pack(x, y, z)
        }
    }
}

/** Tiles packed into one Int: 14 bits x, 14 bits y, 2 bits plane. */
object Packed {
    fun pack(x: Int, y: Int, z: Int): Int = (x and 0x3FFF) or ((y and 0x3FFF) shl 14) or ((z and 3) shl 28)
    fun x(p: Int) = p and 0x3FFF
    fun y(p: Int) = (p ushr 14) and 0x3FFF
    fun z(p: Int) = (p ushr 28) and 3
}
