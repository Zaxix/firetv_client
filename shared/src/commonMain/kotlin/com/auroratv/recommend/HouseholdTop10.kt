package com.auroratv.recommend

import com.auroratv.core.Log
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.profiles.LocalWatchStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Some watching of one title (a film, or a show by any of its episodes): how much, and when. */
data class HouseholdWatch(val titleId: String, val ticks: Long, val atMs: Long)

/**
 * "Top 10 in Your Home": the films and shows watched most on this server by every profile this
 * device knows (account profiles' history from the server, device-only profiles' from the device),
 * ranked by time spent watching them in the last [WINDOW_DAYS] days, then by all-time time when
 * fewer than ten were watched lately.
 *
 * The titles are then fetched as the profile that's looking, so nothing it can't see on the server
 * (another library, a parental rating) ever appears. Worked out at most every [TTL_MS] per server;
 * Home keeps the last row on screen in the meantime. Needs no internet.
 */
class HouseholdTop10(private val now: () -> Long = { com.auroratv.playback.wallClockMs() }) {
    private class Cached(val serverId: String, val atMs: Long, val ids: List<String>)
    @kotlin.concurrent.Volatile private var cached: Cached? = null

    /** Forgets the ranking (something was watched), so the next Home works it out again. */
    fun invalidate() { cached = null }

    /**
     * The household's top ten, as [viewer] sees them. [accounts]: one client per account profile on
     * the server (its own sign-in); [locals]: device-only profiles' histories. Fewer than [MIN] titles
     * watched in total: no row.
     */
    suspend fun top(viewer: JellyfinApi, serverId: String, accounts: List<JellyfinApi>, locals: List<LocalWatchStore>): List<BaseItemDto> {
        val ids = cached?.takeIf { it.serverId == serverId && now() - it.atMs < TTL_MS }?.ids ?: run {
            val watches = coroutineScope {
                accounts.map { a -> async { runCatching { fromServer(a.watchHistory()) }.onFailure { Log.w(TAG, "A profile's history couldn't be read: ${it.message}") }.getOrDefault(emptyList()) } }.awaitAll().flatten()
            } + locals.flatMap { fromLocal(it.history()) }
            rank(watches, now(), CANDIDATES).also {
                cached = Cached(serverId, now(), it)
                Log.i(TAG, "${accounts.size + locals.size} profile(s), ${watches.size} things watched, ${it.size} titles ranked")
            }
        }
        if (ids.size < MIN) return emptyList()
        // As the viewer: only what this profile is allowed to see, in ranked order.
        val items = viewer.items(ItemQuery(ids = ids, includeItemTypes = listOf("Movie", "Series"), sortBy = "SortName", limit = ids.size, totalCount = false)).items
        val byId = items.associateBy { it.id }
        val shown = ids.mapNotNull { byId[it] }.take(SIZE).takeIf { it.size >= MIN }.orEmpty()
        Log.i(TAG, "Top 10 in Your Home: ${shown.size} titles shown (of ${ids.size} ranked): " + shown.take(3).joinToString { "\"${it.name}\"" })
        return shown
    }

    companion object {
        private const val TAG = "Top10"
        const val SIZE = 10
        /** Fewer titles than this watched: no row (a "top 2" isn't a list). */
        const val MIN = 3
        /** Candidates kept, so titles the viewer can't see can be replaced by the next ones. */
        const val CANDIDATES = 30
        const val WINDOW_DAYS = 30
        private const val WINDOW_MS = WINDOW_DAYS * 24L * 3600 * 1000
        const val TTL_MS = 30L * 60 * 1000

        /**
         * Title ids, most watched first: time watched in the last [WINDOW_DAYS] days (ties: the
         * one watched more recently), then the rest by all-time time watched.
         */
        fun rank(watches: List<HouseholdWatch>, nowMs: Long, limit: Int = SIZE): List<String> {
            fun ranked(ws: List<HouseholdWatch>) = ws.groupBy { it.titleId }
                .map { (id, w) -> Triple(id, w.sumOf { it.ticks }, w.maxOf { it.atMs }) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Triple<String, Long, Long>> { it.second }.thenByDescending { it.third })
                .map { it.first }
            val recent = ranked(watches.filter { nowMs - it.atMs in 0..WINDOW_MS })
            val seen = recent.toHashSet()
            return (recent + ranked(watches).filter { it !in seen }).take(limit)
        }

        /** A server history (films and episodes): a finished one counts its length per play; a started one, how far it got. */
        fun fromServer(items: List<BaseItemDto>): List<HouseholdWatch> = items.mapNotNull { item ->
            val title = when {
                item.isEpisode -> item.seriesId
                item.type == "Movie" -> item.id
                else -> null
            } ?: return@mapNotNull null
            val u = item.userData ?: return@mapNotNull null
            val at = u.lastPlayedDate?.let(::parseIsoMs) ?: return@mapNotNull null
            val ticks = if (u.played) (item.runTimeTicks ?: 0L) * u.playCount.coerceAtLeast(1) else u.playbackPositionTicks
            HouseholdWatch(title, ticks, at)
        }

        /** A device-only profile's history (see LocalWatchStore.history). */
        fun fromLocal(entries: List<LocalWatchStore.HistoryEntry>): List<HouseholdWatch> = entries.map { e ->
            HouseholdWatch(e.seriesId ?: e.id, if (e.watched) e.runtimeTicks else e.positionTicks, e.atMs)
        }

        /** "2026-11-05T10:00:00.0000000Z" → epoch ms; null when it isn't a date. */
        @OptIn(kotlin.time.ExperimentalTime::class)
        fun parseIsoMs(s: String): Long? = runCatching { kotlin.time.Instant.parse(s).toEpochMilliseconds() }.getOrNull()
    }
}
