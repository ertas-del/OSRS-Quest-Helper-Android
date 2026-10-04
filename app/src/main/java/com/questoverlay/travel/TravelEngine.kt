package com.questoverlay.travel

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** A named starting point, e.g. "Varrock West bank". */
data class Place(val name: String, val tile: Int)

/**
 * Loads the map and transport data once (in the background) and works out routes off the
 * main thread. Results are cached, so flipping between steps doesn't redo the work.
 */
object TravelEngine {

    sealed class Result {
        data class Found(val route: Route) : Result()
        object NotFound : Result()
        object TooFar : Result()
        data class Failed(val message: String) : Result()
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "route-finder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var finder: RouteFinder? = null
    @Volatile private var placesCache: List<Place>? = null
    private data class Key(val start: Int, val target: Int, val profile: TravelProfile)

    private val cache = object : LinkedHashMap<Key, Result>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Result>?) = size > 64
    }

    /** Only the most recent request matters; older queued ones are skipped. */
    @Volatile private var latest: Key? = null

    fun places(context: Context): List<Place> {
        placesCache?.let { return it }
        val list = ArrayList<Place>()
        try {
            context.applicationContext.assets.open("places.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val c = line.split('\t')
                    if (c.size < 2) continue
                    val p = c[1].split(',').mapNotNull { it.toIntOrNull() }
                    if (p.size == 3) list.add(Place(c[0], Packed.pack(p[0], p[1], p[2])))
                }
            }
        } catch (e: Exception) {
            list.add(Place("Lumbridge (spawn)", Packed.pack(3222, 3218, 0)))
        }
        if (list.isEmpty()) list.add(Place("Lumbridge (spawn)", Packed.pack(3222, 3218, 0)))
        val sorted = listOf(list.first()) + list.drop(1).sortedBy { it.name.lowercase() }
        placesCache = sorted
        return sorted
    }

    @Volatile private var portalNamesCache: List<String>? = null

    /** Names of the portals (and nexus destinations) a house can have, read from the transport data. */
    fun housePortalNames(context: Context): List<String> {
        portalNamesCache?.let { return it }
        val names = LinkedHashSet<String>()
        try {
            context.applicationContext.assets.open("transports.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val i = line.lastIndexOf('\t')
                    if (i < 0) continue
                    val tag = line.substring(i + 1)
                    if (tag.startsWith("portal:")) names.add(tag.removePrefix("portal:"))
                }
            }
        } catch (e: Exception) {
            // No list means no portal choices; everything else still works.
        }
        val sorted = names.sortedWith(compareBy({ it.startsWith("Respawn") }, { it.lowercase() }))
        portalNamesCache = sorted
        return sorted
    }

    /** Cached answer, if this exact question has been asked before. */
    fun cached(start: Int, target: Int, profile: TravelProfile): Result? =
        synchronized(cache) { cache[Key(start, target, profile)] }

    /** Works out a route in the background and calls [onDone] on the main thread. */
    fun route(context: Context, start: Int, target: Int, profile: TravelProfile, onDone: (Result) -> Unit) {
        val k = Key(start, target, profile)
        latest = k
        synchronized(cache) { cache[k] }?.let { hit ->
            main.post { onDone(hit) } // never call back inside the caller's own drawing pass
            return
        }
        val app = context.applicationContext
        worker.execute {
            if (latest != k) return@execute // the player has already moved on to another step
            synchronized(cache) { cache[k] }?.let { hit ->
                main.post { onDone(hit) }
                return@execute
            }
            val result = try {
                val f = finder ?: load(app)
                val r = f.find(start, target, profile)
                when {
                    r != null -> Result.Found(r)
                    f.lastHitLimit -> Result.TooFar
                    else -> Result.NotFound
                }
            } catch (e: OutOfMemoryError) {
                Result.Failed("Not enough memory to plan this route.")
            } catch (e: Exception) {
                Result.Failed("Couldn't plan a route: ${e.message ?: e.javaClass.simpleName}")
            }
            // Failures are cached too, so a broken step isn't retried over and over.
            synchronized(cache) { cache[k] = result }
            main.post { onDone(result) }
        }
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private fun load(context: Context): RouteFinder {
        val map = context.assets.open("collision-map.zip").use { CollisionMap.load(it) }
        val table = context.assets.open("transports.tsv").use { TransportTable.load(it) }
        return RouteFinder(map, table).also { finder = it }
    }
}
