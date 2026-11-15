package com.auroratv.brands

import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.Fields
import com.auroratv.jellyfin.ItemQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A studio, network or franchise shown as a tile on Home. Tiles carry the brand's *name* in Aurora's
 * own type on colours associated with it; no third-party logo artwork ships with the app. If the
 * user's server has a logo/thumb for the studio, that image (from their own server) is used instead.
 *
 * @param studios studio/network names as they appear in Jellyfin metadata (TMDb/TVDb), compared
 *   case- and punctuation-insensitively.
 */
data class Brand(
    val id: String,
    val name: String,
    val studios: List<String>,
    /** Gradient, top-left → bottom-right, as 0xAARRGGBB. */
    val colors: List<Long>,
    val textColor: Long = 0xFFFFFFFF,
    /** Wordmark style: letter spacing in em and whether it's set in capitals. */
    val tracking: Float = 0f,
    val uppercase: Boolean = false,
)

/** A brand found in the user's library: the matching Jellyfin studio ids, and a server image if any. */
data class ResolvedBrand(val brand: Brand, val studioIds: List<String>, val imageStudio: BaseItemDto?, val imageType: String?)

object BrandCatalog {
    val all: List<Brand> = listOf(
        Brand("netflix", "Netflix", listOf("Netflix", "Netflix Animation"), listOf(0xFF1A0204, 0xFF8E0B12), textColor = 0xFFFF2A35, tracking = 0.08f, uppercase = true),
        Brand("disney", "Disney", listOf("Disney+", "Disney Plus", "Walt Disney Pictures", "Walt Disney Animation Studios", "Walt Disney Productions", "Disney Channel", "Disney Junior", "Disney XD", "Disney Television Animation", "Walt Disney Television"), listOf(0xFF0B1A5C, 0xFF1F4FD8)),
        Brand("pixar", "Pixar", listOf("Pixar", "Pixar Animation Studios"), listOf(0xFF07243F, 0xFF1C7CC9), tracking = 0.06f, uppercase = true),
        Brand("marvel", "Marvel", listOf("Marvel Studios", "Marvel Entertainment", "Marvel Television", "Marvel Animation", "Marvel Enterprises"), listOf(0xFF5E0008, 0xFFE0222E), tracking = 0.04f, uppercase = true),
        Brand("starwars", "Star Wars", listOf("Lucasfilm", "Lucasfilm Ltd.", "Lucasfilm Animation"), listOf(0xFF000000, 0xFF1C1C24), textColor = 0xFFFFD84A, tracking = 0.14f, uppercase = true),
        Brand("appletv", "Apple TV", listOf("Apple TV+", "Apple TV", "Apple Studios", "Apple Original Films"), listOf(0xFF0E0E10, 0xFF3A3A40), textColor = 0xFFF2F2F7),
        Brand("paramount", "Paramount", listOf("Paramount+", "Paramount Plus", "Paramount", "Paramount Pictures", "Paramount Television", "Paramount Television Studios", "Paramount Network", "Paramount Animation", "CBS", "CBS Studios", "Showtime"), listOf(0xFF001A57, 0xFF0A5FFF)),
        Brand("hbo", "HBO", listOf("HBO", "HBO Max", "Max", "HBO Films", "HBO Documentary Films"), listOf(0xFF0B0B12, 0xFF3D1C8F), tracking = 0.12f, uppercase = true),
        Brand("prime", "Prime Video", listOf("Amazon Prime Video", "Prime Video", "Amazon Studios", "Amazon MGM Studios", "Amazon"), listOf(0xFF07131F, 0xFF0F79AF), textColor = 0xFF6BD3FF),
        Brand("hulu", "Hulu", listOf("Hulu", "Hulu Originals"), listOf(0xFF04140B, 0xFF0F6B3A), textColor = 0xFF3CF08A),
        Brand("natgeo", "National Geographic", listOf("National Geographic", "National Geographic Channel", "Nat Geo Wild", "National Geographic Documentary Films"), listOf(0xFF111111, 0xFF2B2B2B), textColor = 0xFFFFCC1F),
        Brand("dreamworks", "DreamWorks", listOf("DreamWorks Animation", "DreamWorks Pictures", "DreamWorks"), listOf(0xFF0A1E3C, 0xFF3C76C4)),
        Brand("a24", "A24", listOf("A24"), listOf(0xFF0A0A0A, 0xFF262626), tracking = 0.1f),
        Brand("crunchyroll", "Crunchyroll", listOf("Crunchyroll"), listOf(0xFF2A1205, 0xFFE8650F)),
        Brand("ghibli", "Studio Ghibli", listOf("Studio Ghibli"), listOf(0xFF0A2338, 0xFF2F7AB0)),
    )

