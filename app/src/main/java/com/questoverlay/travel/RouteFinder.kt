package com.questoverlay.travel

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/** One instruction of a route: either a walk or a single transport. */
data class Leg(
    val kind: Kind,
    val title: String,
    val detail: String,
    val from: Int,
    val to: Int,
    val tiles: Int
) {
    enum class Kind { WALK, TELEPORT, TRANSPORT }
}

data class Route(val legs: List<Leg>, val cost: Int, val expanded: Int) {
    /** The first thing to do, short enough for the overlay card. */
    val headline: String
        get() = legs.firstOrNull { it.kind != Leg.Kind.WALK }?.title ?: legs.firstOrNull()?.title ?: ""
}

/**
 * Dijkstra over every game tile plus transport links, in the spirit of the Shortest Path
 * plugin. Costs are roughly "ticks": 1 per tile walked, a link's own duration plus a small
 * penalty for things that cost runes, money or a cooldown.
 */
class RouteFinder(private val map: CollisionMap, private val table: TransportTable) {

    private class Region {
        val cost = IntArray(64 * 64).also { it.fill(Int.MAX_VALUE) }
        val parent = IntArray(64 * 64)
        val via = IntArray(64 * 64) // link index, or -1 walked, -2 start
    }

    /** Growable int list used as a priority bucket. */
    private class IntBag {
        var data = IntArray(64)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun pop(): Int = data[--size]
    }

    private val regions = HashMap<Int, Region>()

    /** True when the last [find] gave up because the search got too big, rather than finding no way. */
    var lastHitLimit = false
        private set

    private fun regionKey(p: Int): Int = (Packed.z(p) shl 28) or ((Packed.y(p) shr 6) shl 14) or (Packed.x(p) shr 6)
    private fun local(p: Int): Int = ((Packed.y(p) and 63) shl 6) or (Packed.x(p) and 63)
    private fun region(p: Int): Region = regions.getOrPut(regionKey(p)) { Region() }
    private fun costOf(p: Int): Int = regions[regionKey(p)]?.cost?.get(local(p)) ?: Int.MAX_VALUE

    fun find(start: Int, target: Int, profile: TravelProfile, maxExpanded: Int = 1_500_000): Route? {
        // Instances and quest-only areas aren't on the walking map: nothing to plan.
        lastHitLimit = false
        if (!map.hasRegion(Packed.x(target), Packed.y(target))) return null
        try {
            return search(start, target, profile, maxExpanded)
        } finally {
            regions.clear() // a long search can touch tens of MB of bookkeeping; free it
        }
    }

    private fun search(start: Int, target: Int, profile: TravelProfile, maxExpanded: Int): Route? {
        regions.clear()
        val buckets = Array(BUCKETS) { IntBag() }
        var queued = 0
        var current = 0
        var expanded = 0

        val avoidWild = profile.avoidWilderness && !Wilderness.contains(start) && !Wilderness.contains(target)
        val allowed = BooleanArray(table.links.size) { profile.allows(table.links[it]) }

        fun relax(node: Int, newCost: Int, from: Int, via: Int) {
            val r = region(node)
            val l = local(node)
            if (newCost < r.cost[l]) {
                r.cost[l] = newCost
                r.parent[l] = from
                r.via[l] = via
                buckets[newCost % BUCKETS].add(node)
                queued++
            }
        }

        relax(start, 0, start, -2)

        // Teleports you can cast or rub from wherever you are.
        for (i in table.anywhere) {
            if (!allowed[i]) continue
            val link = table.links[i]
            if (avoidWild && Wilderness.contains(link.destination)) continue
            relax(link.destination, link.ticks + penalty(link), start, i)
        }

        val tx = Packed.x(target)
        val ty = Packed.y(target)
        val tz = Packed.z(target)
        var found = -1

        while (queued > 0) {
            var bag = buckets[current % BUCKETS]
            var guard = 0
            while (bag.size == 0) {
                current++
                bag = buckets[current % BUCKETS]
                if (++guard > BUCKETS) return null
            }
            val node = bag.pop()
            queued--
            val nodeCost = costOf(node)
            if (nodeCost != current) continue // stale entry

            val x = Packed.x(node)
            val y = Packed.y(node)
            val z = Packed.z(node)
            if (z == tz && abs(x - tx) <= 1 && abs(y - ty) <= 1) {
                found = node
                break
            }
            if (++expanded > maxExpanded) {
                lastHitLimit = true
                return null
            }

            // Links leaving this tile.
            table.byOrigin[node]?.let { idx ->
                for (i in idx) {
                    if (!allowed[i]) continue
                    val link = table.links[i]
                    if (avoidWild && Wilderness.contains(link.destination)) continue
                    relax(link.destination, nodeCost + link.ticks + penalty(link), node, i)
                }
            }

            // Walking to the eight neighbours, with the plugin's rules for blocked tiles.
            val blocked = map.isBlocked(x, y, z)
            for (d in 0 until 8) {
                val ok = if (blocked) {
                    val dx = DX[d]
                    val dy = DY[d]
                    !map.isBlocked(x + dx, y + dy, z) &&
                        (dx == 0 || !map.isBlocked(x + dx, y, z)) &&
                        (dy == 0 || !map.isBlocked(x, y + dy, z))
                } else when (d) {
                    0 -> map.w(x, y, z)
                    1 -> map.e(x, y, z)
                    2 -> map.s(x, y, z)
                    3 -> map.n(x, y, z)
                    4 -> map.sw(x, y, z)
                    5 -> map.se(x, y, z)
                    6 -> map.nw(x, y, z)
                    else -> map.ne(x, y, z)
                }
                if (!ok) continue
                val nx = x + DX[d]
                val ny = y + DY[d]
                if (nx < 0 || ny < 0) continue
                val next = Packed.pack(nx, ny, z)
                if (avoidWild && Wilderness.contains(next)) continue
                relax(next, nodeCost + 1, node, -1)
            }
        }
        if (found < 0) return null
        return Route(buildLegs(start, found), costOf(found), expanded)
    }

