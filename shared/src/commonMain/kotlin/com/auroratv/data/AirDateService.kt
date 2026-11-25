package com.auroratv.data

import com.auroratv.core.Log
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.time.TimeSource

/**
 * Upcoming episodes of a series for the detail page: the server's own metadata (one small request,
 * names, numbers and dates only), and Trakt's next episode when a Trakt key is set (an exact air
 * time, which fixes the date across time zones). Kept in memory per series for [FRESH_MS]; a page
 * opened twice at once asks once. Never on the way of the page: the caller loads it afterwards.
 */
class AirDateService(
    private val scope: CoroutineScope,
    private val ratings: com.auroratv.ratings.RatingsService,
    private val now: () -> Long = { com.auroratv.playback.wallClockMs() },
) {
    private class Entry(val list: List<UpcomingEpisode>, val loadedAt: TimeSource.Monotonic.ValueTimeMark)
    private val cache = LinkedHashMap<String, Entry>()
    private val loads = HashMap<String, Deferred<List<UpcomingEpisode>>>()

    /** From memory (no request), with anything that has aired since dropped; null if not loaded or old. */
    fun cached(seriesId: String): List<UpcomingEpisode>? = cache[seriesId]
        ?.takeIf { it.loadedAt.elapsedNow().inWholeMilliseconds < FRESH_MS }
        ?.list?.filter { it.date.isUpcoming(now()) }

    suspend fun upcoming(api: JellyfinApi, series: BaseItemDto): List<UpcomingEpisode> {
        if (!series.isSeries) return emptyList()
        cached(series.id)?.let { return it }
        loads[series.id]?.takeIf { !it.isCompleted }?.let { return it.await() }
        val d = scope.async {
            val t0 = TimeSource.Monotonic.markNow()
            val trakt = async { runCatching { ratings.nextEpisode(series) }.getOrNull() }
            val server = runCatching { api.upcomingEpisodes(series.id) }
                .onFailure { Log.w("AirDates", "Upcoming episodes unavailable: ${it.message}") }.getOrDefault(emptyList())
                .filter { it.seriesId == null || it.seriesId == series.id }
            val merged = merge(Upcoming.fromServer(server, now()), trakt.await(), now())
            Log.i("AirDates", "${series.name}: ${merged.size} upcoming episode(s) in ${t0.elapsedNow().inWholeMilliseconds} ms" +
                (Upcoming.next(merged)?.let { " (next ${it.date.text}${if (it.date.tentative) ", tentative" else ""})" } ?: ""))
            cache.remove(series.id); cache[series.id] = Entry(merged, TimeSource.Monotonic.markNow())
            while (cache.size > MAX_SERIES) cache.remove(cache.keys.first())
            merged
        }
        loads[series.id] = d
        return try { d.await() } finally { if (d.isCompleted) loads.remove(series.id) }
    }

    companion object {
        /** Air dates change rarely; six hours keeps a schedule change from being missed for long. */
        const val FRESH_MS = 6 * 60 * 60_000L
        private const val MAX_SERIES = 50

        /**
         * Trakt's next episode joins the server's list: its exact time replaces a server date for the
         * same episode, or it's added when the server doesn't list it.
         */
        fun merge(server: List<UpcomingEpisode>, trakt: com.auroratv.ratings.RatingsService.NextEpisode?, nowMs: Long, offset: (Long) -> Long = ::localUtcOffsetMs): List<UpcomingEpisode> {
            val t = trakt?.let { Upcoming.airDate(it.firstAired, it.title, nowMs, offset) }
                ?.let { UpcomingEpisode(trakt.season, trakt.number, trakt.title, it) }
                ?: return server
            val same = server.indexOfFirst { it.seasonNumber == t.seasonNumber && it.episodeNumber == t.episodeNumber }
            val list = if (same >= 0) server.toMutableList().also { l ->
                val s = l[same]
                // A firm server date that disagrees by days isn't overridden by a tentative one.
                if (s.date.instantMs == null && !(t.date.tentative && !s.date.tentative)) l[same] = s.copy(date = t.date.copy(tentative = s.date.tentative && t.date.tentative))
            } else server + t
            return list.sortedWith(compareBy<UpcomingEpisode>({ it.date.day }, { it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }))
        }
    }
}
