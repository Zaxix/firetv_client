package com.auroratv.jellyfin

import io.ktor.http.isSuccess
import io.ktor.client.statement.bodyAsBytes
import com.auroratv.AppInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import com.auroratv.core.AppError
import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.jellyfin.model.AuthenticateByName
import com.auroratv.jellyfin.model.AuthenticationResult
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.MediaSegmentDto
import com.auroratv.jellyfin.model.PlaybackInfoRequest
import com.auroratv.jellyfin.model.PlaybackInfoResponse
import com.auroratv.jellyfin.model.PlaybackReport
import com.auroratv.jellyfin.model.PublicSystemInfo
import com.auroratv.jellyfin.model.QueryResult
import com.auroratv.jellyfin.model.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLQueryComponent
import io.ktor.serialization.kotlinx.json.json

/** Identifies this app/device to Jellyfin (shown in Dashboard → Devices). */
data class ClientIdentity(
    val deviceName: String,
    val deviceId: String,
    val clientName: String = AppInfo.NAME,
    val clientVersion: String = AppInfo.VERSION,
) {
    fun authorizationHeader(token: String?): String = buildString {
        append("MediaBrowser Client=\"").append(clientName.headerSafe())
        append("\", Device=\"").append(deviceName.headerSafe())
        append("\", DeviceId=\"").append(deviceId)
        append("\", Version=\"").append(clientVersion).append('"')
        if (token != null) append(", Token=\"").append(token).append('"')
    }

    private fun String.headerSafe() = encodeURLQueryComponent()
}

/** The platform HTTP engine tuned for talking to one media server (null = Ktor's default from the classpath). */
expect fun platformHttpEngine(maxRequestsPerHost: Int): io.ktor.client.engine.HttpClientEngine?

/** Shared HTTP client configured for Jellyfin. The engine is the tuned platform one, or a test's. */
fun createJellyfinHttpClient(base: HttpClient? = null): HttpClient {
    val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(ContentNegotiation) { json(AuroraJson) }
        // Many servers redirect http→https (301) or add a path. Follow redirects for every method,
        // including the sign-in POST, but never downgrade https to http.
        install(HttpRedirect) {
            checkHttpMethod = false
            allowHttpsDowngrade = false
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }
    if (base != null) return base.config(config)
    return platformHttpEngine(maxRequestsPerHost = 16)?.let { HttpClient(it, config) } ?: HttpClient(config)
}

/** Common item fields requested for cards and detail pages. */
object Fields {
    /** Card + hero text. No ChildCount/RecursiveItemCount: nothing draws them and the server counts every episode of every series for them. */
    const val CARD = "PrimaryImageAspectRatio,Overview,Genres,ParentId,SeriesPrimaryImageTag"
    const val DETAIL = "Overview,Genres,Studios,People,Taglines,MediaSources,MediaStreams,Chapters,PrimaryImageAspectRatio,ChildCount,RecursiveItemCount,OriginalTitle,ParentId"
    const val IMAGE_TYPES = "Primary,Backdrop,Logo,Thumb"
    /** Card fields plus what recommendations compare (tags, origin, studios, ids; the age rating always comes). */
    const val SIMILARITY = "$CARD,Tags,ProductionLocations,OriginalTitle,Studios,ProviderIds"
    /** Just what a poster card draws: no overview/genres/child counts (child counts are costly for series). */
    const val POSTER = "PrimaryImageAspectRatio"
}

data class ItemQuery(
    val parentId: String? = null,
    val includeItemTypes: List<String> = emptyList(),
    val recursive: Boolean = true,
    val sortBy: String = "SortName",
    val sortDescending: Boolean = false,
    val startIndex: Int = 0,
    val limit: Int = 60,
    val filters: List<String> = emptyList(),
    val searchTerm: String? = null,
    val genres: List<String> = emptyList(),
    val isFavorite: Boolean? = null,
    val fields: String = Fields.CARD,
    /** Only items from these studios/networks (Jellyfin studio item ids). */
    val studioIds: List<String> = emptyList(),
    /** Ask the server to count all matches (a second, sometimes slow, query on big libraries). */
    val totalCount: Boolean = true,
    /** Only items featuring these people (Jellyfin person ids). */
    val personIds: List<String> = emptyList(),
    /** Include watched state/progress (skipped for lightweight lists such as the search index). */
    val userData: Boolean = true,
    /** Image kinds to include tags for (fewer = smaller responses). */
    val imageTypes: String = Fields.IMAGE_TYPES,
    /** Only these items (e.g. a ranked list worked out on the device), in the server's order. */
    val ids: List<String> = emptyList(),
    /** Never these items. */
    val excludeIds: List<String> = emptyList(),
)

/**
 * Typed wrapper over the Jellyfin REST API (10.9+ routes). One instance per server; [accessToken] and
 * [userId] are set once signed in.
 */