    private fun penalty(link: Link): Int = if (link.poh.isNotEmpty()) pohPenalty(link) else when (link.type) {
        "SPELL" -> 8                   // runes
        "TELEPORT_ITEM" -> 8           // charges / tablets
        "HOME_TELEPORT" -> 25          // long cast and a 30-minute cooldown
        "MINIGAME" -> 30               // 20-minute cooldown
        "CHARTER_SHIP" -> 12           // coins
        "SHIP", "BOAT", "CANOE" -> 6
        "FAIRY_RING", "SPIRIT_TREE", "GNOME_GLIDER", "QUETZAL", "MAGIC_MUSHTREE" -> 3
        "HOT_AIR_BALLOON" -> 8         // logs
        "OBELISK" -> 15                // random destination, Wilderness
        "AGILITY_SHORTCUT" -> if (link.items.isNotBlank()) 20 else 0 // grapples and the like
        else -> 0
    }

    /** Getting into the house costs a teleport; everything inside it is quick and free. */
    private fun pohPenalty(link: Link): Int = when {
        link.poh == "arrive" || link.poh.startsWith("outside:") -> if (link.type == "SPELL") 8 else 6
        link.poh.startsWith("home:") -> 2
        else -> 1
    }

    // ------------------------------------------------------------------ turning a path into legs

    private fun buildLegs(start: Int, end: Int): List<Leg> {
        // Walk parents back to the start.
        val nodes = ArrayList<Int>()
        val vias = ArrayList<Int>()
        var p = end
        var guard = 0
        while (true) {
            val r = regions[regionKey(p)] ?: break
            val l = local(p)
            nodes.add(p)
            vias.add(r.via[l])
            if (r.via[l] == -2 || ++guard > 200_000) break
            p = r.parent[l]
        }
        nodes.reverse()
        vias.reverse()

        val legs = ArrayList<Leg>()
        var walkFrom = nodes[0]
        var walkTiles = 0
        var planeChange = 0 // kept for walkLeg's signature; level changes now get their own legs

        fun flushWalk(until: Int, toward: Link?) {
            if (walkTiles > 0 || planeChange != 0) {
                legs.add(walkLeg(walkFrom, until, walkTiles, planeChange, toward))
            }
            walkTiles = 0
            planeChange = 0
        }

        for (i in 1 until nodes.size) {
            val via = vias[i]
            val prev = nodes[i - 1]
            val node = nodes[i]
            if (via == -1) {
                walkTiles++
                continue
            }
            if (via < 0) continue
            val link = table.links[via]
            if (link.type == "TRANSPORT" || link.type == "AGILITY_SHORTCUT" && link.skills.isEmpty()) {
                if (changesLevel(prev, node)) {
                    // Stairs, ladders, cave entrances: worth their own line.
                    flushWalk(prev, null)
                    legs.add(Leg(Leg.Kind.WALK, prettyAction(link.name, prev, node), "", prev, node, 0))
                    walkFrom = node
                } else {
                    // Doors and gates are just part of walking.
                    walkTiles += max(1, chebyshev(prev, node).coerceAtMost(3))
                }
                continue
            }
            flushWalk(prev, link)
            legs.add(linkLeg(link, prev, node))
            walkFrom = node
        }
        flushWalk(nodes.last(), null)
        val merged = mergeRepeats(legs)
        legs.clear()
        legs.addAll(merged)
        if (legs.isEmpty()) {
            legs.add(Leg(Leg.Kind.WALK, "You're already here", "", start, end, 0))
        }
        return legs
    }

