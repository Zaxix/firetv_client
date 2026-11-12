package com.auroratv.jellyfin

import com.auroratv.AppInfo
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

/** Shared HTTP client configured for Jellyfin. The engine comes from the classpath (OkHttp) or a test. */
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
    return base?.config(config) ?: HttpClient(config)
}

/** Common item fields requested for cards and detail pages. */
object Fields {
    const val CARD = "PrimaryImageAspectRatio,Overview,Genres,ParentId,SeriesPrimaryImageTag,ChildCount,RecursiveItemCount"
    const val DETAIL = "Overview,Genres,Studios,People,Taglines,MediaSources,MediaStreams,Chapters,PrimaryImageAspectRatio,ChildCount,RecursiveItemCount,OriginalTitle,ParentId"
    const val IMAGE_TYPES = "Primary,Backdrop,Logo,Thumb"
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
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                if (authCall) AppError.InvalidCredentials() else AppError.SessionExpired()
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

    suspend fun items(query: ItemQuery): QueryResult<BaseItemDto> =
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
            parameter("fields", query.fields)
            parameter("imageTypeLimit", 1)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
            parameter("enableTotalRecordCount", true)
        }.read()

    suspend fun item(itemId: String): BaseItemDto =
        http.get(url("/Items/$itemId")) {
            auth(); parameter("userId", requireUser())
        }.read()

    suspend fun resumeItems(limit: Int = 24): List<BaseItemDto> =
        http.get(url("/UserItems/Resume")) {
            auth()
            parameter("userId", requireUser())
            parameter("limit", limit)
            parameter("mediaTypes", "Video")
            parameter("fields", Fields.CARD)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
            parameter("imageTypeLimit", 1)
        }.read<QueryResult<BaseItemDto>>().items

    suspend fun nextUp(limit: Int = 24, seriesId: String? = null): List<BaseItemDto> =
        http.get(url("/Shows/NextUp")) {
            auth()
            parameter("userId", requireUser())
            parameter("limit", limit)
            seriesId?.let { parameter("seriesId", it) }
            parameter("fields", Fields.CARD)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
            parameter("imageTypeLimit", 1)
            parameter("enableResumable", false)
        }.read<QueryResult<BaseItemDto>>().items

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
        }.read()

    suspend fun seasons(seriesId: String): List<BaseItemDto> =
        http.get(url("/Shows/$seriesId/Seasons")) {
            auth(); parameter("userId", requireUser()); parameter("fields", Fields.CARD)
        }.read<QueryResult<BaseItemDto>>().items

    suspend fun episodes(seriesId: String, seasonId: String? = null, startItemId: String? = null, limit: Int? = null): List<BaseItemDto> =
        http.get(url("/Shows/$seriesId/Episodes")) {
            auth()
            parameter("userId", requireUser())
            seasonId?.let { parameter("seasonId", it) }
            startItemId?.let { parameter("startItemId", it) }
            limit?.let { parameter("limit", it) }
            parameter("fields", Fields.CARD)
            parameter("enableImageTypes", Fields.IMAGE_TYPES)
        }.read<QueryResult<BaseItemDto>>().items

    suspend fun similar(itemId: String, limit: Int = 16): List<BaseItemDto> =
        http.get(url("/Items/$itemId/Similar")) {
            auth(); parameter("userId", requireUser()); parameter("limit", limit); parameter("fields", Fields.CARD)
        }.read<QueryResult<BaseItemDto>>().items

    suspend fun setFavorite(itemId: String, favorite: Boolean) {
        val path = url("/UserFavoriteItems/$itemId")
        val response = if (favorite) http.post(path) { auth(); parameter("userId", requireUser()) }
        else http.delete(path) { auth(); parameter("userId", requireUser()) }
        response.ensureOk()
    }

    suspend fun setPlayed(itemId: String, played: Boolean) {
        val path = url("/UserPlayedItems/$itemId")
        val response = if (played) http.post(path) { auth(); parameter("userId", requireUser()) }
        else http.delete(path) { auth(); parameter("userId", requireUser()) }
        response.ensureOk()
    }

    // ---- Playback ------------------------------------------------------------------------------

    suspend fun playbackInfo(itemId: String, request: PlaybackInfoRequest): PlaybackInfoResponse =
        http.post(url("/Items/$itemId/PlaybackInfo")) {
            auth()
            parameter("userId", requireUser())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.read()

    suspend fun mediaSegments(itemId: String): List<MediaSegmentDto> = try {
        http.get(url("/MediaSegments/$itemId")) {
            auth()
            parameter("includeSegmentTypes", "Intro")
            parameter("includeSegmentTypes", "Outro")
            parameter("includeSegmentTypes", "Recap")
        }.read<QueryResult<MediaSegmentDto>>().items
    } catch (e: AppError.NotFound) {
        emptyList() // server older than 10.10 or no segment provider
    }

    suspend fun reportStart(report: PlaybackReport) = http.post(url("/Sessions/Playing")) {
        auth(); contentType(ContentType.Application.Json); setBody(report)
    }.ensureOk()

    suspend fun reportProgress(report: PlaybackReport) = http.post(url("/Sessions/Playing/Progress")) {
        auth(); contentType(ContentType.Application.Json); setBody(report)
    }.ensureOk()

    suspend fun reportStopped(report: PlaybackReport) = http.post(url("/Sessions/Playing/Stopped")) {
        auth(); contentType(ContentType.Application.Json); setBody(report)
    }.ensureOk()

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
            append(url("/Videos/$itemId/stream"))
            container?.split(',')?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { append('.').append(it) }
            append("?static=true&mediaSourceId=").append(mediaSourceId.encodeURLQueryComponent())
            append("&deviceId=").append(identity.deviceId.encodeURLQueryComponent())
            playSessionId?.let { append("&playSessionId=").append(it.encodeURLQueryComponent()) }
        }

    /** Headers the playback engines must send with media requests. Tokens never go into URLs we build. */
    fun mediaHeaders(): Map<String, String> =
        accessToken?.let { mapOf("X-Emby-Token" to it, "Authorization" to identity.authorizationHeader(it)) } ?: emptyMap()

    val images = ImageUrls(baseUrl)
}
