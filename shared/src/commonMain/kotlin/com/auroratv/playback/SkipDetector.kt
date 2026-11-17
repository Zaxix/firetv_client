package com.auroratv.playback

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.ChapterInfo
import com.auroratv.jellyfin.model.ticksToMs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs

/**
 * Finds intro / recap / credits ranges for episodes the server hasn't marked, so the yellow
 * timeline sections and Skip buttons work on more of the library. Sources, best first:
 *
 * 1. **Recurring chapters** across the season: an unnamed chapter of the same length (±1.5 s) in
 *    other episodes, near the start (opening) or end (ending). Exact for the user's own files
 *    and fully local.
 * 2. **AniSkip** (anime only, crowd-sourced, optional): looked up by MyAnimeList id, using the
 *    item's provider ids or an AniList title search that must match the series' title or year.
 *    Results must fit the file's length (AniSkip only returns entries within 20 s of it).
 *
 * 3. **Matching audio** (any show, no plugin): the theme song / credits music this episode shares
 *    with a neighbouring one, found on the device from low-rate audio the server provides.
 *
 * Server media segments and chapter names still win; this only fills kinds that are missing.
 * Online lookups send only the series title / MAL id and episode number, never server data.
 */
class SkipDetector(
    private val api: JellyfinApi,
    /** Plain client: never carries the Jellyfin token. */
    private val http: HttpClient,
    /** Listening for the shared theme/credits music (any show, no plugin needed); see [fromAudio]. */
    private val audioAllowed: () -> Boolean = { true },
    private val onlineAllowed: () -> Boolean,
) {
    private val tag = "SkipDetector"
    private val malCache = mutableMapOf<String, MalEntry?>()
    private val anilistCache = mutableMapOf<String, AniListMedia?>()

    /** [others]: ranges worked out on the way for other episodes (the one compared against). */
    data class Result(val ranges: List<SkipRange>, val source: String, val others: Map<String, List<SkipRange>> = emptyMap())

    suspend fun detect(item: BaseItemDto, alreadyHave: Set<SkipRange.Kind>): Result? {
        if (!item.isEpisode) return null
        val runtime = item.runtimeMs ?: return null
        val found = mutableListOf<Result>()
        runCatching { fromSeasonChapters(item, runtime) }
            .onFailure { Log.w(tag, "Chapter pattern lookup failed: ${it.message}") }
            .getOrNull()?.let { found += it }
        val covered = alreadyHave + found.flatMap { r -> r.ranges.map { it.kind } }
        if (onlineAllowed() && !covered.containsAll(listOf(SkipRange.Kind.Intro, SkipRange.Kind.Credits))) {
            // Real-time timeout (Dispatchers.Default), so a slow third-party API never holds anything up.
            runCatching { withContext(Dispatchers.Default) { withTimeoutOrNull(8_000) { fromAniSkip(item, runtime) } } }
                .onFailure { Log.w(tag, "AniSkip lookup failed: ${it.message}") }
                .getOrNull()?.let { found += it }
        }
        val stillMissing = listOf(SkipRange.Kind.Intro, SkipRange.Kind.Credits)
            .filter { it !in alreadyHave && found.none { r -> r.ranges.any { x -> x.kind == it } } }
        if (audioAllowed() && stillMissing.isNotEmpty()) {
            runCatching { withContext(Dispatchers.Default) { withTimeoutOrNull(90_000) { fromAudio(item, runtime, stillMissing.toSet()) } } }
                .onFailure { Log.w(tag, "Audio match failed: ${it.message}") }
                .getOrNull()?.let { found += it }
        }
        if (found.isEmpty()) return null
        // Per kind, take the best source; never override what the server already provided.
        val ranges = SkipRange.Kind.entries.filter { it !in alreadyHave }.flatMap { kind ->
            found.firstNotNullOfOrNull { r -> r.ranges.filter { it.kind == kind }.takeIf { it.isNotEmpty() } }.orEmpty()
        }
        if (ranges.isEmpty()) return null
        val sources = found.filter { r -> r.ranges.any { it in ranges } }.joinToString(" + ") { it.source }
        Log.i(tag, "Detected ${ranges.joinToString { "${it.kind} ${it.startMs / 1000}-${it.endMs / 1000}s" }} via $sources")
        return Result(ranges, sources, found.fold(emptyMap<String, List<SkipRange>>()) { acc, r -> acc + r.others })
    }

    // ---- 1. Recurring chapters ---------------------------------------------------------------

    private suspend fun fromSeasonChapters(item: BaseItemDto, runtime: Long): Result? {
        if (item.chapters.size < 3) return null
        val seriesId = item.seriesId ?: return null
        val others = api.episodesWithChapters(seriesId, item.seasonId)
            .filter { it.id != item.id && it.chapters.size >= 3 }
            .take(12)
            .mapNotNull { ep -> ep.runtimeMs?.let { ep.chapters to it } }
        val ranges = recurringChapterRanges(item.chapters, runtime, others)
        return ranges.takeIf { it.isNotEmpty() }?.let { Result(it, "matching chapters") }
    }

    // ---- 3. Listening: the theme song and credits music this episode shares with another ----------

    /**
     * Works for any show, anime or not, with no plugin and nothing sent outside the server: fetch
     * the opening minutes (and the last few) of this episode and of a neighbouring one in the same
     * season as low-rate audio, and find the stretch of sound they share (see AudioFingerprint).
     * About 4 KB per second of audio; the server only decodes sound for it.
     */
    private suspend fun fromAudio(item: BaseItemDto, runtime: Long, want: Set<SkipRange.Kind>): Result? {
        val seriesId = item.seriesId ?: return null
        val season = api.episodesWithChapters(seriesId, item.seasonId).filter { it.runtimeMs != null }
        val at = season.indexOfFirst { it.id == item.id }
        // The previous episode if there is one (most likely already watched, its markers cached), else the next.
        val other = (if (at > 0) season[at - 1] else season.getOrNull(at + 1))?.takeIf { it.id != item.id } ?: return null
        val otherRuntime = other.runtimeMs ?: return null
        val rate = AudioFingerprint.SAMPLE_RATE
        val mine = mutableListOf<SkipRange>(); val theirs = mutableListOf<SkipRange>()

        suspend fun prints(id: String, startMs: Long, seconds: Int): IntArray? =
            api.audioSample(id, startMs, seconds, rate)?.let { AudioFingerprint.fingerprint(AudioFingerprint.wavSamples(it)) }
                ?.takeIf { it.size * AudioFingerprint.FRAME_MS >= seconds * 1000L / 2 } // too short: the server gave up

        val minFrames = (MIN_SHARED_MS / AudioFingerprint.FRAME_MS).toInt()
        if (SkipRange.Kind.Intro in want) {
            // Openings sit in the first quarter (at most 10 minutes), after any cold open.
            val secs = { rt: Long -> (minOf(rt / 4, INTRO_WINDOW_MS) / 1000).toInt() }
            val a = prints(item.id, 0, secs(runtime)); val b = a?.let { prints(other.id, 0, secs(otherRuntime)) }
            val m = if (a != null && b != null) AudioFingerprint.longestShared(a, b, minFrames) else null
            if (m != null && m.frames * AudioFingerprint.FRAME_MS <= MAX_INTRO_MS) {
                mine += SkipRange(SkipRange.Kind.Intro, m.aStart * AudioFingerprint.FRAME_MS, m.aEnd * AudioFingerprint.FRAME_MS)
                theirs += SkipRange(SkipRange.Kind.Intro, m.bStart * AudioFingerprint.FRAME_MS, m.bEnd * AudioFingerprint.FRAME_MS)
            }
        }
        if (SkipRange.Kind.Credits in want) {
            val windowMs = { rt: Long -> minOf(rt / 5, CREDITS_WINDOW_MS) }
            val aFrom = runtime - windowMs(runtime); val bFrom = otherRuntime - windowMs(otherRuntime)
            val a = prints(item.id, aFrom, (windowMs(runtime) / 1000).toInt())
            val b = a?.let { prints(other.id, bFrom, (windowMs(otherRuntime) / 1000).toInt()) }
            val m = if (a != null && b != null) AudioFingerprint.longestShared(a, b, minFrames) else null
            if (m != null) {
                // Credits run to the end even where the music stops a little early.
                fun end(from: Long, frame: Int, rt: Long) = (from + frame * AudioFingerprint.FRAME_MS).let { if (rt - it < CREDITS_TAIL_MS) rt else it }
                mine += SkipRange(SkipRange.Kind.Credits, aFrom + m.aStart * AudioFingerprint.FRAME_MS, end(aFrom, m.aEnd, runtime))
                theirs += SkipRange(SkipRange.Kind.Credits, bFrom + m.bStart * AudioFingerprint.FRAME_MS, end(bFrom, m.bEnd, otherRuntime))
            }
        }
        if (mine.isEmpty()) {
            Log.i(tag, "Audio: nothing shared with ${other.name ?: other.id}")
            return null
        }
        return Result(mine, "matching audio", mapOf(other.id to theirs))
    }

    // ---- 2. AniSkip --------------------------------------------------------------------------

    private suspend fun fromAniSkip(item: BaseItemDto, runtime: Long): Result? {
        val episode = item.indexNumber ?: return null
        val seriesId = item.seriesId ?: return null
        val series = runCatching { api.item(seriesId) }.getOrNull() ?: return null
        val season = item.seasonId?.let { id -> runCatching { api.item(id) }.getOrNull() }
        if (!looksLikeAnime(series, item)) return null
        val seasonNumber = item.parentIndexNumber ?: 1
        if (seasonNumber < 1) return null // specials have no reliable mapping
        val key = "$seriesId:$seasonNumber:$episode"
        val entry = if (malCache.containsKey(key)) malCache[key] else resolveMal(series, season, seasonNumber, episode).also { malCache[key] = it }
        entry ?: return null
        val response = http.get("https://api.aniskip.com/v2/skip-times/${entry.malId}/${entry.episode}") {
            listOf("op", "ed", "mixed-op", "mixed-ed", "recap").forEach { parameter("types", it) }
            parameter("episodeLength", (runtime / 1000.0).toString())
        }
        if (!response.status.isSuccess()) return null
        val body = AuroraJson.decodeFromString(AniSkipResponse.serializer(), response.bodyAsText())
        val ranges = aniSkipRanges(body, runtime)
        return ranges.takeIf { it.isNotEmpty() }?.let { Result(it, "AniSkip") }
    }

    private data class MalEntry(val malId: Int, val episode: Int)

    /** Maps a Jellyfin season + episode number onto a MyAnimeList entry + episode number. */
    private suspend fun resolveMal(series: BaseItemDto, season: BaseItemDto?, seasonNumber: Int, episode: Int): MalEntry? {
        // Season-level ids (AniList/MAL plugins) are exact for that season.
        season?.let { s ->
            providerId(s, "MyAnimeList", "Mal")?.let { return MalEntry(it, episode) }
            providerId(s, "AniList")?.let { id -> anilist(id = id)?.let { return walk(it, 0, episode) } }
        }
        val first = providerId(series, "MyAnimeList", "Mal")?.let { mal -> anilist(malId = mal) }
            ?: providerId(series, "AniList")?.let { anilist(id = it) }
            ?: searchAniList(series)
            ?: return null
        return walk(first, seasonNumber - 1, episode)
    }

    /** Follows TV sequels: [seasonsAhead] whole seasons, then split cours by episode count. */
    private suspend fun walk(start: AniListMedia, seasonsAhead: Int, episode: Int): MalEntry? {
        var media = start
        repeat(seasonsAhead) { media = sequel(media) ?: return null }
        var remaining = episode
        while (media.episodes != null && remaining > media.episodes!!) {
            remaining -= media.episodes!!
            media = sequel(media) ?: return null
        }
        return media.idMal?.let { MalEntry(it, remaining) }
    }

    private suspend fun sequel(media: AniListMedia): AniListMedia? {
        val next = media.relations?.edges.orEmpty()
            .firstOrNull { it.relationType == "SEQUEL" && it.node.type == "ANIME" && it.node.format in TV_FORMATS }
            ?: return null
        return anilist(id = next.node.id)
    }

    private suspend fun searchAniList(series: BaseItemDto): AniListMedia? {
        val title = series.name ?: return null
        val media = anilist(search = title) ?: return null
        return media.takeIf { matchesSeries(it, series) }
    }

    private suspend fun anilist(id: Int? = null, malId: Int? = null, search: String? = null): AniListMedia? {
        val key = "id=$id mal=$malId q=$search"
        if (anilistCache.containsKey(key)) return anilistCache[key]
        val body = buildJsonObject {
            put("query", ANILIST_QUERY)
            putJsonObject("variables") {
                id?.let { put("id", it) }
                malId?.let { put("idMal", it) }
                search?.let { put("search", it) }
            }
        }
        val response = http.post("https://graphql.anilist.co") {
            contentType(ContentType.Application.Json)
            setBody(AuroraJson.encodeToString(JsonObject.serializer(), body))
        }
        val media = if (response.status.isSuccess())
            runCatching { AuroraJson.decodeFromString(AniListResponse.serializer(), response.bodyAsText()).data?.media }.getOrNull()
        else null
        anilistCache[key] = media
        return media
    }

    companion object {
        /** A shared stretch shorter than this is chance (a sound effect), not a theme. */
        private const val MIN_SHARED_MS = 12_000L
        private const val MAX_INTRO_MS = 150_000L
        private const val INTRO_WINDOW_MS = 10 * 60_000L
        private const val CREDITS_WINDOW_MS = 4 * 60_000L
        private const val CREDITS_TAIL_MS = 20_000L

        private val TV_FORMATS = setOf("TV", "TV_SHORT", "ONA")
        private const val ANILIST_QUERY =
            "query(\$id:Int,\$idMal:Int,\$search:String){Media(id:\$id,idMal:\$idMal,search:\$search,type:ANIME){" +
                "id idMal format episodes startDate{year} title{romaji english native} synonyms " +
                "relations{edges{relationType node{id idMal format type episodes}}}}}"

        private fun providerId(item: BaseItemDto, vararg names: String): Int? =
            item.providerIds.entries.firstOrNull { (k, _) -> names.any { it.equals(k, ignoreCase = true) } }?.value?.trim()?.toIntOrNull()

        fun looksLikeAnime(series: BaseItemDto, episode: BaseItemDto): Boolean {
            val audio = episode.mediaSources.flatMap { it.mediaStreams }.filter { it.isAudio }.map { it.language }
            return ContentKind.of(series.copy(genres = series.genres + episode.genres), audio) == ContentKind.Anime
        }

        private fun normalizeTitle(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** Guards against AniList's fuzzy search picking a different show. */
        internal fun matchesSeries(media: AniListMedia, series: BaseItemDto): Boolean {
            val wanted = listOfNotNull(series.name, series.originalTitle).map(::normalizeTitle).filter { it.isNotEmpty() }
            val titles = (listOfNotNull(media.title?.romaji, media.title?.english, media.title?.native) + media.synonyms).map(::normalizeTitle)
            if (wanted.any { w -> titles.any { it == w } }) return true
            val year = series.productionYear
            return year != null && media.startDate?.year == year && wanted.any { w -> titles.any { it.startsWith(w) || w.startsWith(it) } }
        }

        /**
         * Openings/endings as chapters whose length recurs (±1.5 s) in other episodes of the season:
         * opening within the first 40%, ending within the last 30%. Periodic chapter layouts (many
         * equal-length chapters) are ignored, because equal lengths mean nothing there.
         */
        fun recurringChapterRanges(chapters: List<ChapterInfo>, runtimeMs: Long, others: List<Pair<List<ChapterInfo>, Long>>): List<SkipRange> {
            fun spans(ch: List<ChapterInfo>, runtime: Long) = ch.mapIndexed { i, c ->
                val start = c.startPositionTicks.ticksToMs()
                val end = ch.getOrNull(i + 1)?.startPositionTicks?.ticksToMs() ?: runtime
                start to end
            }.filter { (s, e) -> e > s }
            val mine = spans(chapters, runtimeMs)
            val lengths = mine.map { (s, e) -> e - s }
            if (lengths.count { l -> lengths.count { abs(it - l) <= 1_500 } >= 3 } >= 3) return emptyList()
            val otherSpans = others.map { (ch, rt) -> spans(ch, rt) to rt }

            fun pick(kind: SkipRange.Kind, inZone: (Long, Long, Long) -> Boolean): SkipRange? = mine
                .filter { (s, e) -> e - s in 20_000..180_000 && inZone(s, e, runtimeMs) }
                .map { (s, e) ->
                    val matches = otherSpans.count { (sp, rt) -> sp.any { (os, oe) -> abs((oe - os) - (e - s)) <= 1_500 && inZone(os, oe, rt) } }
                    Triple(s, e, matches)
                }
                .filter { it.third >= 1 }
                .maxByOrNull { it.third }
                ?.let { (s, e, _) -> SkipRange(kind, s, e) }

            return listOfNotNull(
                pick(SkipRange.Kind.Intro) { s, _, rt -> s < rt * 0.4 },
                pick(SkipRange.Kind.Credits) { s, _, rt -> s > rt * 0.7 },
            )
        }

        /** AniSkip results as ranges, keeping only ones that sit where an OP/ED/recap can be. */
        internal fun aniSkipRanges(body: AniSkipResponse, runtimeMs: Long): List<SkipRange> {
            if (!body.found) return emptyList()
            val byKind = body.results.mapNotNull { r ->
                val kind = when (r.skipType) {
                    "op", "mixed-op" -> SkipRange.Kind.Intro
                    "ed", "mixed-ed" -> SkipRange.Kind.Credits
                    "recap" -> SkipRange.Kind.Recap
                    else -> return@mapNotNull null
                }
                val start = (r.interval.startTime * 1000).toLong()
                val end = (r.interval.endTime * 1000).toLong().coerceAtMost(runtimeMs)
                val sane = end - start in 10_000..240_000 && when (kind) {
                    SkipRange.Kind.Intro, SkipRange.Kind.Recap -> start < runtimeMs * 0.5
                    SkipRange.Kind.Credits -> start > runtimeMs * 0.6
                }
                if (sane) SkipRange(kind, start, end) else null
            }
            // Prefer plain op/ed over the "mixed" variants when both exist (first per kind wins).
            return byKind.groupBy { it.kind }.map { (_, list) -> list.first() }
        }
    }
}

@Serializable
internal data class AniSkipResponse(val found: Boolean = false, val results: List<AniSkipResult> = emptyList())

@Serializable
internal data class AniSkipResult(val interval: AniSkipInterval, val skipType: String, val episodeLength: Double = 0.0)

@Serializable
internal data class AniSkipInterval(val startTime: Double, val endTime: Double)

@Serializable
internal data class AniListResponse(val data: AniListData? = null)

@Serializable
internal data class AniListData(@kotlinx.serialization.SerialName("Media") val media: AniListMedia? = null)

@Serializable
internal data class AniListMedia(
    val id: Int,
    val idMal: Int? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val startDate: AniListDate? = null,
    val title: AniListTitle? = null,
    val synonyms: List<String> = emptyList(),
    val relations: AniListRelations? = null,
)

@Serializable
internal data class AniListDate(val year: Int? = null)

@Serializable
internal data class AniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)

@Serializable
internal data class AniListRelations(val edges: List<AniListEdge> = emptyList())

@Serializable
internal data class AniListEdge(val relationType: String? = null, val node: AniListNode)

@Serializable
internal data class AniListNode(val id: Int, val idMal: Int? = null, val format: String? = null, val type: String? = null, val episodes: Int? = null)
