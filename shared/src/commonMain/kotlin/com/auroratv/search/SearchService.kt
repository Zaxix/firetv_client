package com.auroratv.search

import com.auroratv.core.Log
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Holds the [SearchIndex] for the signed-in user: one lightweight request for every movie, series
 * and collection title (names, years, genres and one poster tag; no watch data), made the first time
 * Search opens and refreshed after half an hour. Everything typed after that is matched on the device.
 */
class SearchService(private val scope: CoroutineScope) {
    private var current: Triple<JellyfinApi, Deferred<SearchIndex?>, TimeMark>? = null

    /** Starts (or reuses) loading the index for [api]; cheap to call repeatedly. */
    fun index(api: JellyfinApi): Deferred<SearchIndex?> {
        current?.let { (a, d, at) ->
            val failed = d.isCompleted && runCatching { d.getCompletedOrNull() }.getOrNull() == null
            if (a === api && at.elapsedNow() < REFRESH && !failed) return d
        }
        val started = TimeSource.Monotonic.markNow()
        val d = scope.async {
            runCatching {
                val items = api.items(
                    ItemQuery(
                        includeItemTypes = listOf("Movie", "Series", "BoxSet"), recursive = true, limit = MAX_TITLES,
                        sortBy = "SortName", fields = "Genres,OriginalTitle,PrimaryImageAspectRatio", totalCount = false,
                        userData = false, imageTypes = "Primary",
                    )
                ).items
                withContext(Dispatchers.Default) { SearchIndex(items) }
            }.onSuccess { Log.i("Search", "Index: ${it.size} titles in ${started.elapsedNow().inWholeMilliseconds} ms") }
                .onFailure { Log.w("Search", "Index failed: ${it.message}") }
                .getOrNull()
        }
        current = Triple(api, d, started)
        return d
    }

    /** People whose names match [query] and their titles (best match only), for a "Starring …" row. */
    suspend fun people(api: JellyfinApi, query: String): Pair<BaseItemDto, List<BaseItemDto>>? {
        val q = query.trim()
        if (q.length < 3) return null
        val person = api.persons(q, limit = 5).firstOrNull { p ->
            val name = SearchIndex.normalize(p.name.orEmpty())
            SearchIndex.normalize(q).split(' ').all { w -> name.split(' ').any { it.startsWith(w) } }
        } ?: return null
        val titles = api.items(ItemQuery(personIds = listOf(person.id), includeItemTypes = listOf("Movie", "Series"), recursive = true, limit = 30, sortBy = "PremiereDate,SortName", sortDescending = true, totalCount = false)).items
        return if (titles.isEmpty()) null else person to titles
    }

    /** Episodes whose names match (the index holds only movies/series, episodes are far too many). */
    suspend fun episodes(api: JellyfinApi, query: String): List<BaseItemDto> =
        api.items(ItemQuery(searchTerm = query.trim(), includeItemTypes = listOf("Episode"), recursive = true, limit = 20, totalCount = false)).items

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> Deferred<T>.getCompletedOrNull(): T? = if (isCompleted && !isCancelled) getCompleted() else null

    companion object {
        private val REFRESH = 30.minutes
        private const val MAX_TITLES = 20_000
    }
}