    /** True when a link moves you to another floor, or between the surface and underground. */
    private fun changesLevel(a: Int, b: Int): Boolean {
        if (Packed.z(a) != Packed.z(b)) return true
        val ua = Packed.y(a) >= 6400
        val ub = Packed.y(b) >= 6400
        return ua != ub || chebyshev(a, b) > 12
    }

    /** "Climb-down Ladder" -> "Climb down the ladder". */
    private fun prettyAction(name: String, from: Int, to: Int): String {
        val m = Regex("^([A-Za-z]+)(?:-([a-z]+))?\\s+(.+)$").find(name.trim())
        if (m == null || name.isBlank()) {
            return when {
                Packed.z(to) > Packed.z(from) -> "Go upstairs"
                Packed.z(to) < Packed.z(from) -> "Go downstairs"
                else -> "Go through"
            }
        }
        val verb = m.groupValues[1]
        val particle = m.groupValues[2]
        val thing = m.groupValues[3].lowercase()
        return listOf(verb, particle, "the", thing).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Two identical steps in a row ("Climb up the ladder" twice) become one with "(x2)". */
    private fun mergeRepeats(legs: List<Leg>): List<Leg> {
        val out = ArrayList<Leg>()
        var count = 1
        for ((i, leg) in legs.withIndex()) {
            val next = legs.getOrNull(i + 1)
            if (next != null && next.kind == leg.kind && next.title == leg.title && leg.tiles == 0 && next.tiles == 0) {
                count++
                continue
            }
            out.add(if (count > 1) leg.copy(title = "${leg.title} (\u00D7$count)") else leg)
            count = 1
        }
        return out
    }

    private fun walkLeg(from: Int, to: Int, tiles: Int, planeChange: Int, toward: Link?): Leg {
        val dir = compass(from, to)
        val target = toward?.let { " to the ${originNoun(it)}" } ?: ""
        val climb = when {
            planeChange > 0 -> ", then go upstairs"
            planeChange < 0 -> ", then go downstairs"
            else -> ""
        }
        val title = if (tiles <= 2 && toward != null) "Go to the ${originNoun(toward)}$climb"
        else "Walk $tiles ${if (tiles == 1) "tile" else "tiles"}${if (dir.isNotEmpty()) " $dir" else ""}$target$climb"
        return Leg(Leg.Kind.WALK, title, "", from, to, tiles)
    }

    private fun originNoun(link: Link): String = when (link.type) {
        "FAIRY_RING" -> "fairy ring"
        "SPIRIT_TREE" -> "spirit tree"
        "GNOME_GLIDER" -> "glider"
        "MAGIC_CARPET" -> "carpet merchant"
        "CHARTER_SHIP" -> "charter crew on the docks"
        "SHIP", "BOAT" -> "boat"
        "CANOE" -> "canoe station"
        "MINECART" -> "minecart"
        "HOT_AIR_BALLOON" -> "balloon"
        "QUETZAL" -> "quetzal"
        "MAGIC_MUSHTREE" -> "mushtree"
        "AGILITY_SHORTCUT" -> "shortcut"
        "OBELISK" -> "obelisk"
        "LEVER" -> "lever"
        "PORTAL" -> "portal"
        else -> link.name.lowercase()
    }

    private fun requirementText(link: Link): String {
        val parts = ArrayList<String>()
        for ((s, l) in link.skills) parts.add("$s $l")
        if (link.items.isNotBlank()) parts.add(link.items)
        if (link.quests.isNotEmpty()) parts.add("needs " + link.quests.joinToString(", "))
        if (link.needsUnlock) parts.add("may need an unlock")
        return parts.joinToString(" · ")
    }

    private fun linkLeg(link: Link, from: Int, to: Int): Leg {
        if (link.poh.isNotEmpty()) return houseLeg(link, from, to)
        val dest = link.name.replace(Regex("^\\d+:\\s*"), "")
        val (kind, title) = when (link.type) {
            "SPELL" -> Leg.Kind.TELEPORT to "Cast $dest"
            "HOME_TELEPORT" -> Leg.Kind.TELEPORT to "Cast $dest (30-minute cooldown)"
            "TELEPORT_ITEM" -> Leg.Kind.TELEPORT to "Teleport with $dest"
            "MINIGAME" -> Leg.Kind.TELEPORT to "Grouping teleport: $dest (20-minute cooldown)"
            "FAIRY_RING" -> Leg.Kind.TRANSPORT to fairyTitle(dest, "Fairy ring")
            "CHARTER_SHIP" -> Leg.Kind.TRANSPORT to "Charter a ship to $dest"
            "SHIP", "BOAT" -> Leg.Kind.TRANSPORT to "Take the boat: $dest"
            "CANOE" -> Leg.Kind.TRANSPORT to "Canoe: $dest"
            "MAGIC_CARPET" -> Leg.Kind.TRANSPORT to "Magic carpet to $dest"
            "MINECART" -> Leg.Kind.TRANSPORT to "Minecart: $dest"
            "AGILITY_SHORTCUT" -> Leg.Kind.TRANSPORT to "Agility shortcut: $dest"
            "OBELISK" -> Leg.Kind.TRANSPORT to "Wilderness obelisk: $dest"
            "LEVER" -> Leg.Kind.TRANSPORT to "Pull the lever: $dest"
            "PORTAL" -> Leg.Kind.TRANSPORT to "Portal: $dest"
            else -> Leg.Kind.TRANSPORT to dest
        }
        return Leg(kind, title, requirementText(link), from, to, 0)
    }

    /** "Fairy ring AIQ" -> "Fairy ring: dial AIQ"; "Fairy ring to Zanaris" stays a sentence. */
    private fun fairyTitle(name: String, prefix: String): String = when {
        name.startsWith("Fairy ring to ") -> prefix + " to " + name.removePrefix("Fairy ring to ")
        name.startsWith("Fairy ring ") -> prefix + ": dial " + name.removePrefix("Fairy ring ")
        else -> "$prefix: $name"
    }

    private fun houseLeg(link: Link, from: Int, to: Int): Leg {
        val dest = link.name.replace(Regex("^[0-9A-Z]:\\s*"), "")
        val tag = link.poh
        val (kind, title) = when {
            tag == "arrive" && link.type == "SPELL" -> Leg.Kind.TELEPORT to "Cast Teleport to House"
            tag == "arrive" -> Leg.Kind.TELEPORT to dest.replace("Construction cape: Tele to POH", "Construction cape: teleport to your house")
                .replace("Teleport to House tablet", "Break a Teleport to House tablet")
            tag.startsWith("outside:") -> Leg.Kind.TELEPORT to when {
                link.type == "SPELL" -> "Cast Teleport to House, Outside option"
                link.name.startsWith("Construction cape") -> "Construction cape: Tele to POH (house set to teleport outside)"
                else -> "Teleport to House tablet: Outside option"
            }
            tag.startsWith("home:") && Packed.x(from) == 1858 -> Leg.Kind.TRANSPORT to "Leave through your house's exit portal"
            tag.startsWith("home:") -> Leg.Kind.TRANSPORT to "Enter your house through the house portal"
            tag.startsWith("portal:") -> Leg.Kind.TELEPORT to "In your house: ${dest}"
            tag.startsWith("box:") -> Leg.Kind.TELEPORT to "Jewellery box in your house: $dest"
            tag == "mount:glory" -> Leg.Kind.TELEPORT to "Mounted glory in your house: $dest"
            tag == "mount:mythical" -> Leg.Kind.TELEPORT to "Mounted mythical cape: Myths' Guild"
            tag == "mount:xeric" -> Leg.Kind.TELEPORT to "Mounted Xeric's talisman: $dest"
            tag == "mount:digsite" -> Leg.Kind.TELEPORT to "Mounted digsite pendant: $dest"
            tag == "fairy" && Packed.x(from) == 1858 -> Leg.Kind.TRANSPORT to fairyTitle(dest, "Fairy ring in your house")
            tag == "fairy" -> Leg.Kind.TRANSPORT to "Fairy ring: dial DIQ (your house)"
            tag == "spirit" && Packed.x(from) == 1858 -> Leg.Kind.TRANSPORT to dest.replace("Spirit tree to ", "Spirit tree in your house to ")
            tag == "spirit" -> Leg.Kind.TRANSPORT to "Spirit tree to your house"
            else -> Leg.Kind.TELEPORT to dest
        }
        return Leg(kind, title, requirementText(link), from, to, 0)
    }

    companion object {
        private const val BUCKETS = 512
        private val DX = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)
        private val DY = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)
        private val NAMES = arrayOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

        fun chebyshev(a: Int, b: Int): Int =
            max(abs(Packed.x(a) - Packed.x(b)), abs(Packed.y(a) - Packed.y(b)))

        private fun surfaceY(p: Int): Int = Packed.y(p).let { if (it >= 6400 && it < 10560) it - 6400 else it }

        fun compass(from: Int, to: Int): String {
            val dx = Packed.x(to) - Packed.x(from)
            val dy = surfaceY(to) - surfaceY(from)
            if (abs(dx) + abs(dy) < 3) return ""
            val deg = (Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())) + 360.0) % 360.0
            return NAMES[((deg + 22.5) / 45.0).toInt() % 8]
        }

        /** Rough walking time: running covers two tiles per 0.6-second tick. */
        fun walkSeconds(tiles: Int): Int = (tiles * 0.3).roundToInt()
    }
}
