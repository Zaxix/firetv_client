package com.auroratv.jellyfin

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TMDB's episode groups: the alternative orders people have documented for a show (DVD,
 * production, absolute, an intended order...). Needs the viewer's own free TMDB key (Settings →
 * Ratings): a v3 API key or a v4 read access token. The key goes only to TMDB and is never logged.
 * Asked rarely: a series' list once a week at most, a group once (see EpisodeOrders).
 */
class TmdbEpisodeGroups(private val http: HttpClient, private val key: () -> String) {
    val available: Boolean get() = key().isNotBlank()

    /** The orders TMDB lists for the series [tmdbSeriesId]; null when TMDB couldn't be asked. */
    suspend fun list(tmdbSeriesId: String): List<SourceOrderSummary>? =
        get<GroupList>("tv/$tmdbSeriesId/episode_groups")?.results?.map { SourceOrderSummary(it.id, it.name, it.type, it.episodeCount) }

    /** One order with its seasons and episodes; null when TMDB couldn't be asked. */
    suspend fun details(groupId: String): SourceOrder? {
        val g = get<GroupDetails>("tv/episode_group/$groupId") ?: return null
        val groups = g.groups.sortedBy { it.order }
        var regular = 0
        val seasons = groups.map { grp ->
            val number = EpisodeOrders.seasonNumber(grp.name, regular)
            if (number != 0) regular++
            GroupSeason(number, grp.name.ifBlank { null }, grp.episodes.sortedBy { it.order }.map { GroupEpisode(it.seasonNumber, it.episodeNumber, it.id, it.name) })
        }
        // Two groups claiming one season number (names like "Part 1" after "Season 1"): number them in order instead.
        val clean = if (seasons.map { it.number }.distinct().size == seasons.size) seasons
        else { var n = 0; seasons.map { s -> if (s.number == 0) s else s.copy(number = ++n) } }
        return SourceOrder(g.id, g.name, g.type, clean)
    }

    /** Whether the key works (for Settings → Save and test). */
    suspend fun test(): String = try {
        val k = key().trim()
        val r = http.get("https://api.themoviedb.org/3/configuration") {
            if (k.startsWith("eyJ")) header("Authorization", "Bearer $k") else parameter("api_key", k)
        }
        when {
            r.status.isSuccess() -> "key works"
            r.status.value == 401 -> "key rejected by TMDB"
            else -> "TMDB answered HTTP ${r.status.value}"
        }
    } catch (c: CancellationException) { throw c } catch (t: Throwable) { "couldn't reach TMDB (${t::class.simpleName})" }

    private suspend inline fun <reified T> get(path: String): T? {
        val k = key().trim()
        if (k.isEmpty()) return null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                val r = http.get("https://api.themoviedb.org/3/$path") {
                    // A v4 read access token is a JWT; a v3 API key is 32 hex characters.
                    if (k.startsWith("eyJ")) header("Authorization", "Bearer $k") else parameter("api_key", k)
                    header("Accept", "application/json")
                }
                if (!r.status.isSuccess()) { Log.w(TAG, "TMDB answered ${r.status.value} for an episode-group request"); return@withTimeoutOrNull null }
                AuroraJson.decodeFromString<T>(r.bodyAsText())
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "TMDB episode groups unavailable: ${t::class.simpleName}"); null
        }
    }

    @Serializable private class GroupList(val results: List<GroupSummary> = emptyList())
    @Serializable private class GroupSummary(
        val id: String,
        val name: String = "",
        val type: Int = 0,
        @SerialName("episode_count") val episodeCount: Int = 0,
    )
    @Serializable private class GroupDetails(val id: String, val name: String = "", val type: Int = 0, val groups: List<Group> = emptyList())
    @Serializable private class Group(val name: String = "", val order: Int = 0, val episodes: List<Episode> = emptyList())
    @Serializable private class Episode(
        val id: Long? = null,
        val name: String? = null,
        @SerialName("season_number") val seasonNumber: Int,
        @SerialName("episode_number") val episodeNumber: Int,
        val order: Int = 0,
    )

    private companion object {
        const val TAG = "EpisodeOrder"
        const val TIMEOUT_MS = 8_000L
    }
}
