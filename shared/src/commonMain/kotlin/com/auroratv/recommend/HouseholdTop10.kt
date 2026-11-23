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
 * "Top 10 in Your Home": the films and shows watched most on this server, ranked by time spent
 * watching them in the last [WINDOW_DAYS] days, then by all-time time when fewer than ten were
 * watched lately. Whose viewing counts: every profile this device knows (account profiles' history
 * from the server, device-only profiles' from the device) and, when the signed-in account is a
 * server administrator, every other account on the server too (people who watch on other devices).
 *
 * Only real viewing counts: with fewer than [MIN] titles watched in the whole house the row isn't
 * shown (nothing is made up from ratings or popularity, and nothing is called "most watched"
 * without being watched). The titles are fetched as the profile that's looking, so nothing it
 * can't see on the server (another library, a parental rating) ever appears.
 *
 * Worked out at most every [TTL_MS] per server; after something is watched ([invalidate]) it's
 * worked out again, but not more than once every [REFRESH_MS]. Needs no internet.
 */
class HouseholdTop10(private val now: () -> Long = { com.auroratv.playback.wallClockMs() }) {
    private class Cached(val serverId: String, val atMs: Long, val ids: List<String>, val stale: Boolean = false)
    @kotlin.concurrent.Volatile private var cached: Cached? = null
    /** The last row as fetched for a viewer, reused while the ranking hasn't changed. */
    private class Shown(val viewer: JellyfinApi, val serverId: String, val ids: List<String>, val items: List<BaseItemDto>)
    @kotlin.concurrent.Volatile private var shown: Shown? = null

    /** Something was watched: the ranking is worked out again next time (at most every [REFRESH_MS]). */
    fun invalidate() {
        cached = cached?.let { Cached(it.serverId, it.atMs, it.ids, stale = true) }
        shown = null
    }

    /**
     * The household's top ten, as [viewer] sees them. [accounts]: one client per account profile on
     * this device (its own sign-in); [locals]: device-only profiles' histories. Fewer than [MIN]
     * titles watched in total: an empty list (no row).
     */
    suspend fun top(viewer: JellyfinApi, serverId: String, accounts: List<JellyfinApi>, locals: List<LocalWatchStore>): List<BaseItemDto> {
        val c = cached
        val age = c?.let { now() - it.atMs } ?: Long.MAX_VALUE
        val reuse = c != null && c.serverId == serverId && age < TTL_MS && !(c.stale && age >= REFRESH_MS)
        val ids = if (reuse) c!!.ids else run {
            val onDevice = accounts.mapNotNull { it.userId }.toSet()
            // An administrator's sign-in can read every account's history; anyone else's is refused.
            val others = runCatching { viewer.serverUsers() }
                .map { users -> users.filter { !(it.policy?.isDisabled ?: false) && it.id !in onDevice }.map { it.id } }
                .getOrElse { emptyList() }
            val watches = coroutineScope {
                val mine = accounts.map { a -> async { history { a.watchHistory() } } }
                val theirs = others.map { id -> async { history { viewer.watchHistory(forUser = id) } } }
                (mine + theirs).awaitAll().flatten()
            } + locals.flatMap { fromLocal(it.history()) }
            rank(watches, now(), CANDIDATES).also {
                cached = Cached(serverId, now(), it)
                Log.i(TAG, "${accounts.size + others.size + locals.size} profile(s) (${others.size} from the server), ${watches.size} things watched, ${it.size} titles ranked")
            }
        }
        if (ids.size < MIN) {
            Log.i(TAG, "Top 10 in Your Home: not enough watched yet (${ids.size} title(s), needs $MIN), row not shown")
            return emptyList()
        }
        shown?.takeIf { it.viewer === viewer && it.serverId == serverId && it.ids == ids }?.let { return it.items }
        // As the viewer: only what this profile is allowed to see, in ranked order.
        val items = viewer.items(ItemQuery(ids = ids, includeItemTypes = listOf("Movie", "Series"), sortBy = "SortName", limit = ids.size, totalCount = false)).items
        val byId = items.associateBy { it.id }
        val list = ids.mapNotNull { byId[it] }.take(SIZE).takeIf { it.size >= MIN }.orEmpty()
        shown = Shown(viewer, serverId, ids, list)
        Log.i(TAG, "Top 10 in Your Home: ${list.size} titles shown (of ${ids.size} ranked): " + list.take(3).joinToString { "\"${it.name}\"" })
        return list
    }

    private suspend fun history(read: suspend () -> List<BaseItemDto>): List<HouseholdWatch> =
        runCatching { fromServer(read()) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "A profile's history couldn't be read: ${it.message}") }
            .getOrDefault(emptyList())

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
        /** After something is watched, the ranking is worked out again no sooner than this. */
        const val REFRESH_MS = 60L * 1000

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
