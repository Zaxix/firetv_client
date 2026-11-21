package com.auroratv.profiles

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.jellyfin.LocalWatchData
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.UserItemData
import com.auroratv.platform.FileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A device-only profile's watch history: per item, how far it got, whether it's watched, whether
 * it's a favourite, and when. One small JSON file per profile (`watch-<profile>.json`), at most
 * [MAX] items (oldest dropped first); items with nothing to remember aren't kept. Follows Jellyfin's
 * rules: under 5% watched isn't a resume point, 90% or more counts as watched.
 */
@OptIn(ExperimentalAtomicApi::class)
class LocalWatchStore(
    private val files: FileStore,
    profileKey: String,
    private val now: () -> Long = { com.auroratv.playback.wallClockMs() },
    /** File kind: "watch" for a device-only profile, "split-watch" for virtual episodes. */
    base: String = "watch",
) : LocalWatchData {
    /** p = position (ticks), w = watched, f = favourite, t = last watched (epoch ms), rt = runtime (ticks), s = series id. */
    @Serializable
    private data class E(val p: Long = 0, val w: Boolean = false, val f: Boolean = false, val t: Long = 0, val rt: Long = 0, val s: String? = null)

    private val file = ProfileFiles.name(base, profileKey)
    private val serializer = MapSerializer(String.serializer(), E.serializer())
    // Copy-on-write maps: read from any thread, updated with compare-and-set (they're small).
    private val entries = AtomicReference(load())
    /** Runtime and series of items seen on screen, for progress/next-up maths (not saved). */
    private val seen = AtomicReference<Map<String, Pair<Long, String?>>>(emptyMap())

    private fun load(): Map<String, E> = runCatching { files.read(file)?.let { AuroraJson.decodeFromString(serializer, it) } }
        .onFailure { Log.w("Profiles", "Watch history unreadable, starting fresh: ${it.message}") }.getOrNull() ?: emptyMap()

    private fun save(map: Map<String, E>) {
        runCatching { files.write(file, AuroraJson.encodeToString(serializer, map)) }.onFailure { Log.w("Profiles", "Couldn't save watch history: ${it.message}") }
    }

    private inline fun change(id: String, crossinline f: (E?) -> E) {
        while (true) {
            val old = entries.load()
            val next = f(old[id])
            var map = if (next.p == 0L && !next.w && !next.f) old - id else old + (id to next)
            if (map.size > MAX) map = map.entries.sortedByDescending { it.value.t }.take(MAX).associate { it.key to it.value }
            if (entries.compareAndSet(old, map)) { save(map); return }
        }
    }

    override fun userData(item: BaseItemDto): UserItemData {
        val series = if (item.isEpisode) item.seriesId else null
        val rt = item.runTimeTicks ?: 0
        if (rt > 0 || series != null) while (true) {
            val old = seen.load()
            if (old[item.id]?.let { it.first == rt && it.second == series } == true) break
            val next = (if (old.size > MAX) emptyMap() else old) + (item.id to (rt to series))
            if (seen.compareAndSet(old, next)) break
        }
        val e = entries.load()[item.id]
        val seriesWatched = series?.let { entries.load()[it]?.w } == true
        val played = e?.w ?: seriesWatched
        val runtime = e?.rt?.takeIf { it > 0 } ?: rt
        val pos = e?.p ?: 0
        return UserItemData(
            playbackPositionTicks = pos,
            playedPercentage = if (runtime > 0 && pos > 0) pos * 100.0 / runtime else null,
            played = played,
            isFavorite = e?.f == true,
            playCount = if (played) 1 else 0,
            // Fixed-width number: sorts like a date where the app compares "last played" values.
            lastPlayedDate = e?.t?.takeIf { it > 0 }?.toString()?.padStart(15, '0'),
        )
    }

    private fun meta(id: String) = seen.load()[id]

    /** Anything recorded for [id] (progress, watched, favourite). */
    fun has(id: String): Boolean = entries.load()[id] != null
    fun isWatched(id: String): Boolean = entries.load()[id]?.w == true

    override fun setPlayed(itemId: String, played: Boolean) {
        change(itemId) { e -> (e ?: E(s = meta(itemId)?.second)).copy(w = played, p = 0, t = if (played) now() else (e?.t ?: 0)) }
        if (!played) {
            // Unmarking a whole series also unmarks its episodes.
            entries.load().filter { it.value.s == itemId && it.value.w }.keys.forEach { ep -> change(ep) { e -> e!!.copy(w = false) } }
        }
    }

    override fun setFavorite(itemId: String, favorite: Boolean) =
        change(itemId) { e -> (e ?: E(s = meta(itemId)?.second)).copy(f = favorite, t = e?.t?.takeIf { it > 0 } ?: now()) }

    override fun progress(itemId: String, positionTicks: Long, stopped: Boolean) {
        val (seenRt, series) = meta(itemId) ?: (0L to null)
        change(itemId) { e ->
            val rt = seenRt.takeIf { it > 0 } ?: e?.rt ?: 0
            val fraction = if (rt > 0) positionTicks.toDouble() / rt else 0.0
            val base = (e ?: E()).copy(t = now(), rt = rt, s = series ?: e?.s)
            when {
                stopped && fraction >= WATCHED -> base.copy(w = true, p = 0)
                stopped && rt > 0 && fraction < RESUMABLE -> base.copy(p = 0)
                else -> base.copy(p = positionTicks)
            }
        }
    }

    private fun recent(filter: (E) -> Boolean) = entries.load().entries.filter { filter(it.value) }.sortedByDescending { it.value.t }

    override fun resumeIds(limit: Int) = recent { it.p > 0 && !it.w }.take(limit).map { it.key }
    override fun favoriteIds() = recent { it.f }.map { it.key }
    override fun playedIds(limit: Int) = recent { it.w }.take(limit).map { it.key }
    override fun recentSeries(limit: Int) = recent { it.s != null && (it.w || it.p > 0) }.mapNotNull { it.value.s }.distinct().take(limit)
    override fun lastFinishedEpisode(seriesId: String) = recent { it.s == seriesId && it.w }.firstOrNull()?.key
    override fun lastWatched(itemId: String) = entries.load()[itemId]?.t?.takeIf { it > 0 }

    private companion object {
        const val MAX = 3000
        const val WATCHED = 0.9
        const val RESUMABLE = 0.05
    }
}
