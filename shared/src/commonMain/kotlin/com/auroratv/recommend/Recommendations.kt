package com.auroratv.recommend

import com.auroratv.core.Log
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.Fields
import com.auroratv.playback.ContentKind
import com.auroratv.core.AuroraJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

/** A title this profile watched recently (an episode counts for its series), and how much it says about taste. */
data class WatchedTitle(val id: String, val name: String, val isSeries: Boolean, val weight: Double, val disliked: Boolean = false)

/**
 * Lightweight content-based recommendations: no model, no extra catalogue. Taste comes from what the
 * profile watched lately (newer counts more; favourites, thumbs-up and their own ratings count more,
 * thumbs-down counts against; a series watched for many episodes counts more), matched against the
 * genres the library already has for every title.
 */
object RecommendationEngine {
    private const val RECENCY = 0.85

    /** Distinct titles, most recently watched first, with their weight. */
    fun watchedTitles(history: List<BaseItemDto>): List<WatchedTitle> {
        val ordered = history.sortedByDescending { it.userData?.lastPlayedDate.orEmpty() }
        val byTitle = LinkedHashMap<String, MutableList<BaseItemDto>>()
        for (item in ordered) {
            val id = (if (item.isEpisode) item.seriesId else item.id) ?: continue
            byTitle.getOrPut(id) { mutableListOf() }.add(item)
        }
        return byTitle.entries.mapIndexed { rank, (id, items) ->
            val first = items.first()
            val ud = items.mapNotNull { it.userData }
            val disliked = ud.any { it.likes == false }
            var w = RECENCY.pow(rank)
            if (ud.any { it.isFavorite }) w *= 1.5
            if (ud.any { it.likes == true }) w *= 1.5
            if (disliked) w *= 0.15
            ud.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.let { r -> w *= (r.average() / 7.0).coerceIn(0.2, 1.6) }
            // Watching many episodes (or rewatching) is a stronger signal than one sitting.
            val plays = items.size + ud.sumOf { (it.playCount - 1).coerceAtLeast(0) }
            w *= 1.0 + 0.15 * (plays - 1).coerceIn(0, 6)
            WatchedTitle(id, (if (first.isEpisode) first.seriesName else first.name) ?: "", first.isEpisode, w, disliked)
        }
    }

    /** Genre → weight (largest 1.0) from the watched titles. */
    fun genreProfile(titles: List<WatchedTitle>, genresOf: (String) -> Collection<String>): Map<String, Double> {
        val acc = HashMap<String, Double>()
        for (t in titles) {
            val g = genresOf(t.id)
            if (g.isEmpty()) continue
            val share = t.weight / sqrt(g.size.toDouble()) * (if (t.disliked) -1.0 else 1.0)
            g.forEach { acc[it] = (acc[it] ?: 0.0) + share }
        }
        val max = acc.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyMap()
        return acc.filterValues { it > 0 }.mapValues { it.value / max }
    }

    /** Candidates that fit [profile] best (a small nudge from community rating breaks ties). */
    fun rankForProfile(candidates: List<BaseItemDto>, profile: Map<String, Double>, limit: Int): List<BaseItemDto> {
        if (profile.isEmpty()) return emptyList()
        return candidates.asSequence()
            .map { c ->
                val match = if (c.genres.isEmpty()) 0.0 else c.genres.sumOf { profile[it] ?: 0.0 } / sqrt(c.genres.size.toDouble())
                c to match
            }
            .filter { it.second > 0.05 }
            .sortedByDescending { (c, m) -> m + 0.02 * (c.communityRating ?: 0.0) }
            .take(limit).map { it.first }.toList()
    }

    /** Titles sharing genres with a seed (Jaccard overlap), best first; at least a third must overlap. */
    fun byGenreOverlap(seedGenres: Collection<String>, candidates: List<BaseItemDto>, limit: Int): List<BaseItemDto> {
        if (seedGenres.isEmpty()) return emptyList()
        val seed = seedGenres.toSet()
        return candidates.asSequence()
            .map { c ->
                val g = c.genres.toSet()
                val inter = g.count { it in seed }
                val jaccard = if (inter == 0) 0.0 else inter.toDouble() / (g.size + seed.size - inter)
                c to jaccard
            }
            .filter { it.second >= 1.0 / 3 }
            .sortedByDescending { (c, j) -> j + 0.02 * (c.communityRating ?: 0.0) }
            .take(limit).map { it.first }.toList()
    }

    /** A profile's taste: content-genre weights (largest 1.0), and how its viewing splits across kinds and audiences. */
    class Taste(val genres: Map<String, Double>, val kinds: Map<ContentKind, Double>, val audiences: Map<Similarity.Audience, Double>)

