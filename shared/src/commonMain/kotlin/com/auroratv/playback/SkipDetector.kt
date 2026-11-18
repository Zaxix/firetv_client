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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * 4. **Credits on screen** (credits only, any show): the server's trickplay thumbnails (when it has
 *    made them) of the episode's end show text on black for a stretch, and the neighbouring
 *    episode's end does the same for a similar length (see CreditsVision). For live-action shows
 *    whose closing credits change music every episode or have none.
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

    /** True when the audio and screen checks are on, i.e. a miss means nothing could be found. */
    val searchesEveryShow: Boolean get() = audioAllowed()

    /** [others]: ranges worked out on the way for other episodes (the one compared against). */
    data class Result(val ranges: List<SkipRange>, val source: String, val others: Map<String, List<SkipRange>> = emptyMap())

    /** Detection under way per episode: a second request for the same one waits for it instead of repeating it. */
    private val running = HashMap<String, CompletableDeferred<Result?>>()
    /** One episode's detection at a time on this server (a few small audio requests each, at most two at once). */
    private val jobs = Semaphore(1)
    /** A season's episode list (chapters, trickplay info), kept a few minutes: the next episode reuses it. */
    private val seasons = LinkedHashMap<String, Pair<kotlin.time.TimeSource.Monotonic.ValueTimeMark, List<BaseItemDto>>>()
    /** Audio windows already fetched (this episode's end is the next one's partner): no second request. */
    private val windows = LinkedHashMap<String, AudioWindow>()

    /** Low-rate audio of one stretch of an episode, and its fingerprint. */
    private class AudioWindow(val startMs: Long, val pcm: ByteArray, val prints: IntArray)

    /** Per-step timings of the last detection (for the Perf log and the integration test). */
    var lastTimings: String = ""
        private set

    suspend fun detect(item: BaseItemDto, alreadyHave: Set<SkipRange.Kind>): Result? {
        running[item.id]?.let { return it.await() }
        val done = CompletableDeferred<Result?>()
        running[item.id] = done
        try {
            val result = jobs.withPermit { detectNow(item, alreadyHave) }
            done.complete(result)
            return result
        } catch (t: Throwable) {
            done.completeExceptionally(t)
            throw t
        } finally {
            running.remove(item.id)
        }
    }

    private suspend fun seasonEpisodes(item: BaseItemDto): List<BaseItemDto> {
        val sid = item.seriesId ?: return emptyList()
        val key = "$sid/${item.seasonId}"
        seasons[key]?.takeIf { it.first.elapsedNow().inWholeMilliseconds < SEASON_FRESH_MS }?.let { return it.second }
        val list = runCatching { api.episodesWithChapters(sid, item.seasonId) }.getOrNull().orEmpty().filter { it.runtimeMs != null }
        if (list.isNotEmpty()) {
            seasons.remove(key); seasons[key] = kotlin.time.TimeSource.Monotonic.markNow() to list
            while (seasons.size > 4) seasons.remove(seasons.keys.first())
        }
        return list
    }

    private suspend fun detectNow(item: BaseItemDto, alreadyHave: Set<SkipRange.Kind>): Result? {
        if (!item.isEpisode) return null
        val runtime = item.runtimeMs ?: return null
        val found = mutableListOf<Result>()
        val clock = kotlin.time.TimeSource.Monotonic.markNow()
        val timings = mutableListOf<String>()
        fun step(name: String, from: kotlin.time.TimeSource.Monotonic.ValueTimeMark) { timings += "$name ${from.elapsedNow().inWholeMilliseconds} ms" }
        // The season's episodes (with chapters and trickplay info): fetched once, only if needed.
        var seasonList: List<BaseItemDto>? = null
        suspend fun season(): List<BaseItemDto> = seasonList ?: seasonEpisodes(item).also { seasonList = it }
        var t = kotlin.time.TimeSource.Monotonic.markNow()
        runCatching { fromSeasonChapters(item, runtime) { season() } }
            .onFailure { Log.w(tag, "Chapter pattern lookup failed: ${it.message}") }
            .getOrNull()?.let { found += it }
        step("chapters", t)
        // Only a confident find counts as found: a weak one is kept but the next source still looks.
        fun hasFirm(kind: SkipRange.Kind) = found.any { r -> r.ranges.any { it.kind == kind && it.confident } }
        val covered = alreadyHave + SkipRange.Kind.entries.filter(::hasFirm)
        if (onlineAllowed() && !covered.containsAll(listOf(SkipRange.Kind.Intro, SkipRange.Kind.Credits))) {
            t = kotlin.time.TimeSource.Monotonic.markNow()
            // Real-time timeout (Dispatchers.Default), so a slow third-party API never holds anything up.
            runCatching { withContext(Dispatchers.Default) { withTimeoutOrNull(8_000) { fromAniSkip(item, runtime) } } }
                .onFailure { Log.w(tag, "AniSkip lookup failed: ${it.message}") }
                .getOrNull()?.let { found += it }
            step("AniSkip", t)
        }
        val stillMissing = listOf(SkipRange.Kind.Intro, SkipRange.Kind.Credits).filter { it !in alreadyHave && !hasFirm(it) }
        if (audioAllowed() && stillMissing.isNotEmpty()) {
            t = kotlin.time.TimeSource.Monotonic.markNow()
            runCatching { withContext(Dispatchers.Default) { withTimeoutOrNull(90_000) { fromAudio(item, runtime, stillMissing.toSet(), season()) } } }
                .onFailure { Log.w(tag, "Audio match failed: ${it.message}") }
                .getOrNull()?.let { found += it }
            step("listening", t)
        }
        val creditsMissing = SkipRange.Kind.Credits !in alreadyHave && !hasFirm(SkipRange.Kind.Credits)
        if (audioAllowed() && creditsMissing && item.trickplay != null) {
            t = kotlin.time.TimeSource.Monotonic.markNow()
            runCatching { withContext(Dispatchers.Default) { withTimeoutOrNull(60_000) { fromScreen(item, runtime, season()) } } }
                .onFailure { Log.w(tag, "On-screen credits check failed: ${it.message}") }
                .getOrNull()?.let { found += it }
            step("screen", t)
        }
        lastTimings = (timings + "total ${clock.elapsedNow().inWholeMilliseconds} ms").joinToString(", ")
        Log.i(tag, "Timings: $lastTimings")
        if (found.isEmpty()) return null
        // Per kind, take the best source (a confident one before a weak one, else in the order
        // above); never override what the server already provided.
        val ranges = SkipRange.Kind.entries.filter { it !in alreadyHave }.flatMap { kind ->
            (found.firstNotNullOfOrNull { r -> r.ranges.filter { it.kind == kind && it.confident }.takeIf { it.isNotEmpty() } }
                ?: found.firstNotNullOfOrNull { r -> r.ranges.filter { it.kind == kind }.takeIf { it.isNotEmpty() } }).orEmpty()
        }
        if (ranges.isEmpty()) return null
        val sources = found.filter { r -> r.ranges.any { it in ranges } }.joinToString(" + ") { it.source }
        Log.i(tag, "Detected ${ranges.joinToString { "${it.kind} ${it.startMs / 1000.0}-${it.endMs / 1000.0}s${if (it.confident) "" else " (weak)"}" }} via $sources")
        // Other episodes' ranges from every step (e.g. the neighbour's intro by audio, its outro on screen).
        val others = HashMap<String, List<SkipRange>>()
        found.forEach { r -> r.others.forEach { (id, list) -> others[id] = (others[id].orEmpty() + list).distinctBy { it.kind } } }
        return Result(ranges, sources, others)
    }

    // ---- 1. Recurring chapters ---------------------------------------------------------------

    private suspend fun fromSeasonChapters(item: BaseItemDto, runtime: Long, season: suspend () -> List<BaseItemDto>): Result? {
        if (item.chapters.size < 3 || item.seriesId == null) return null
        val others = season()
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
    private suspend fun fromAudio(item: BaseItemDto, runtime: Long, want: Set<SkipRange.Kind>, season: List<BaseItemDto>): Result? = coroutineScope {
        val partners = partners(item, season)
        val other = partners.firstOrNull() ?: return@coroutineScope null
        val otherRuntime = other.runtimeMs ?: return@coroutineScope null
        val mine = mutableListOf<SkipRange>()
        val others = HashMap<String, MutableList<SkipRange>>()
        val minFrames = (MIN_SHARED_MS / AudioFingerprint.FRAME_MS).toInt()
        val introSecs = { rt: Long -> (minOf(rt / 4, INTRO_WINDOW_MS) / 1000).toInt() }
        val creditsWindow = { rt: Long -> minOf(rt / 5, CREDITS_WINDOW_MS) }

        if (SkipRange.Kind.Intro in want) {
            // Openings sit in the first quarter (at most 10 minutes), after any cold open. Both
            // episodes' audio is fetched at the same time (two requests at most, ever).
            val ad = async { window(item.id, 0, introSecs(runtime)) }
            val bd = async { window(other.id, 0, introSecs(otherRuntime)) }
            val a = ad.await(); val b = bd.await()
            val rough = if (a != null && b != null) AudioFingerprint.longestShared(a.prints, b.prints, minFrames) else null
            if (a != null && b != null && rough != null && rough.frames * AudioFingerprint.FRAME_MS <= MAX_INTRO_MS) {
                // Exact edges, or (rarely) the rough ones pulled in, which then count as a weak match.
                val exact = AudioFingerprint.refine(a.prints, b.prints, rough)
                val m = exact ?: AudioFingerprint.shrink(rough)
                val confident = exact != null && confident(a.prints, b.prints, m)
                mine += edges(SkipRange.Kind.Intro, a, m.aStart, m.aEnd, runtime, confident)
                others.getOrPut(other.id) { mutableListOf() } += edges(SkipRange.Kind.Intro, b, m.bStart, m.bEnd, otherRuntime, confident)
            }
        }
        if (SkipRange.Kind.Credits in want) {
            // This episode's end and the first neighbour's together; a second neighbour only if
            // they share nothing (credits music changes more often than openings: a finale, a song per episode).
            val ad = async { endWindow(item.id, runtime, creditsWindow(runtime)) }
            val first = partners.first().let { p -> p.runtimeMs?.let { prt -> async { endWindow(p.id, prt, creditsWindow(prt)) } } }
            val a = ad.await()
            for ((n, partner) in partners.withIndex()) {
                if (a == null) break
                val prt = partner.runtimeMs ?: continue
                val b = (if (n == 0) first?.await() else endWindow(partner.id, prt, creditsWindow(prt))) ?: continue
                val rough = AudioFingerprint.longestShared(a.prints, b.prints, minFrames) ?: continue
                val exact = AudioFingerprint.refine(a.prints, b.prints, rough)
                val m = exact ?: AudioFingerprint.shrink(rough)
                val confident = exact != null && confident(a.prints, b.prints, m)
                mine += edges(SkipRange.Kind.Credits, a, m.aStart, m.aEnd, runtime, confident)
                others.getOrPut(partner.id) { mutableListOf() } += edges(SkipRange.Kind.Credits, b, m.bStart, m.bEnd, prt, confident)
                break
            }
        }
        if (mine.isEmpty()) {
            Log.i(tag, "Audio: nothing shared with ${partners.joinToString { it.name ?: it.id }}")
            return@coroutineScope null
        }
        Result(mine, "matching audio", others)
    }

    /** A strong match: long enough to be a theme, and most of it agreeing. */
    private fun confident(a: IntArray, b: IntArray, m: AudioFingerprint.Match): Boolean =
        m.frames * AudioFingerprint.FRAME_MS >= CONFIDENT_MS && AudioFingerprint.agreement(a, b, m) >= CONFIDENT_AGREEMENT

    /**
     * A matched stretch (frames [from], [to) of [w], already trimmed to exact edges) as a range,
     * then moved into a silent gap at the edge if there is one (where the music meets the story). Credits
     * run to the end of the episode only when nothing but silence follows them; anything else after
     * them (a post-credits scene, a preview) is left to play.
     */
    private fun edges(kind: SkipRange.Kind, w: AudioWindow, from: Int, to: Int, runtime: Long, confident: Boolean): SkipRange {
        val f = AudioFingerprint.FRAME_MS
        var start = from * f
        var end = to * f
        // Start: a gap just before or a little into the match; end: a gap at or a little before its end.
        AudioFingerprint.silenceBetween(w.pcm, start - EDGE_BEFORE_MS, start + EDGE_INSIDE_MS)?.let { start = it }
        AudioFingerprint.silenceBetween(w.pcm, end - EDGE_INSIDE_MS, end + EDGE_BEFORE_MS)?.let { end = it }
        var absEnd = w.startMs + end
        if (kind == SkipRange.Kind.Credits) {
            val tailFrom = (end * AudioFingerprint.SAMPLE_RATE / 1000).toInt()
            val tailMs = runtime - absEnd
            val quietTail = tailMs <= QUIET_TAIL_GRACE_MS || quietShare(w.pcm, tailFrom) >= 0.9
            if (quietTail) absEnd = runtime
        }
        return SkipRange(kind, w.startMs + start, absEnd.coerceAtMost(runtime), confident)
    }

    /** Share of the 1-second blocks of [pcm] from sample [from] on that are silent. */
    private fun quietShare(pcm: ByteArray, from: Int): Double {
        val block = AudioFingerprint.SAMPLE_RATE
        val blocks = (pcm.size - from) / block
        if (blocks <= 0) return 1.0
        val quiet = (0 until blocks).count { k -> AudioFingerprint.loudness(pcm, from + k * block, from + (k + 1) * block) < AudioFingerprint.QUIET * 2 }
        return quiet.toDouble() / blocks
    }

    /** The last [lengthMs] of an episode (to the end of the file). CI checked: the server starts it exactly where asked. */
    private suspend fun endWindow(id: String, runtime: Long, lengthMs: Long): AudioWindow? =
        window(id, runtime - lengthMs, ((lengthMs + 999) / 1000).toInt())

    /** One stretch of an episode's audio: from memory if it was fetched for an earlier comparison. */
    private suspend fun window(id: String, startMs: Long, seconds: Int): AudioWindow? {
        val key = "$id@$startMs+$seconds"
        windowLock.withLock { windows[key] }?.let { return it }
        val pcm = api.audioSample(id, startMs, seconds, AudioFingerprint.SAMPLE_RATE)?.let { AudioFingerprint.wavSamples(it) } ?: return null
        val prints = AudioFingerprint.fingerprint(pcm)
        if (prints.size * AudioFingerprint.FRAME_MS < seconds * 1000L / 3) return null // too short: the server gave up
        val w = AudioWindow(startMs, pcm, prints)
        // (Callers run in parallel on Dispatchers.Default: the cache is touched under its own lock.)
        synchronizedWindows { windows.remove(key); windows[key] = w; while (windows.size > MAX_WINDOWS) windows.remove(windows.keys.first()) }
        return w
    }

    private val windowLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun synchronizedWindows(block: () -> Unit) = windowLock.withLock { block() }

    /**
     * Episodes to compare with, best first: the next one (its markers come out of the same
     * comparison, so when it's played next nothing has to be fetched or analysed for it), then the
     * previous one.
     */
    private fun partners(item: BaseItemDto, season: List<BaseItemDto>): List<BaseItemDto> {
        val at = season.indexOfFirst { it.id == item.id }
        if (at < 0) return emptyList()
        return listOfNotNull(season.getOrNull(at + 1), season.getOrNull(at - 1)).filter { it.id != item.id }
    }

    // ---- 4. Looking: credits on screen (trickplay thumbnails) --------------------------------------

    /**
     * End credits from the server's trickplay thumbnails: a stretch of "text on black" at this
     * episode's end, confirmed by a stretch of similar length at a neighbouring episode's end. Only
     * static thumbnail sheets the server already made are downloaded (no video is decoded or
     * transcoded anywhere), at most two per episode.
     */
    private suspend fun fromScreen(item: BaseItemDto, runtime: Long, season: List<BaseItemDto>): Result? {
        val mine = screenRun(item, runtime) ?: run { Log.i(tag, "Screen: no credits look at the end"); return null }
        for (partner in partners(item, season)) {
            val prt = partner.runtimeMs ?: continue
            val theirs = screenRun(partner, prt) ?: continue
            if (!CreditsVision.agree(mine, theirs)) continue
            return Result(
                listOf(SkipRange(SkipRange.Kind.Credits, mine.startMs, mine.endMs)),
                "credits on screen",
                mapOf(partner.id to listOf(SkipRange(SkipRange.Kind.Credits, theirs.startMs, theirs.endMs))),
            )
        }
        Log.i(tag, "Screen: credits look at the end not confirmed by a neighbouring episode")
        return null
    }

    private suspend fun screenRun(ep: BaseItemDto, runtime: Long): CreditsVision.Run? {
        val (sourceId, info) = CreditsVision.pickTrickplay(ep) ?: return null
        val tiles = CreditsVision.tailTiles(info, runtime) ?: return null
        val perSheet = info.tileWidth * info.tileHeight
        val looks = HashMap<Int, CreditsVision.Look>()
        for (sheet in tiles.map { it / perSheet }.distinct()) {
            val bytes = runCatching { api.downloadBytes(api.images.trickplayTile(ep.id, sourceId, info.width, sheet)) }.getOrNull() ?: return null
            val sample = if (info.width >= 240) 2 else 1
            val px = decodeImagePixels(bytes, sample) ?: return null
            tiles.filter { it / perSheet == sheet }.forEach { i ->
                val k = i % perSheet
                looks[i] = CreditsVision.look(px, (k % info.tileWidth) * info.width / sample, (k / info.tileWidth) * info.height / sample, info.width / sample, info.height / sample)
            }
        }
        return CreditsVision.creditsRun(tiles.map { looks[it] ?: CreditsVision.Look.Scene }, tiles.first(), info.interval.toLong(), runtime)
    }

    // ---- 2. AniSkip --------------------------------------------------------------------------

    private suspend fun fromAniSkip(item: BaseItemDto, runtime: Long): Result? {
        val episode = item.indexNumber ?: return null
        val seriesId = item.seriesId ?: return null
        // The series and the season together (two small requests at once, not one after the other).
        val (series, season) = coroutineScope {
            val s = async { runCatching { api.item(seriesId) }.getOrNull() }
            val n = item.seasonId?.let { id -> async { runCatching { api.item(id) }.getOrNull() } }
            s.await() to n?.await()
        }
        series ?: return null
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
        /** Credits ending this close to the end of the file run to the end. */
        private const val QUIET_TAIL_GRACE_MS = 3_000L
        /** A match at least this long, with this share of its frames agreeing, is confident. */
        private const val CONFIDENT_MS = 20_000L
        private const val CONFIDENT_AGREEMENT = 0.5
        /** How far an edge may move into a silent gap: outward (away from the music) / inward. */
        private const val EDGE_BEFORE_MS = 250L
        private const val EDGE_INSIDE_MS = 900L
        private const val MAX_WINDOWS = 4
        private const val SEASON_FRESH_MS = 10 * 60_000L

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
                // One other episode with a chapter of that length could be chance; two or more can't.
                ?.let { (s, e, n) -> SkipRange(kind, s, e, confident = n >= 2 || otherSpans.size < 2) }

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
