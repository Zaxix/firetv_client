package com.auroratv.ratings

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.platform.SecureVault
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.TimeSource

/** Scores from the big rating sites (MDBList aggregates them; Jellyfin's own fields are the fallback). */
data class ExternalRatings(
    /** Rotten Tomatoes critics score (Tomatometer), 0–100. */
    val tomatometer: Int? = null,
    /** Rotten Tomatoes audience score (Popcornmeter), 0–100. */
    val popcornmeter: Int? = null,
    val imdb: Double? = null,
    val imdbVotes: Int? = null,
    val metacritic: Int? = null,
    /** Letterboxd average, 0–5. */
    val letterboxd: Double? = null,
    /** Where the numbers came from, for the attribution line. */
    val source: String,
) {
    val isEmpty get() = tomatometer == null && popcornmeter == null && imdb == null && metacritic == null && letterboxd == null
}

/** One spoiler-free review/comment and the score its author gave (1–10), if they rated it. */
data class Review(
    val id: Long,
    val author: String,
    val rating: Int?,
    val text: String,
    /** Trakt marks comments of 200+ words as reviews. */
    val isLongReview: Boolean,
    val likes: Int,
    /** ISO date (yyyy-MM-dd). */
    val date: String?,
)

/** API keys for the optional rating sources. Both are free; see Settings → Ratings. */
data class RatingsKeys(val mdblist: String = "", val trakt: String = "") {
    val hasAny get() = mdblist.isNotBlank() || trakt.isNotBlank()
}

/**
 * Keeps [RatingsKeys] in the platform's secure vault, and optionally in the user's Jellyfin
 * profile (DisplayPreferences) so a key typed once on a PC also appears on the TV.
 */
class RatingsKeyStore(private val vault: SecureVault) {
    private val _keys = MutableStateFlow(RatingsKeys(vault.get(MDBLIST).orEmpty(), vault.get(TRAKT).orEmpty()))
    val keys: StateFlow<RatingsKeys> = _keys.asStateFlow()
    private var pulledFrom: String? = null

    fun save(keys: RatingsKeys) {
        val clean = RatingsKeys(keys.mdblist.trim(), keys.trakt.trim())
        if (clean.mdblist.isBlank()) vault.remove(MDBLIST) else vault.put(MDBLIST, clean.mdblist)
        if (clean.trakt.isBlank()) vault.remove(TRAKT) else vault.put(TRAKT, clean.trakt)
        _keys.value = clean
    }

    /** Fills missing keys from the user's Jellyfin profile (once per server/user per run). */
    suspend fun pull(api: JellyfinApi) {
        val who = "${api.baseUrl}|${api.userId}"
        if (pulledFrom == who) return
        pulledFrom = who
        val prefs = runCatching { api.customPrefs(PREFS_ID, PREFS_CLIENT) }.getOrNull() ?: return
        val current = _keys.value
        val merged = RatingsKeys(
            current.mdblist.ifBlank { prefs["mdblistKey"].orEmpty() },
            current.trakt.ifBlank { prefs["traktClientId"].orEmpty() },
        )
        if (merged != current) save(merged)
    }

    /** Stores the keys in the user's Jellyfin profile so their other Aurora devices pick them up. */
    suspend fun push(api: JellyfinApi, keys: RatingsKeys) {
        api.saveCustomPrefs(PREFS_ID, PREFS_CLIENT, mapOf("mdblistKey" to keys.mdblist.trim(), "traktClientId" to keys.trakt.trim()))
        pulledFrom = "${api.baseUrl}|${api.userId}"
    }

    companion object {
        private const val MDBLIST = "ratings:mdblist"
        private const val TRAKT = "ratings:trakt"
        const val PREFS_ID = "aurora-ratings"
        const val PREFS_CLIENT = "AuroraTV"
    }
}

/**
 * Fetches ratings (MDBList) and spoiler-free reviews (Trakt) for movies and series. Results are
 * cached in memory for the session (failures briefly), requests time out after a few seconds, and
 * nothing here ever blocks the detail page: callers fetch after the item is shown. API keys travel
 * only to their own service and are never logged.
 */