class JellyfinApi(
    private val http: HttpClient,
    val baseUrl: String,
    val identity: ClientIdentity,
    @kotlin.concurrent.Volatile var accessToken: String? = null,
    @kotlin.concurrent.Volatile var userId: String? = null,
) {
    private val tag = "JellyfinApi"

    /**
     * Set for a device-only profile: its own watch state replaces the account's on every item, and
     * its viewing is recorded on the device instead of the server (see LocalWatchData).
     */
    @kotlin.concurrent.Volatile var local: LocalWatchData? = null

    private fun BaseItemDto.withLocal(): BaseItemDto = local?.let { l -> copy(userData = l.userData(this)) } ?: this
    private fun List<BaseItemDto>.withLocal(): List<BaseItemDto> = if (local == null) this else map { it.withLocal() }

    /**
     * Episodes stored several to a file, shown and played separately (see VirtualEpisodes). Null: no
     * splitting (nothing changes anywhere).
     */
    @kotlin.concurrent.Volatile var virtual: VirtualEpisodes? = null

    /** Split files shown as their episodes (no work when nothing is split). */
    private fun List<BaseItemDto>.split(): List<BaseItemDto> = virtual?.expand(this) ?: this

    /** The server's item for [id]: a virtual episode's file, or [id] itself. */
    private fun sid(id: String) = VirtualIds.serverId(id)

    /**
     * Series shown in another episode order (DVD, intended, hand-made; see EpisodeOrders). Null, or no
     * order for a series: the server's order, unchanged.
     */
    @kotlin.concurrent.Volatile var order: EpisodeOrders? = null
    /** Where source orders come from (TMDB episode groups); null: hand-made orders only. */
    @kotlin.concurrent.Volatile var orderSource: TmdbEpisodeGroups? = null

    private class CachedArrangement(val plan: OrderPlan, val mark: kotlin.time.TimeSource.Monotonic.ValueTimeMark, val value: Arrangement)
    private val arrangeLock = kotlinx.coroutines.sync.Mutex()
    /** Arranged series (copy-on-write: read and cleared from any thread). */
    @kotlin.concurrent.Volatile private var arranged: Map<String, CachedArrangement> = emptyMap()

    /** Forgets arranged lists (watched state or an order changed). */
    fun invalidateArrangements(seriesId: String? = null) { arranged = if (seriesId == null) emptyMap() else arranged - seriesId }

    private fun List<BaseItemDto>.relabel(): List<BaseItemDto> = order?.let { o -> map { o.relabel(it) } } ?: this

    /** [item] with the season and number its series' order shows (for display; ids and files are the same). */
    fun displayed(item: BaseItemDto): BaseItemDto = order?.relabel(item) ?: item

    /**
     * [seriesId]'s episodes arranged in its episode order, or null when it's shown in the server's
     * order. One request for all its episodes (with its seasons alongside), kept for a minute or until
     * something is watched, so the season list, each season and Next Up share it.
     */
    suspend fun arrangement(seriesId: String): Arrangement? {
        val o = order ?: return null
        val plan = o.plan(seriesId) ?: return null
        return arrangeLock.withLock {
            arranged[seriesId]?.takeIf { it.plan == plan && it.mark.elapsedNow() < ARRANGEMENT_TTL }?.let { return@withLock it.value }
            val (seasons, all) = coroutineScope {
                val s = async { serverSeasons(seriesId) }
                val e = async { serverEpisodes(seriesId) }
                s.await() to e.await()
            }
            o.arrange(seriesId, plan, seasons, all).also {
                arranged = arranged + (seriesId to CachedArrangement(plan, kotlin.time.TimeSource.Monotonic.markNow(), it))
                o.remember(it)
                Log.i("EpisodeOrder", "${all.firstOrNull()?.seriesName ?: seriesId} in ${plan.label}: " + it.seasons.joinToString { s -> "${s.name} = ${it.episodesBySeason[s.id]?.size ?: 0} episodes" })
            }
        }
    }

    /**
     * Looks up the alternative orders TMDB lists for [series] (at most once a week; never when there's
     * no TMDB key or the series has no TMDB id), fetches the ones automatic ordering would use, and
     * checks each against the library: kept only if its numbers find the library's episodes under the
     * same titles. Offline or failing: whatever was found before stays in use. True when the order
     * [series] is shown in changed.
     */
    suspend fun lookUpOrders(series: BaseItemDto, force: Boolean = false): Boolean {
        val o = order ?: return false
        val src = orderSource?.takeIf { it.available } ?: return false
        val tmdb = series.providerIds.entries.firstOrNull { it.key.equals("Tmdb", true) }?.value?.takeIf { it.isNotBlank() } ?: return false
        val now = com.auroratv.playback.wallClockMs()
        val current = o.store[series.id]
        if (!force && current?.sources != null && now - current.sourcesAtMs < EpisodeOrders.SOURCES_TTL_MS) return false
        val before = o.plan(series.id)
        val list = src.list(tmdb) ?: return false
        val wanted = o.candidates(list).filter { it.id !in current?.details.orEmpty() }
        val fetched = wanted.mapNotNull { src.details(it.id) }
        val library = if (fetched.isEmpty()) emptyList() else serverEpisodes(series.id, expand = false)
        val rejected = fetched.mapNotNull { g -> EpisodeOrders.verify(g, library)?.let { g.id to it } }.toMap()
        o.store.update(series.id) { it.copy(sources = list, sourcesAtMs = now, details = it.details + fetched.associateBy { g -> g.id }, rejected = it.rejected + rejected) }
        Log.i("EpisodeOrder", "${series.name}: TMDB lists ${list.size} order(s)" +
            fetched.joinToString("") { g -> "; ${g.kind} \"${g.name}\" " + (rejected[g.id]?.let { "doesn't fit the library ($it)" } ?: "fits the library") })
        val after = o.plan(series.id)
        if (after != before) invalidateArrangements(series.id)
        return after != before
    }

    /** One of [seriesId]'s source orders, fetched (and checked against the library) if it wasn't yet. Null if unavailable. */
    suspend fun sourceOrder(seriesId: String, groupId: String): Pair<SourceOrder, String?>? {
        val o = order ?: return null
        o.store[seriesId]?.details?.get(groupId)?.let { return it to o.store[seriesId]?.rejected?.get(groupId) }
        val g = orderSource?.details(groupId) ?: return null
        val why = EpisodeOrders.verify(g, serverEpisodes(seriesId, expand = false))
        o.store.update(seriesId) { it.copy(details = it.details + (g.id to g), rejected = if (why != null) it.rejected + (g.id to why) else it.rejected - g.id) }
        return g to why
    }

    /** Sets how [seriesId]'s episodes are ordered (see SeriesOrder.mode) and, for a hand-made order, its seasons. */
    fun setOrder(seriesId: String, mode: String, custom: List<CustomSeason>? = null) {
        val o = order ?: return
        o.store.update(seriesId) { it.copy(mode = mode, custom = custom ?: it.custom) }
        invalidateArrangements(seriesId)
    }

    /** [ids] in that order (a device profile's resume/favourite/history lists). */
    private suspend fun itemsInOrder(ids: List<String>, fields: String = Fields.CARD): List<BaseItemDto> {
        if (ids.isEmpty()) return emptyList()
        val byId = serverItems(ItemQuery(ids = ids, limit = ids.size, recursive = true, fields = fields, totalCount = false)).items.associateBy { it.id }
        return ids.mapNotNull { byId[it] }.withLocal()
    }

    val authHeader: String get() = identity.authorizationHeader(accessToken)

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun HttpRequestBuilder.auth() {
        header("Authorization", identity.authorizationHeader(accessToken))
    }

    private fun requireUser(): String = userId ?: throw AppError.SessionExpired()

    private suspend inline fun <reified T> HttpResponse.read(authCall: Boolean = false): T {
        ensureOk(authCall)
        return try {
            body()
        } catch (t: Throwable) {
            Log.w(tag, "Failed to decode ${call.request.url.encodedPath}", t)
            throw AppError.BadData(t)
        }
    }

    private suspend fun HttpResponse.ensureOk(authCall: Boolean = false) {
        if (status.value in 200..299) return
        Log.w(tag, "HTTP ${status.value} for ${call.request.method.value} ${call.request.url}")
        throw when (status) {
            HttpStatusCode.Unauthorized -> if (authCall) AppError.InvalidCredentials() else AppError.SessionExpired()
            // 403 is the server refusing this device (remote access off for a VPN/outside address, or
            // not allowed), not a bad sign-in: never sign the user out for it.
            HttpStatusCode.Forbidden -> AppError.Refused()
            HttpStatusCode.NotFound -> AppError.NotFound()
            else -> AppError.Server(status.value)
        }
    }

    // ---- System / auth -------------------------------------------------------------------------

    suspend fun publicSystemInfo(): PublicSystemInfo = publicSystemInfoResolved().first

    /**
     * Fetches public server info and returns the base URL the server actually answered on after any
     * redirects (e.g. http://example.com → https://example.com), so that's the address we save.
     */
    suspend fun publicSystemInfoResolved(): Pair<PublicSystemInfo, String> {
        val response = http.get(url("/System/Info/Public"))
        if (response.status == HttpStatusCode.Forbidden) throw AppError.Refused()
        if (response.status.value !in 200..299) throw AppError.NotJellyfin()
        val info = try { response.body<PublicSystemInfo>() } catch (t: Throwable) { throw AppError.NotJellyfin() }
        if (info.id.isNullOrBlank()) throw AppError.NotJellyfin()
        val finalUrl = response.call.request.url.toString()
        val resolved = finalUrl.substringBefore("?").let {
            val idx = it.lastIndexOf("/System/Info/Public", ignoreCase = true)
            if (idx > 0) it.substring(0, idx) else baseUrl
        }.trimEnd('/')
        return info to resolved
    }

    suspend fun publicUsers(): List<UserDto> =
        http.get(url("/Users/Public")) { auth() }.read()

    suspend fun authenticate(username: String, password: String): AuthenticationResult {
        val result: AuthenticationResult = http.post(url("/Users/AuthenticateByName")) {
            header("Authorization", identity.authorizationHeader(null))
            contentType(ContentType.Application.Json)
            setBody(AuthenticateByName(username, password))
        }.read(authCall = true)
        accessToken = result.accessToken
        userId = result.user.id
        return result
    }

    suspend fun currentUser(): UserDto = http.get(url("/Users/Me")) { auth() }.read()

    suspend fun logout() {
        runCatching { http.post(url("/Sessions/Logout")) { auth() } }
    }

    // ---- Browsing ------------------------------------------------------------------------------

    suspend fun userViews(): List<BaseItemDto> =
        http.get(url("/UserViews")) {
            auth(); parameter("userId", requireUser())
        }.read<QueryResult<BaseItemDto>>().items

    suspend fun items(query: ItemQuery): QueryResult<BaseItemDto> {
        val l = local ?: return serverItems(query).let { r -> if (virtual == null) r else r.copy(items = r.items.split()) }
        // A device profile: favourites and watched/in-progress filters come from its own history;
        // the server is only asked for the items themselves.
        var q = query
        var orderBy: List<String>? = null
        var total: Int? = null
        // Narrows the query to [ids] (keeping only those the caller already asked for, if any).
        fun only(ids: List<String>): List<String> = if (q.ids.isEmpty()) ids else ids.filter { it in q.ids }
        if (q.isFavorite == true) {
            val ids = only(l.favoriteIds().take(MAX_LOCAL_IDS)).ifEmpty { return QueryResult() }
            q = q.copy(isFavorite = null, ids = ids)
        }
        when {
            "IsPlayed" in q.filters -> {
                val ids = only(l.playedIds(MAX_LOCAL_IDS)).ifEmpty { return QueryResult() }
                if (q.sortBy.startsWith("DatePlayed")) {
                    // Most recent first, paged here (the server only sees this page's ids).
                    val page = ids.drop(q.startIndex).take(q.limit).ifEmpty { return QueryResult(totalRecordCount = ids.size) }
                    q = q.copy(filters = q.filters - "IsPlayed", ids = page, startIndex = 0); orderBy = page; total = ids.size
                } else q = q.copy(filters = q.filters - "IsPlayed", ids = ids)
            }
            "IsResumable" in q.filters -> {
                val ids = only(l.resumeIds(MAX_LOCAL_IDS)).ifEmpty { return QueryResult() }
                val page = ids.drop(q.startIndex).take(q.limit).ifEmpty { return QueryResult(totalRecordCount = ids.size) }
                q = q.copy(filters = q.filters - "IsResumable", ids = page, startIndex = 0); orderBy = page; total = ids.size
            }
            "IsUnplayed" in q.filters -> q = q.copy(filters = q.filters - "IsUnplayed", excludeIds = l.playedIds(MAX_LOCAL_IDS))
        }
        val result = serverItems(q)
        val items = result.items.withLocal()
        return result.copy(
            items = (orderBy?.let { order -> val byId = items.associateBy { it.id }; order.mapNotNull { byId[it] } } ?: items).split(),
            totalRecordCount = total ?: result.totalRecordCount,
        )
    }

    private suspend fun serverItems(query: ItemQuery): QueryResult<BaseItemDto> =
        http.get(url("/Items")) {
            auth()
            parameter("userId", requireUser())
            query.parentId?.let { parameter("parentId", it) }
            if (query.includeItemTypes.isNotEmpty()) parameter("includeItemTypes", query.includeItemTypes.joinToString(","))
            parameter("recursive", query.recursive)
            parameter("sortBy", query.sortBy)
            parameter("sortOrder", if (query.sortDescending) "Descending" else "Ascending")
            parameter("startIndex", query.startIndex)
            parameter("limit", query.limit)
            if (query.filters.isNotEmpty()) parameter("filters", query.filters.joinToString(","))
            query.searchTerm?.let { parameter("searchTerm", it) }
            if (query.genres.isNotEmpty()) parameter("genres", query.genres.joinToString("|"))
            query.isFavorite?.let { parameter("isFavorite", it) }
            if (query.studioIds.isNotEmpty()) parameter("studioIds", query.studioIds.joinToString("|"))
            if (query.personIds.isNotEmpty()) parameter("personIds", query.personIds.joinToString("|"))
            if (query.ids.isNotEmpty()) parameter("ids", query.ids.joinToString(","))
            if (query.excludeIds.isNotEmpty()) parameter("excludeItemIds", query.excludeIds.joinToString(","))
            if (!query.userData) parameter("enableUserData", false)
            parameter("fields", query.fields)
            parameter("imageTypeLimit", 1)
            parameter("enableImageTypes", query.imageTypes)
            parameter("enableTotalRecordCount", query.totalCount)
        }.read()

    /** People (actors, directors…) whose name matches [term]. */
    suspend fun persons(term: String, limit: Int = 8): List<BaseItemDto> =
        http.get(url("/Persons")) {
            auth()
            parameter("userId", requireUser())
            parameter("searchTerm", term)
            parameter("limit", limit)
            parameter("enableImages", true)
            parameter("imageTypeLimit", 1)
            parameter("enableUserData", false)
        }.read<QueryResult<BaseItemDto>>().items

    /** Studios and networks used by the user's movies and series (for brand tiles), with any images. */
    suspend fun studios(): List<BaseItemDto> =
        http.get(url("/Studios")) {
            auth()
            parameter("userId", requireUser())
            parameter("includeItemTypes", "Movie,Series")
            parameter("recursive", true)
            parameter("enableImages", true)
            parameter("imageTypeLimit", 1)
            parameter("enableImageTypes", "Logo,Thumb,Primary")
            parameter("enableUserData", false)
            parameter("enableTotalRecordCount", false)
        }.read<QueryResult<BaseItemDto>>().items

    /** CustomPrefs of a DisplayPreferences entry (per user + client); the server returns defaults if none exist. */
    suspend fun customPrefs(id: String, client: String): Map<String, String> {
        val obj: kotlinx.serialization.json.JsonObject = http.get(url("/DisplayPreferences/$id")) {
            auth(); parameter("userId", requireUser()); parameter("client", client)
        }.read()
        val prefs = obj["CustomPrefs"] as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
        return prefs.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
    }

    /** Merges [values] into a DisplayPreferences entry's CustomPrefs (read-modify-write keeps the other fields). */
    suspend fun saveCustomPrefs(id: String, client: String, values: Map<String, String>) {
        val current: kotlinx.serialization.json.JsonObject = http.get(url("/DisplayPreferences/$id")) {
            auth(); parameter("userId", requireUser()); parameter("client", client)
        }.read()
        val prefs = (current["CustomPrefs"] as? kotlinx.serialization.json.JsonObject).orEmpty() +
            values.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
        val body = kotlinx.serialization.json.JsonObject(current + mapOf(
            "Id" to kotlinx.serialization.json.JsonPrimitive(id),
            "Client" to kotlinx.serialization.json.JsonPrimitive(client),
            "CustomPrefs" to kotlinx.serialization.json.JsonObject(prefs),
        ))
        http.post(url("/DisplayPreferences/$id")) {
            auth(); parameter("userId", requireUser()); parameter("client", client)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.ensureOk()
    }

    /** The server's item for [itemId] (a virtual episode's file), whole: no episode split applied. */
    suspend fun fileItem(itemId: String): BaseItemDto =
        http.get(url("/Items/${sid(itemId)}")) {
            auth(); parameter("userId", requireUser())
        }.read<BaseItemDto>().withLocal()

    suspend fun item(itemId: String): BaseItemDto {
        val dto = fileItem(itemId)
        val v = virtual ?: return dto
        // A virtual episode is built from its file; a shared-file entry gets its part's length and progress.
        VirtualIds.part(itemId)?.let { k ->
            val split = v.splits[dto.id]?.takeIf { it.isSplit && it.fits(dto) } ?: return dto
            if (k in split.parts.indices) return v.build(dto, k, split)
            return dto
        }
        return v.entryView(dto) ?: dto
    }

    suspend fun resumeItems(limit: Int = 24): List<BaseItemDto> {
        val list = local?.let { l -> itemsInOrder(l.resumeIds(limit)) } ?: http.get(url("/UserItems/Resume")) {
            auth()
            parameter("userId", requireUser())
            parameter("limit", limit)
            parameter("mediaTypes", "Video")
            parameter("fields", Fields.CARD)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
            parameter("imageTypeLimit", 1)
        }.read<QueryResult<BaseItemDto>>().items
        return withVirtualResume(list, limit).relabel()
    }

    /**
     * Continue Watching with virtual episodes: a split file's own server resume point is replaced by
     * the parts this profile has in progress (one request for their files, only when there are any),
     * in order of when they were last watched.
     */
    private suspend fun withVirtualResume(list: List<BaseItemDto>, limit: Int): List<BaseItemDto> {
        val v = virtual ?: return list
        if (!v.splits.anySplit) return list
        val partIds = v.resumeIds(limit)
        val fileIds = partIds.map { VirtualIds.serverId(it) }.distinct().filter { f -> list.none { it.id == f } }
        val files = if (fileIds.isEmpty()) emptyList() else itemsInOrder(fileIds)
        val byId = (list + files).associateBy { it.id }
        val parts = partIds.mapNotNull { pid -> byId[VirtualIds.serverId(pid)]?.let { f -> v.expand(listOf(f)).firstOrNull { it.id == pid } } }
            .filter { (it.userData?.playbackPositionTicks ?: 0) > 0 && it.userData?.played != true }
        // The server's entries for split files (and shared-file entries) are dropped: their progress is the parts'.
        val rest = list.filter { item -> !v.handles(item.id) && v.splits[item.id]?.isSplit != true }
        fun whenMs(i: BaseItemDto): Long = v.lastWatched(i.id) ?: com.auroratv.data.AirDates.parse(i.userData?.lastPlayedDate)?.instantMs ?: 0L
        return (parts + rest).sortedByDescending(::whenMs).take(limit)
    }

    /**
     * A device profile's Next Up: for each series it watched lately, the first unwatched episode after
     * the one it finished most recently. One small request per series (at most [NEXT_UP_SERIES]):
     * the episode list starting at that episode, a few items long.
     */
    private suspend fun localNextUp(l: LocalWatchData, limit: Int, seriesId: String?): List<BaseItemDto> = coroutineScope {
        val series = seriesId?.let { listOf(it) } ?: l.recentSeries(NEXT_UP_SERIES)
        series.map { sid ->
            async {
                val last = l.lastFinishedEpisode(sid) ?: return@async null
                val eps = runCatching { episodes(sid, startItemId = last, limit = NEXT_UP_LOOKAHEAD) }.getOrNull() ?: return@async null
                eps.dropWhile { it.id != last }.drop(1)
                    .firstOrNull { it.userData?.played != true && (it.userData?.playbackPositionTicks ?: 0) == 0L && (it.parentIndexNumber ?: 1) > 0 }
            }
        }.awaitAll().filterNotNull().take(limit)
    }

    suspend fun nextUp(limit: Int = 24, seriesId: String? = null): List<BaseItemDto> {
        val o = order
        // A series in another episode order: its next episode in that order (the server's Next Up
        // follows its own numbering).
        if (seriesId != null && o?.active(seriesId) == true) {
            arrangement(seriesId)?.let { a -> return listOfNotNull(o.nextUp(a, allowStarted = true)) }
        }
        val list = local?.let { l -> localNextUp(l, limit, seriesId) } ?: serverNextUp(limit, seriesId)
        val v = virtual
        // A split file in Next Up is its first episode not finished yet (the server only knows the file).
        // One already started is in Continue Watching, so Home's Next Up leaves it out; a single
        // series' Next Up (its Play button) keeps it, to resume it.
        val split = if (v == null) list else list.flatMap { item ->
            val parts = v.expand(listOf(item))
            if (parts.size == 1) return@flatMap parts
            val next = parts.firstOrNull { it.userData?.played != true }
            listOfNotNull(next?.takeIf { seriesId != null || (it.userData?.playbackPositionTicks ?: 0L) == 0L })
        }
        if (o == null || split.none { o.active(it.seriesId) }) return split
        return coroutineScope {
            split.map { item ->
                async {
                    val sid = item.seriesId
                    if (sid == null || !o.active(sid)) item
                    else runCatching { arrangement(sid)?.let { o.nextUp(it, allowStarted = seriesId != null) } }.getOrNull()
                }
            }.awaitAll().filterNotNull().distinctBy { it.id }
        }
    }

    /**
     * This account's viewing history: films and episodes by when they were last played (most recent
     * first; never-played ones sort last and are dropped). For the household's Top 10.
     */
    suspend fun watchHistory(limit: Int = 300): List<BaseItemDto> =
        http.get(url("/Items")) {
            auth()
            parameter("userId", requireUser())
            parameter("recursive", true)
            parameter("includeItemTypes", "Movie,Episode")
            parameter("sortBy", "DatePlayed")
            parameter("sortOrder", "Descending")
            parameter("limit", limit)
            parameter("enableImages", false)
            parameter("enableTotalRecordCount", false)
        }.read<QueryResult<BaseItemDto>>().items.filter { it.userData?.lastPlayedDate != null }

    /**
     * What a show's Play starts: the series' Next Up (the episode in progress or next, in the show's
     * episode order), else its first episode. The same choice as the series page's Play button.
     */
    suspend fun seriesPlayTarget(seriesId: String): BaseItemDto? =
        nextUp(limit = 1, seriesId = seriesId).firstOrNull()
            ?: episodes(seriesId).firstOrNull { it.locationType != "Virtual" && (it.parentIndexNumber ?: 1) > 0 }

    private suspend fun serverNextUp(limit: Int, seriesId: String?): List<BaseItemDto> {
        return http.get(url("/Shows/NextUp")) {
            auth()
            parameter("userId", requireUser())
            parameter("limit", limit)
            seriesId?.let { parameter("seriesId", it) }
            parameter("fields", Fields.CARD)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
            parameter("imageTypeLimit", 1)
            parameter("enableResumable", false)
        }.read<QueryResult<BaseItemDto>>().items
    }

    suspend fun latest(parentId: String?, limit: Int = 24, includeItemTypes: List<String> = emptyList()): List<BaseItemDto> =
        http.get(url("/Items/Latest")) {
            auth()
            parameter("userId", requireUser())
            parentId?.let { parameter("parentId", it) }
            parameter("limit", limit)
            if (includeItemTypes.isNotEmpty()) parameter("includeItemTypes", includeItemTypes.joinToString(","))
            parameter("fields", Fields.CARD)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
            parameter("imageTypeLimit", 1)
            parameter("groupItems", true)
        }.read<List<BaseItemDto>>().withLocal().split().relabel()

    suspend fun seasons(seriesId: String): List<BaseItemDto> = arrangement(seriesId)?.seasons ?: serverSeasons(seriesId)

    private suspend fun serverSeasons(seriesId: String): List<BaseItemDto> =
        http.get(url("/Shows/$seriesId/Seasons")) {
            auth(); parameter("userId", requireUser()); parameter("fields", Fields.CARD)
        }.read<QueryResult<BaseItemDto>>().items.withLocal()

    /**
     * A series' episodes (one season's, or all of them), in its episode order when it has one. From
     * [startItemId] on: that episode and the ones after it in viewing order (across seasons).
     */
    suspend fun episodes(seriesId: String, seasonId: String? = null, startItemId: String? = null, limit: Int? = null): List<BaseItemDto> {
        arrangement(seriesId)?.let { a ->
            val base = if (seasonId != null) a.episodesBySeason[seasonId].orEmpty() else a.all
            val from = if (startItemId != null) base.dropWhile { it.id != startItemId } else base
            return limit?.let { from.take(it) } ?: from
        }
        return serverEpisodes(seriesId, seasonId, startItemId, limit)
    }

    /** Every episode of [seriesId] in the server's own order and numbering (no episode order applied). */
    suspend fun serverEpisodeList(seriesId: String): List<BaseItemDto> = serverEpisodes(seriesId)

    /** The server's own list of episodes (split files shown as their episodes unless [expand] is false). */
    private suspend fun serverEpisodes(seriesId: String, seasonId: String? = null, startItemId: String? = null, limit: Int? = null, expand: Boolean = true): List<BaseItemDto> {
        val v = virtual.takeIf { expand }
        // From a virtual episode: ask from its file (and enough more to cover the file's other parts).
        val serverStart = startItemId?.let(::sid)
        val extra = startItemId?.takeIf { VirtualIds.isVirtual(it) }?.let { v?.splits?.get(sid(it))?.parts?.size } ?: 0
        val list = http.get(url("/Shows/$seriesId/Episodes")) {
            auth()
            parameter("userId", requireUser())
            seasonId?.let { parameter("seasonId", it) }
            serverStart?.let { parameter("startItemId", it) }
            limit?.let { parameter("limit", it + extra) }
            // Path: spotting entries that share one file (see VirtualEpisodes.candidates).
            parameter("fields", if (v != null) "${Fields.CARD},Path" else Fields.CARD)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
        }.read<QueryResult<BaseItemDto>>().items.withLocal()
        if (v == null) return list
        decideSplits(v, list)
        val out = v.expand(list)
        if (startItemId == null || !VirtualIds.isVirtual(startItemId)) return out
        return out.dropWhile { it.id != startItemId }.let { rest -> limit?.let { rest.take(it) } ?: rest }
    }

    /**
     * Files in [list] that may hold several episodes and were never looked at get one look at their
     * chapters (one small request for all of them); the answer is kept, so it happens once per file.
     */
    private suspend fun decideSplits(v: VirtualEpisodes, list: List<BaseItemDto>) {
        val todo = v.candidates(list)
        if (todo.isEmpty()) return
        val ids = todo.flatMap { c -> c.entries.map { it.id } }.distinct()
        val withChapters = runCatching { serverItems(ItemQuery(ids = ids, limit = ids.size, recursive = true, fields = "Chapters,Path", totalCount = false, userData = false)).items }
            .onFailure { Log.w("Splits", "Couldn't read chapters to look for episode boundaries: ${it.message}") }.getOrNull() ?: return
        todo.forEach { v.decide(it, withChapters) }
    }

    /**
     * The server's own list of a series' episodes airing from yesterday on (from its metadata:
     * virtual items for episodes not in the library yet). Names, numbers and dates only.
     */
    suspend fun upcomingEpisodes(seriesId: String, limit: Int = 40): List<BaseItemDto> =
        http.get(url("/Shows/Upcoming")) {
            auth()
            parameter("userId", requireUser())
            parameter("parentId", seriesId)
            parameter("limit", limit)
            parameter("enableImages", false)
            parameter("enableUserData", false)
        }.read<QueryResult<BaseItemDto>>().items

    /** A season's episodes with their chapter lists (used to spot recurring openings/endings). */
    suspend fun episodesWithChapters(seriesId: String, seasonId: String?): List<BaseItemDto> =
        http.get(url("/Shows/$seriesId/Episodes")) {
            auth()
            parameter("userId", requireUser())
            seasonId?.let { parameter("seasonId", it) }
            // Trickplay: where the episode's thumbnail sheets are (for the on-screen credits check).
            parameter("fields", "Chapters,Trickplay")
            parameter("enableImages", false)
        }.read<QueryResult<BaseItemDto>>().items

    suspend fun similar(itemId: String, limit: Int = 16, fields: String = Fields.CARD): List<BaseItemDto> =
        http.get(url("/Items/${sid(itemId)}/Similar")) {
            auth(); parameter("userId", requireUser()); parameter("limit", limit); parameter("fields", fields)
        }.read<QueryResult<BaseItemDto>>().items.withLocal()

    suspend fun setFavorite(itemId: String, favorite: Boolean) {
        local?.let { it.setFavorite(sid(itemId), favorite); return }
        val path = url("/UserFavoriteItems/${sid(itemId)}")
        val response = if (favorite) http.post(path) { auth(); parameter("userId", requireUser()) }
        else http.delete(path) { auth(); parameter("userId", requireUser()) }
        response.ensureOk()
    }

    suspend fun setPlayed(itemId: String, played: Boolean) {
        invalidateArrangements()
        // A virtual episode is marked on the device; the server's item follows only when that's true
        // of it too (a split file once all its episodes are watched; unwatched as soon as one isn't).
        virtual?.takeIf { it.handles(itemId) }?.let { v ->
            v.setPlayed(itemId, played)?.let { serverItem -> setServerPlayed(serverItem, played) }
            return
        }
        setServerPlayed(itemId, played)
    }

    private suspend fun setServerPlayed(itemId: String, played: Boolean) {
        local?.let { it.setPlayed(itemId, played); return }
        val path = url("/UserPlayedItems/$itemId")
        val response = if (played) http.post(path) { auth(); parameter("userId", requireUser()) }
        else http.delete(path) { auth(); parameter("userId", requireUser()) }
        response.ensureOk()
    }

    // ---- Playback ------------------------------------------------------------------------------

    suspend fun playbackInfo(itemId: String, request: PlaybackInfoRequest): PlaybackInfoResponse =
        http.post(url("/Items/${sid(itemId)}/PlaybackInfo")) {
            auth()
            parameter("userId", requireUser())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.read()

    suspend fun mediaSegments(itemId: String): List<MediaSegmentDto> = try {
        http.get(url("/MediaSegments/${sid(itemId)}")) {
            auth()
            parameter("includeSegmentTypes", "Intro")
            parameter("includeSegmentTypes", "Outro")
            parameter("includeSegmentTypes", "Recap")
        }.read<QueryResult<MediaSegmentDto>>().items
    } catch (e: AppError.NotFound) {
        emptyList() // server older than 10.10 or no segment provider
    }

    /**
     * [seconds] of an item's sound from [startMs], as 8-bit mono WAV at [sampleRate] (≈4 KB a second
     * at 4 kHz), for intro/credits detection on the device. Reads only what it needs, then hangs up
     * and tells the server to end that encode (only that play session's). Uses its own play-session id so it never touches the stream
     * being watched (or another window's). Null when the server can't provide it.
     */
    suspend fun audioSample(itemId: String, startMs: Long, seconds: Int, sampleRate: Int): ByteArray? {
        val session = "sharkfin-scan-${itemId.take(12)}-$startMs"
        var stoppedEarly = false
        val bytes = http.prepareGet(url("/Audio/${sid(itemId)}/stream")) {
            auth()
            parameter("static", false)
            parameter("container", "wav")
            parameter("audioCodec", "pcm_u8")
            parameter("audioChannels", 1)
            parameter("audioSampleRate", sampleRate)
            parameter("startTimeTicks", startMs * 10_000)
            // Jellyfin names a progressive encode by file + device + play session (not start point), so
            // each window needs its own session id or it's handed the earlier window's encode.
            parameter("playSessionId", session)
            timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 45_000 }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                Log.w(tag, "Audio sample for intro detection unavailable (HTTP ${response.status.value})")
                return@execute null
            }
            val channel = response.bodyAsChannel()
            val want = 512 + seconds * sampleRate
            val buf = ByteArray(want)
            var n = 0
            while (n < want) {
                val r = channel.readAvailable(buf, n, want - n)
                if (r < 0) break
                n += r
            }
            stoppedEarly = n >= want
            buf.copyOf(n)
        }
        // Only a stretch was needed: end the server's encode now rather than let it run on through
        // the rest of the episode (it has no "duration" option).
        if (stoppedEarly) stopTranscoding(session)
        return bytes
    }

    // A device profile's viewing is recorded on the device, never reported as the account's (that
    // would move the account's resume points and watched marks). Only a keep-alive ping goes to the
    // server, so a transcode it's serving isn't treated as abandoned.
    suspend fun reportStart(report: PlaybackReport) {
        invalidateArrangements()
        if (virtualProgress(report, stopped = false)) return
        local?.let { it.progress(report.itemId, report.positionTicks, stopped = false); return }
        http.post(url("/Sessions/Playing")) { auth(); contentType(ContentType.Application.Json); setBody(report) }.ensureOk()
    }

    suspend fun reportProgress(report: PlaybackReport) {
        if (virtualProgress(report, stopped = false)) {
            keepAlive(report)
            return
        }
        local?.let { l ->
            l.progress(report.itemId, report.positionTicks, stopped = false)
            report.playSessionId?.let { ps -> runCatching { http.post(url("/Sessions/Playing/Ping")) { auth(); parameter("playSessionId", ps) } } }
            return
        }
        http.post(url("/Sessions/Playing/Progress")) { auth(); contentType(ContentType.Application.Json); setBody(report) }.ensureOk()
    }

    suspend fun reportStopped(report: PlaybackReport) {
        invalidateArrangements()
        if (virtualProgress(report, stopped = true)) return
        local?.let { it.progress(report.itemId, report.positionTicks, stopped = true); return }
        http.post(url("/Sessions/Playing/Stopped")) { auth(); contentType(ContentType.Application.Json); setBody(report) }.ensureOk()
    }

    /**
     * A virtual episode's viewing is kept on the device (the server's position for the file would mix
     * up its episodes). When this finishes one, the server's item is marked watched if that's now true
     * of it. Returns false for ordinary items.
     */
    private suspend fun virtualProgress(report: PlaybackReport, stopped: Boolean): Boolean {
        val v = virtual ?: return false
        if (!v.handles(report.itemId)) return false
        v.progress(report.itemId, report.positionTicks, stopped)?.let { serverItem ->
            runCatching { setServerPlayed(serverItem, true) }.onFailure { Log.w("Splits", "Couldn't mark $serverItem watched: ${it.message}") }
        }
        return true
    }

    /** Tells the server a transcode it's serving is still being watched (no position is sent). */
    private suspend fun keepAlive(report: PlaybackReport) {
        report.playSessionId?.let { ps -> runCatching { http.post(url("/Sessions/Playing/Ping")) { auth(); parameter("playSessionId", ps) } } }
    }

    suspend fun stopTranscoding(playSessionId: String) {
        runCatching {
            http.delete(url("/Videos/ActiveEncodings")) {
                auth(); parameter("deviceId", identity.deviceId); parameter("playSessionId", playSessionId)
            }
        }
    }

    // ---- URLs ----------------------------------------------------------------------------------

    /** Absolute URL for a server-relative path such as a TranscodingUrl or subtitle DeliveryUrl. */
    fun absolute(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) pathOrUrl
        else baseUrl.trimEnd('/') + "/" + pathOrUrl.trimStart('/')

    fun directStreamUrl(itemId: String, mediaSourceId: String, container: String?, playSessionId: String?): String =
        buildString {
            append(url("/Videos/${sid(itemId)}/stream"))
            container?.split(',')?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { append('.').append(it) }
            append("?static=true&mediaSourceId=").append(mediaSourceId.encodeURLQueryComponent())
            append("&deviceId=").append(identity.deviceId.encodeURLQueryComponent())
            playSessionId?.let { append("&playSessionId=").append(it.encodeURLQueryComponent()) }
        }

    /** Headers the playback engines must send with media requests. Tokens never go into URLs we build. */
    /** Downloads a server file (e.g. a font attachment) with the session's credentials. */
    suspend fun downloadBytes(pathOrUrl: String): ByteArray {
        val response = http.get(absolute(pathOrUrl)) { auth() }
        if (!response.status.isSuccess()) throw AppError.Server(response.status.value)
        return response.bodyAsBytes()
    }

    fun mediaHeaders(): Map<String, String> =
        accessToken?.let { mapOf("X-Emby-Token" to it, "Authorization" to identity.authorizationHeader(it)) } ?: emptyMap()

    val images = ImageUrls(baseUrl)
}

/** How many ids a device profile's lists may send in one request (keeps the URL well under server limits). */
private const val MAX_LOCAL_IDS = 100
/** Series considered for a device profile's Next Up. */
private const val NEXT_UP_SERIES = 8
/** Episodes fetched after the last finished one when looking for the next (skips specials/watched ones). */
private const val NEXT_UP_LOOKAHEAD = 6
/** How long an arranged series (episode order) is reused before its episodes are asked for again. */
private val ARRANGEMENT_TTL = kotlin.time.Duration.parse("60s")