    /** Lowercase, `+` spelled "plus", everything but letters/digits dropped: "Apple TV+" → "appletvplus". */
    fun normalize(name: String): String = name.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    private val IMAGE_PREFERENCE = listOf("Logo", "Thumb", "Primary")

    /** Brands present in [studios] (the server's studio list), in catalog order. */
    fun resolve(studios: List<BaseItemDto>): List<ResolvedBrand> {
        val byName = studios.groupBy { normalize(it.name.orEmpty()) }
        return all.mapNotNull { brand ->
            val matches = brand.studios.flatMap { byName[normalize(it)].orEmpty() }.distinctBy { it.id }
            if (matches.isEmpty()) return@mapNotNull null
            val withImage = IMAGE_PREFERENCE.firstNotNullOfOrNull { type -> matches.firstOrNull { type in it.imageTags }?.let { it to type } }
            ResolvedBrand(brand, matches.map { it.id }, withImage?.first, withImage?.second)
        }
    }
}

/** The rows of one Brand Hub, each loading independently so the page fills in as answers arrive. */
class BrandHubData(
    val newest: Deferred<List<BaseItemDto>>,
    val movies: Deferred<List<BaseItemDto>>,
    val series: Deferred<List<BaseItemDto>>,
    /** Movies + series count; a separate query so the rows never wait for it. */
    val total: Deferred<Int>,
    val created: TimeMark,
)

/**
 * Resolves brands once per server session (studio lists change rarely; refreshed every 30 min) and
 * loads Brand Hubs: lean queries (poster fields only, no per-row counts), all in parallel, cached
 * for 10 minutes, and started early when a tile is focused so the hub is usually ready on open.
 */
class BrandService(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var cached: Triple<JellyfinApi, TimeMark, List<ResolvedBrand>>? = null
    private val hubs = HashMap<String, Pair<JellyfinApi, BrandHubData>>()

    suspend fun brands(api: JellyfinApi): List<ResolvedBrand> = mutex.withLock {
        cached?.let { (a, at, list) -> if (a === api && at.elapsedNow() < 30.minutes) return@withLock list }
        val list = BrandCatalog.resolve(api.studios())
        cached = Triple(api, TimeSource.Monotonic.markNow(), list)
        list
    }

    fun find(api: JellyfinApi, id: String): ResolvedBrand? = cached?.takeIf { it.first === api }?.third?.firstOrNull { it.brand.id == id }

    /** The hub for [brand]: cached, in flight, or started now. Main thread only. */
    fun hub(api: JellyfinApi, brand: ResolvedBrand): BrandHubData {
        hubs[brand.brand.id]?.let { (a, h) ->
            val failed = listOf(h.newest, h.movies, h.series).any { it.isCancelled }
            if (a === api && h.created.elapsedNow() < 10.minutes && !failed) return h
        }
        fun query(types: List<String>, sort: String, limit: Int = 24) = ItemQuery(
            includeItemTypes = types, studioIds = brand.studioIds, sortBy = sort, sortDescending = true,
            limit = limit, fields = Fields.POSTER, totalCount = false,
        )
        val h = BrandHubData(
            newest = scope.async { api.items(query(listOf("Movie", "Series"), "PremiereDate,SortName")).items },
            movies = scope.async { api.items(query(listOf("Movie"), "CommunityRating,SortName")).items },
            series = scope.async { api.items(query(listOf("Series"), "CommunityRating,SortName")).items },
            total = scope.async { api.items(query(listOf("Movie", "Series"), "SortName", limit = 0).copy(totalCount = true, fields = "")).totalRecordCount },
            created = TimeSource.Monotonic.markNow(),
        )
        hubs[brand.brand.id] = api to h
        return h
    }

    /** Starts loading [brand]'s hub (e.g. when its tile is focused) so opening it is instant. */
    fun prefetch(api: JellyfinApi, brand: ResolvedBrand) { hub(api, brand) }
}