    fun taste(titles: List<WatchedTitle>, details: Map<String, BaseItemDto>, genresOf: (String) -> Collection<String>): Taste {
        val genres = genreProfile(titles) { id -> genresOf(id).filter { it.lowercase() !in setOf("animation", "anime") } }
        val kinds = HashMap<ContentKind, Double>(); val audiences = HashMap<Similarity.Audience, Double>()
        var total = 0.0
        titles.filter { !it.disliked }.forEach { t ->
            val d = details[t.id] ?: return@forEach
            kinds[ContentKind.of(d)] = (kinds[ContentKind.of(d)] ?: 0.0) + t.weight
            audiences[Similarity.audience(d)] = (audiences[Similarity.audience(d)] ?: 0.0) + t.weight
            total += t.weight
        }
        return Taste(genres, kinds.mapValues { it.value / total.coerceAtLeast(1e-9) }, audiences.mapValues { it.value / total.coerceAtLeast(1e-9) })
    }

    /**
     * "Recommended For You": genre match with the profile, scaled by how much of their viewing is
     * that kind of content (a profile that watches anime gets anime; one that never does gets little)
     * and whether they watch children's titles at all.
     */
    fun rankForTaste(candidates: List<BaseItemDto>, taste: Taste, limit: Int): List<BaseItemDto> {
        if (taste.genres.isEmpty()) return emptyList()
        val knownKinds = taste.kinds.isNotEmpty()
        return candidates.asSequence().distinctBy { it.id }
            .map { c ->
                val g = Similarity.contentGenres(c)
                val match = if (g.isEmpty()) 0.0 else g.sumOf { taste.genres[it] ?: 0.0 } / sqrt(g.size.toDouble())
                val kind = if (knownKinds) 0.2 + 0.8 * (taste.kinds[ContentKind.of(c)] ?: 0.0) else 1.0
                val kids = Similarity.audience(c) == Similarity.Audience.Kids && (taste.audiences[Similarity.Audience.Kids] ?: 0.0) < 0.1
                c to (if (match < 0.2) 0.0 else match * kind * (if (kids) 0.5 else 1.0) + 0.01 * (c.communityRating ?: 0.0))
            }
            .filter { it.second >= 0.15 }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }.toList()
    }

    /** Two from [primary] for every one from [secondary], keeping each list's order, without repeats. */
    fun interleave(primary: List<BaseItemDto>, secondary: List<BaseItemDto>): List<BaseItemDto> {
        val out = LinkedHashMap<String, BaseItemDto>()
        var p = 0; var s = 0
        while (p < primary.size || s < secondary.size) {
            repeat(2) { if (p < primary.size) primary[p++].let { out.putIfAbsent(it.id, it) } }
            if (s < secondary.size) secondary[s++].let { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }
}

/**
 * Builds Home's "Because You Watched …" and "Recommended For You" rows for one profile. Everything
 * comes from this profile's own history (its Jellyfin user's, or a device-only profile's own, which
 * the API client substitutes; see LocalWatchData), so profiles never mix. Kept light on the server: no catalogue download (only small, filtered lists of unwatched
 * titles in the right genres), and the finished rows are saved per profile and reused for as long
 * as the watch history hasn't changed, so most launches cost one small history request.
 */
class RecommendationService(
    private val scope: CoroutineScope,
    /** Where finished rows are kept between launches (one small file per profile); null: memory only. */
    private val files: com.auroratv.platform.FileStore? = null,
) {
    class Plan(
        val titles: List<WatchedTitle>,
        val watchedIds: Set<String>,
        val signature: String,
        /** Genres of the watched titles (movies from the history itself, series in one request). */
        val genres: Map<String, List<String>>,
        /** The most recent watched titles with what similarity compares (tags, description, origin…). */
        val details: Map<String, BaseItemDto> = emptyMap(),
    ) {
        /** The titles the "Because You Watched" rows are about (newest first, never a thumbs-down). */
        val seeds: List<WatchedTitle> get() = titles.filter { !it.disliked && it.name.isNotBlank() }.take(SEEDS)
    }

    @kotlinx.serialization.Serializable
    data class Row(val key: String, val title: String, val items: List<BaseItemDto>)

    @kotlinx.serialization.Serializable
    private data class Saved(val signature: String, val rows: List<Row>)

    private val cache = HashMap<String, Pair<String, Deferred<List<Row>?>>>()

    /** A profile's plan as last loaded: which watch activity it reflects and when it was loaded. */
    private class CachedPlan(val plan: Plan, val watchVersion: Int, val loadedAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark)
    private val plans = HashMap<String, CachedPlan>()
    /** Plan loads under way, per profile, so Home opened twice (or refreshed) asks the server once. */
    private val planLoads = HashMap<String, Pair<Int, Deferred<Plan>>>()

    /** [plan] is the last one loaded for the profile; [fresh]: nothing was watched since and it's recent. */
    class Peek(val plan: Plan, val fresh: Boolean)

    /**
     * The profile's last plan from memory (no request), if there is one. Fresh while no title has
     * been watched, marked or favourited on this device since ([watchVersion]) and for
     * [PLAN_FRESH_MS] (to notice what was watched on other devices); otherwise it's still shown while
     * a new one loads (stale-while-revalidate).
     */
    fun peekPlan(profileKey: String, watchVersion: Int): Peek? = plans[profileKey]?.let { c ->
        Peek(c.plan, c.watchVersion == watchVersion && c.loadedAt.elapsedNow().inWholeMilliseconds < PLAN_FRESH_MS)
    }

    /**
     * The profile's plan: from memory while fresh, else loaded once however many callers ask at the
     * same time. Kept in memory for the next time Home opens.
     */
    suspend fun planFor(api: JellyfinApi, profileKey: String, watchVersion: Int, resume: List<BaseItemDto>): Plan {
        peekPlan(profileKey, watchVersion)?.takeIf { it.fresh }?.let { return it.plan }
        planLoads[profileKey]?.let { (v, d) -> if (v == watchVersion && !d.isCompleted) return d.await() }
        val d = scope.async {
            plan(api, resume).also { p ->
                plans[profileKey] = CachedPlan(p, watchVersion, kotlin.time.TimeSource.Monotonic.markNow())
                Log.i("Recommend", "History loaded for this profile (${p.titles.size} title(s)); kept in memory")
            }
        }
        planLoads[profileKey] = watchVersion to d
        return try { d.await() } finally { if (planLoads[profileKey]?.second === d && d.isCompleted) planLoads.remove(profileKey) }
    }

    /** One small request (plus one for series genres): recently finished titles, and what's in progress ([resume]). Not cached; see [planFor]. */
    suspend fun plan(api: JellyfinApi, resume: List<BaseItemDto>): Plan {
        val played = api.items(
            ItemQuery(
                includeItemTypes = listOf("Movie", "Episode"), recursive = true, sortBy = "DatePlayed", sortDescending = true,
                filters = listOf("IsPlayed"), limit = HISTORY, fields = "Genres", totalCount = false, imageTypes = "Primary",
            )
        ).items
        val history = (resume + played).distinctBy { it.id }
        val titles = RecommendationEngine.watchedTitles(history)
        val watched = history.flatMap { listOfNotNull(it.id, it.seriesId) }.toSet()
        // ALGORITHM changes the signature, so rows saved by an older ranking are worked out again once.
        val signature = titles.take(12).joinToString(",") { "${it.id}:${(it.weight * 100).toInt()}" } + "|" + watched.size + "|" + ALGORITHM
        val genres = HashMap<String, List<String>>()
        history.filter { !it.isEpisode && it.genres.isNotEmpty() }.forEach { genres[it.id] = it.genres }
        // The newest titles (series and films) with everything similarity compares: one small request.
        val lookup = titles.take(DETAIL_LOOKUPS).map { it.id }
        val details = if (lookup.isEmpty()) emptyMap() else runCatching {
            api.items(ItemQuery(ids = lookup, limit = lookup.size, recursive = true, fields = Fields.SIMILARITY, totalCount = false, userData = false, imageTypes = "Primary")).items
        }.getOrNull().orEmpty().associateBy { it.id }
        details.values.forEach { if (it.genres.isNotEmpty()) genres[it.id] = it.genres }
        return Plan(titles, watched, signature, genres, details)
    }

    /**
     * The rows for [plan], shared by every rail that asks; reused while the history is unchanged
     * (from memory, or from this profile's saved file after a restart). Null means it failed
     * (offline); the next call tries again.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rows(api: JellyfinApi, profileKey: String, plan: Plan): Deferred<List<Row>?> {
        cache[profileKey]?.let { (sig, d) ->
            val failed = d.isCompleted && (d.isCancelled || d.getCompleted() == null)
            if (sig == plan.signature && !failed) return d
        }
        val d = scope.async {
            val file = com.auroratv.profiles.ProfileFiles.name("recommendations", profileKey)
            val saved = runCatching { files?.read(file)?.let { AuroraJson.decodeFromString(Saved.serializer(), it) } }.getOrNull()
            if (saved != null && saved.signature == plan.signature) {
                Log.i("Recommend", "History unchanged: ${saved.rows.size} saved row(s), no requests")
                return@async saved.rows
            }
            try {
                val t0 = kotlin.time.TimeSource.Monotonic.markNow()
                // Kept (in memory and on disk) as the ranked titles with what a card shows, not the
                // tags, cast and descriptions the ranking compared.
                compute(api, plan).map { r -> r.copy(items = r.items.map(::slim)) }.also { rows ->
                    Log.i("Recommend", "${rows.size} row(s) in ${t0.elapsedNow().inWholeMilliseconds} ms: " + rows.joinToString { "${it.title} (${it.items.size})" })
                    runCatching { files?.write(file, AuroraJson.encodeToString(Saved.serializer(), Saved(plan.signature, rows))) }
                }
            } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                Log.w("Recommend", "Couldn't work out recommendations: ${t.message}"); null
            }
        }
        cache[profileKey] = plan.signature to d
        return d
    }

    /** What a card needs, without long text (keeps the saved file small). */
    private fun slim(i: BaseItemDto) = i.copy(overview = i.overview?.take(240), taglines = emptyList(), people = emptyList(),
        mediaSources = emptyList(), mediaStreams = emptyList(), chapters = emptyList(), studios = emptyList(), providerIds = emptyMap(),
        tags = emptyList(), productionLocations = emptyList())

    /** Unwatched titles of [types] in any of [genres], best rated first: one small, filtered request. */
    private suspend fun unwatched(api: JellyfinApi, types: List<String>, genres: Collection<String>, limit: Int): List<BaseItemDto> =
        if (genres.isEmpty()) emptyList() else runCatching {
            api.items(ItemQuery(includeItemTypes = types, genres = genres.toList(), filters = listOf("IsUnplayed"), recursive = true,
                sortBy = "CommunityRating,SortName", sortDescending = true, limit = limit, totalCount = false, fields = Fields.SIMILARITY)).items
        }.getOrDefault(emptyList())

    /**
     * Where to look for titles like [seed]: anime among anime/animation, other animation among
     * animation, live action among its own (content) genres. One request; the ranking decides.
     */
    private fun poolGenres(seed: BaseItemDto): List<String> = when (ContentKind.of(seed)) {
        ContentKind.Anime -> listOf("Anime", "Animation")
        ContentKind.Animation -> listOf("Animation")
        ContentKind.LiveAction -> Similarity.contentGenres(seed)
    }

    private suspend fun compute(api: JellyfinApi, plan: Plan): List<Row> {
        val genresOf: (String) -> Collection<String> = { id -> plan.genres[id].orEmpty() }
        val used = HashSet(plan.watchedIds)
        val rows = mutableListOf<Row>()
        plan.seeds.forEachIndexed { i, seed ->
            val seedItem = plan.details[seed.id] ?: return@forEachIndexed // nothing to compare with: no row
            // Candidates: the server's own Similar list (it also weighs cast, crew and studios), plus
            // unwatched titles of the same kind of content. Both are ranked by Similarity; anything
            // without a clear reason to appear is dropped, however popular.
            val server = runCatching { api.similar(seed.id, limit = 24, fields = Fields.SIMILARITY) }.getOrDefault(emptyList())
                .filter { (it.isMovie || it.isSeries) && it.userData?.played != true && it.id !in used }
            val pool = unwatched(api, listOf("Movie", "Series"), poolGenres(seedItem), POOL).filter { it.id !in used }
            val items = withContext(Dispatchers.Default) {
                Similarity.rank(seedItem, server + pool, server.map { it.id }.toSet(), PER_ROW)
            }
            if (items.size >= MIN_ROW) {
                rows += Row("because-$i", "Because You Watched ${seed.name}", items)
                items.forEach { used += it.id } // a title shows up in one row only
            } else {
                Log.i("Recommend", "Because You Watched ${seed.name}: ${items.size} close match(es) in the library, row left out")
            }
        }
        if (plan.titles.size >= 2) {
            val taste = RecommendationEngine.taste(plan.titles, plan.details, genresOf)
            val top = taste.genres.entries.sortedByDescending { it.value }.take(4).map { it.key } +
                (if ((taste.kinds[ContentKind.Anime] ?: 0.0) >= 0.3) listOf("Anime", "Animation") else emptyList())
            val candidates = unwatched(api, listOf("Movie", "Series"), top.distinct(), 80).filter { it.id !in used }
            val items = withContext(Dispatchers.Default) { RecommendationEngine.rankForTaste(candidates, taste, PER_ROW) }
            if (items.size >= MIN_ROW) rows += Row("foryou", "Recommended For You", items)
        }
        return rows
    }

    fun forget(profileKey: String) { cache.remove(profileKey); plans.remove(profileKey); planLoads.remove(profileKey) }

    private companion object {
        const val SEEDS = 2
        const val HISTORY = 40
        const val PER_ROW = 20
        const val POOL = 60
        const val MIN_ROW = 3
        const val DETAIL_LOOKUPS = 8
        /** Bumped when the ranking changes, so saved rows are recomputed once. */
        const val ALGORITHM = "v2"
        /** How long a profile's history is trusted without asking the server again (when nothing was watched here). */
        const val PLAN_FRESH_MS = 30 * 60_000L
    }
}