class RatingsService(
    private val http: HttpClient,
    private val keys: () -> RatingsKeys,
) {
    private val mutex = Mutex()
    private val cache = HashMap<String, Cached>()
    private val clock = TimeSource.Monotonic.markNow()

    private class Cached(val value: Any?, val atMs: Long, val ok: Boolean)

    private fun nowMs() = clock.elapsedNow().inWholeMilliseconds

    /** Session cache: successes for [OK_TTL_MS], failures (null) for [FAIL_TTL_MS] so a dead key/network isn't hammered. */
    private suspend fun cached(key: String, load: suspend () -> Any): Any? {
        mutex.withLock {
            cache[key]?.let { c -> if (nowMs() - c.atMs < if (c.ok) OK_TTL_MS else FAIL_TTL_MS) return c.value }
        }
        val loaded = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT_MS) { runCatching { load() }.getOrNull() } }
        mutex.withLock {
            if (cache.size > MAX_ENTRIES) cache.clear()
            cache[key] = Cached(loaded, nowMs(), loaded != null)
        }
        return loaded
    }

    /** True when [item] is something the rating sites know (a movie or series with an id they use). */
    fun supports(item: BaseItemDto) = (item.isMovie || item.isSeries) && (tmdbId(item) != null || imdbId(item) != null)

    /**
     * Ratings for [item]: MDBList when a key is set, otherwise Jellyfin's own community/critic
     * fields (null when there is nothing to show).
     */
    suspend fun ratings(item: BaseItemDto): ExternalRatings? {
        val fallback = fromJellyfin(item)
        val key = keys().mdblist
        val tmdb = tmdbId(item)
        if (key.isBlank() || tmdb == null || !supports(item)) return fallback
        val type = if (item.isSeries) "show" else "movie"
        val fetched = cached("ratings:$type:$tmdb") {
            val response = http.get("https://api.mdblist.com/tmdb/$type/$tmdb") { parameter("apikey", key) }
            response.ensure("MDBList")
            parseMdbList(response.bodyAsText())
        } as ExternalRatings?
        // Keep Jellyfin's critic score if MDBList lacks one.
        return fetched?.let { if (it.tomatometer == null && fallback?.tomatometer != null) it.copy(tomatometer = fallback.tomatometer) else it }
            ?.takeUnless { it.isEmpty } ?: fallback
    }

    /** Spoiler-free Trakt comments, most-liked first; empty without a Trakt key. */
    suspend fun reviews(item: BaseItemDto): List<Review> {
        val clientId = keys().trakt
        val id = imdbId(item) ?: return emptyList()
        if (clientId.isBlank() || !supports(item)) return emptyList()
        val type = if (item.isSeries) "shows" else "movies"
        val result = cached("reviews:$type:$id") {
            val response = http.get("https://api.trakt.tv/$type/$id/comments/likes") {
                parameter("limit", 50)
                header("trakt-api-version", "2")
                header("trakt-api-key", clientId)
            }
            response.ensure("Trakt")
            parseTraktComments(response.bodyAsText())
        }
        @Suppress("UNCHECKED_CAST")
        val reviews = result as? List<Review>
        return reviews.orEmpty()
    }

    /** Checks an MDBList key; returns a short status line for Settings. */
    suspend fun testMdbList(key: String): String = runCatching {
        val r = http.get("https://api.mdblist.com/user") { parameter("apikey", key.trim()) }
        when {
            r.status.isSuccess() -> "Key works" + (Regex("\"api_requests\"\\s*:\\s*(\\d+)").find(r.bodyAsText())?.let { " · ${it.groupValues[1]} requests/day" } ?: "")
            r.status.value == 401 || r.status.value == 403 -> "Key rejected by MDBList"
            else -> "MDBList answered HTTP ${r.status.value}"
        }
    }.getOrElse { "Couldn't reach MDBList (${it::class.simpleName})" }

    /** Checks a Trakt client id with a tiny request. */
    suspend fun testTrakt(clientId: String): String = runCatching {
        val r = http.get("https://api.trakt.tv/movies/tt0111161") {
            header("trakt-api-version", "2"); header("trakt-api-key", clientId.trim())
        }
        when {
            r.status.isSuccess() -> "Client ID works"
            r.status.value == 401 || r.status.value == 403 -> "Client ID rejected by Trakt"
            else -> "Trakt answered HTTP ${r.status.value}"
        }
    }.getOrElse { "Couldn't reach Trakt (${it::class.simpleName})" }

    private fun HttpResponse.ensure(service: String) {
        // The URL carries the API key, so only the status is logged.
        if (!status.isSuccess()) { Log.w("Ratings", "$service answered HTTP ${status.value}"); error("HTTP ${status.value}") }
    }

    companion object {
        private const val OK_TTL_MS = 6 * 60 * 60 * 1000L
        private const val FAIL_TTL_MS = 5 * 60 * 1000L
        private const val TIMEOUT_MS = 8_000L
        private const val MAX_ENTRIES = 400

        fun tmdbId(item: BaseItemDto) = item.providerIds.entries.firstOrNull { it.key.equals("Tmdb", true) }?.value?.takeIf { it.isNotBlank() }
        fun imdbId(item: BaseItemDto) = item.providerIds.entries.firstOrNull { it.key.equals("Imdb", true) }?.value?.takeIf { it.startsWith("tt") }

        /** Jellyfin's CriticRating is the Rotten Tomatoes critics score when the server's metadata has it. */
        fun fromJellyfin(item: BaseItemDto): ExternalRatings? {
            val critic = item.criticRating?.toInt()
            return if (critic == null) null else ExternalRatings(tomatometer = critic, source = "Jellyfin")
        }

        @Serializable
        private data class MdbRating(val source: String? = null, val value: Double? = null, val score: Double? = null, val votes: Long? = null)
        @Serializable
        private data class MdbResponse(val ratings: List<MdbRating> = emptyList())

        fun parseMdbList(json: String): ExternalRatings {
            val ratings = AuroraJson.decodeFromString(MdbResponse.serializer(), json).ratings
            fun v(source: String) = ratings.firstOrNull { it.source.equals(source, true) }?.takeIf { (it.value ?: 0.0) > 0.0 }
            return ExternalRatings(
                tomatometer = v("tomatoes")?.value?.toInt(),
                popcornmeter = v("popcorn")?.value?.toInt(),
                imdb = v("imdb")?.value,
                imdbVotes = v("imdb")?.votes?.toInt(),
                metacritic = v("metacritic")?.value?.toInt(),
                letterboxd = v("letterboxd")?.value,
                source = "MDBList",
            )
        }

        @Serializable
        private data class TraktUser(val username: String? = null, val name: String? = null, @SerialName("private") val isPrivate: Boolean = false)
        @Serializable
        private data class TraktUserStats(val rating: Int? = null)
        @Serializable
        private data class TraktComment(
            val id: Long = 0,
            val comment: String = "",
            val spoiler: Boolean = false,
            val review: Boolean = false,
            val likes: Int = 0,
            @SerialName("created_at") val createdAt: String? = null,
            @SerialName("user_rating") val userRating: Int? = null,
            @SerialName("user_stats") val userStats: TraktUserStats? = null,
            val user: TraktUser? = null,
        )

        private val SPOILER_TAG = Regex("\\[/?spoiler]", RegexOption.IGNORE_CASE)

        /**
         * Trakt comments → reviews, dropping anything flagged as a spoiler or containing spoiler
         * tags, and one-liners too short to be useful. Longer reviews first, then by likes.
         */
        fun parseTraktComments(json: String): List<Review> =
            AuroraJson.decodeFromString(ListSerializer(TraktComment.serializer()), json)
                .asSequence()
                .filter { !it.spoiler && !SPOILER_TAG.containsMatchIn(it.comment) }
                .map { it.copy(comment = it.comment.trim()) }
                .filter { it.comment.length >= 40 }
                .map { c ->
                    Review(
                        id = c.id,
                        author = c.user?.name?.takeIf { it.isNotBlank() && !c.user.isPrivate } ?: c.user?.username ?: "Trakt user",
                        rating = (c.userStats?.rating ?: c.userRating)?.takeIf { it in 1..10 },
                        text = c.comment,
                        isLongReview = c.review,
                        likes = c.likes,
                        date = c.createdAt?.take(10),
                    )
                }
                .sortedWith(compareByDescending<Review> { it.isLongReview }.thenByDescending { it.likes })
                .take(20)
                .toList()
    }
}
