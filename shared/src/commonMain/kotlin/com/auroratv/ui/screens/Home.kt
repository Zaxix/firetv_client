package com.auroratv.ui.screens

import com.auroratv.ui.components.revealOnFocus
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.LocalContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.ArtworkImage
import com.auroratv.ui.components.FrostedField
import com.auroratv.ui.components.artworkPx
import com.auroratv.ui.components.screenArtworkPx
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.ContentRail
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.components.ScreenStart
import com.auroratv.ui.components.SkeletonRail
import com.auroratv.ui.components.formatRating
import com.auroratv.ui.components.formatRuntime
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import com.auroratv.ui.theme.BlurHash
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay

/**
 * A rail whose items load lazily the first time it scrolls into view. Callers share one load (the
 * hero and the rail both want "Recently Added"), failures are retried twice with a short pause, and
 * a rail that still failed tries again the next time Home shows it.
 */
class RailState(val key: String, val title: String, val style: CardStyle, val loader: suspend () -> List<BaseItemDto>) {
    var items by mutableStateOf<List<BaseItemDto>?>(null)
    var failed by mutableStateOf(false)
    /** [items] came from the last session (Instant Home): shown at once, replaced when fresh ones arrive. */
    var stale = false
    private val lock = kotlinx.coroutines.sync.Mutex()
    suspend fun ensureLoaded(): List<BaseItemDto> = lock.withLock {
        items?.takeIf { !failed && !stale }?.let { return@withLock it }
        failed = false
        if (items?.isEmpty() == true) items = null // show the skeleton again while retrying
        repeat(RAIL_ATTEMPTS) { attempt ->
            try {
                val loaded = loader()
                items = loaded
                stale = false
                return@withLock loaded
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                com.auroratv.core.Log.w("Home", "Rail $key failed (attempt ${attempt + 1}): ${t.message}")
                if (attempt < RAIL_ATTEMPTS - 1) delay(1_500L * (attempt + 1))
            }
        }
        if (stale) return@withLock items.orEmpty() // offline: keep showing last session's row
        failed = true
        items = emptyList()
        emptyList()
    }
}

private const val RAIL_ATTEMPTS = 3

class HomeModel {
    var hero by mutableStateOf<List<BaseItemDto>>(emptyList())
    var rails by mutableStateOf<List<RailState>?>(null)
    var brands by mutableStateOf<List<com.auroratv.brands.ResolvedBrand>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var focused by mutableStateOf<BaseItemDto?>(null)
    var heroIndex by mutableIntStateOf(0)
    val listState = LazyListState()
    /** The WatchActivity version these rows reflect; a newer one refreshes the personal rows. */
    var seenWatch = com.auroratv.data.WatchActivity.version.value
    /** Perf log: when this Home was opened, and whether an earlier Home was opened in this session. */
    val createdAt = kotlin.time.TimeSource.Monotonic.markNow()
    var isRevisit = false
    var revisitLogged = false
    var apiAtOpen: Int? = null
    /** When the rows were last loaded from the server (null: not yet). */
    var loadedAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
    /** Started from the rows Home showed earlier in this session (see HomeSnapshot). */
    var seeded = false

    fun seedFrom(s: HomeSnapshot) {
        hero = s.hero; rails = s.rails; brands = s.brands; seenWatch = s.seenWatch; loadedAt = s.loadedAt; seeded = true
    }

    fun snapshot(api: com.auroratv.jellyfin.JellyfinApi): HomeSnapshot? {
        val r = rails ?: return null
        val at = loadedAt ?: return null
        return HomeSnapshot(api, hero, r, brands, seenWatch, at)
    }
}

/**
 * Home's rows as last shown for a profile, kept in memory for this session (AppContainer.homeMemory):
 * opening Home again from the side menu shows them at once, and only asks the server again once
 * they're [HOME_FRESH_MS] old. The personal rows still follow what's watched (WatchActivity).
 */
class HomeSnapshot(
    /** The signed-in connection the rows load through; a new sign-in starts afresh. */
    val api: com.auroratv.jellyfin.JellyfinApi,
    val hero: List<BaseItemDto>,
    val rails: List<RailState>,
    val brands: List<com.auroratv.brands.ResolvedBrand>,
    val seenWatch: Int,
    val loadedAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark,
)

private var homesOpened = 0

/** API requests made so far (from Platform.requestStats), or null where it isn't counted. */
private fun apiRequestsSoFar(container: com.auroratv.ui.AppContainer): Int? =
    container.platform.requestStats()?.let { Regex("""api=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Continue Watching, Next Up and the recommendation rows: the ones that change as this profile watches. */
private fun isPersonalRail(key: String) = key == "resume" || key == "nextup" || key == "foryou" || key.startsWith("because-")

/**
 * Home's rows in order: what you're in the middle of, what's picked for you, the libraries, a second
 * "Because You Watched", then favourites. Recommendations sit near the top but never above what
 * you're already watching.
 */
private fun arrangeRails(
    resume: List<BaseItemDto>,
    nextUp: List<BaseItemDto>,
    recommendations: List<RailState>,
    libraries: List<RailState>,
    favorites: RailState?,
): List<RailState> = buildList {
    if (resume.isNotEmpty()) add(RailState("resume", "Continue Watching", CardStyle.Landscape) { resume }.also { it.items = resume })
    if (nextUp.isNotEmpty()) add(RailState("nextup", "Next Up", CardStyle.Landscape) { nextUp }.also { it.items = nextUp })
    recommendations.filter { it.key != "because-1" }.forEach(::add)
    addAll(libraries)
    recommendations.firstOrNull { it.key == "because-1" }?.let(::add)
    favorites?.let(::add)
}

/**
 * The recommendation rails for this profile. Titles are known from the history right away; the
 * rows themselves are worked out once (shared by all three rails) when the first of them comes on
 * screen, and reused until the history changes. A row with too little to show stays hidden.
 */
private fun recommendationRails(
    container: com.auroratv.ui.AppContainer,
    api: com.auroratv.jellyfin.JellyfinApi,
    profileKey: String,
    plan: com.auroratv.recommend.RecommendationService.Plan?,
): List<RailState> {
    if (plan == null || plan.seeds.isEmpty()) return emptyList()
    fun rail(key: String, title: String) = RailState(key, title, CardStyle.Poster) {
        container.recommendations.rows(api, profileKey, plan).await()?.firstOrNull { it.key == key }?.items.orEmpty()
    }
    return buildList {
        plan.seeds.getOrNull(0)?.let { add(rail("because-0", "Because You Watched ${it.name}")) }
        if (plan.titles.size >= 2) add(rail("foryou", "Recommended For You"))
        plan.seeds.getOrNull(1)?.let { add(rail("because-1", "Because You Watched ${it.name}")) }
    }
}

/** Swaps in new personal rows, keeping row objects (and focus) where nothing changed. */
private fun mergeRails(old: List<RailState>, fresh: List<RailState>): List<RailState> {
    val byKey = old.associateBy { it.key }
    return fresh.map { f ->
        val o = byKey[f.key] ?: return@map f
        when {
            !isPersonalRail(f.key) -> o
            o.title == f.title && f.items != null && o.items == f.items -> o
            else -> f.also { if (it.items == null) o.items?.takeIf { prev -> prev.isNotEmpty() }?.let { prev -> it.items = prev; it.stale = true } }
        }
    }
}

@Composable
fun HomeScreen() {
    val container = LocalContainer.current
    val session = LocalSession.current
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val profileKey = remember(session) { session.profile.key }
    val model = entry.model("home") {
        HomeModel().also { m ->
            m.isRevisit = homesOpened++ > 0; m.apiAtOpen = apiRequestsSoFar(container)
            container.homeMemory[profileKey]?.takeIf { it.api === session.api }?.let(m::seedFrom)
        }
    }
    // Leaving Home (to another section, a detail page, the player): remember what it showed.
    androidx.compose.runtime.DisposableEffect(model) {
        onDispose { model.snapshot(session.api)?.let { container.rememberHome(profileKey, it) } }
    }
    val api = session.api
    val settings = container.settings.current
    val heroFocus = remember { FocusRequester() }
    var reloadKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val homeCache = remember(session) { HomeCache(container.platform.files, session.server.id, session.user.id + (session.local?.let { "-p${it.id}" } ?: "")) }
    // Moving onto the featured title or a Continue Watching / Next Up card gets Play ready in the
    // background (after a short pause, so scrolling past cards costs nothing).
    val prewarmRequests = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    LaunchedEffect(Unit) {
        prewarmRequests.collectLatest { id -> if (id != null) { delay(PREWARM_DELAY_MS); container.prewarmPlayback(api, id) } }
    }
    // A show on the banner (often its newest episode) plays what its own page's Play would: the
    // episode in progress or next in its order, never the newest one just because it was added last
    // (that jumped viewers far ahead, and the server's Next Up then followed the jump).
    val bannerTargets = remember(session) { HashMap<String, BaseItemDto>() }
    val bannerFocus = remember { kotlinx.coroutines.flow.MutableStateFlow<BaseItemDto?>(null) }
    LaunchedEffect(Unit) {
        bannerFocus.collectLatest { item ->
            item ?: return@collectLatest
            val series = bannerSeries(item)
            if (series == null) { prewarmRequests.value = item.id.takeUnless { item.isSeries }; return@collectLatest }
            delay(PREWARM_DELAY_MS)
            val target = runCatching { api.seriesPlayTarget(series) }.getOrNull() ?: return@collectLatest
            bannerTargets[series] = target
            prewarmRequests.value = target.id
        }
    }
    fun playBanner(item: BaseItemDto) {
        val series = bannerSeries(item) ?: return navigator.push(Route.Player(item.id))
        scope.launch {
            val target = bannerTargets[series] ?: runCatching { api.seriesPlayTarget(series) }.getOrNull()
            if (target != null) navigator.push(Route.Player(target.id, false, target)) else navigator.push(Route.Detail(series))
        }
    }

    LaunchedEffect(reloadKey) {
        if (model.rails != null && reloadKey == 0) {
            // Back on this screen (from a detail page or the player): nothing to load. Opened again
            // from the side menu: the rows from memory are shown, and refreshed only once they're old.
            val age = model.loadedAt?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE
            if (!model.seeded || age < HOME_FRESH_MS) {
                if (model.seeded) com.auroratv.core.Log.i("Home", "Rows from memory (${age / 1000} s old), no requests")
                return@LaunchedEffect
            }
            com.auroratv.core.Log.i("Home", "Rows from memory (${age / 1000} s old), refreshing them")
        }
        model.error = null
        // Instant Home: last session's rows from disk, read alongside (not before) the server requests
        // and shown only if they win the race.
        val savedJob = if (model.rails == null) launch {
            val readStart = kotlin.time.TimeSource.Monotonic.markNow()
            val saved = homeCache.read()
            com.auroratv.core.Log.i("Perf", "Saved Home ${if (saved == null) "not found" else "read"} in ${readStart.elapsedNow().inWholeMilliseconds} ms")
            if (saved != null && model.rails == null) {
                model.hero = saved.hero
                model.rails = saved.rails
                container.logHomeReady("from the saved copy")
            }
        } else null
        val loadStart = kotlin.time.TimeSource.Monotonic.markNow()
        try {
            coroutineScope {
                // Brand tiles load on their own; Home never waits for them.
                if (settings.showBrandTiles) launch { model.brands = runCatching { container.brands.brands(api) }.getOrDefault(emptyList()) }
                val views = async { api.userViews() }
                val resume = async { runCatching { api.resumeItems() }.getOrDefault(emptyList()) }
                val nextUp = async { runCatching { api.nextUp() }.getOrDefault(emptyList()) }
                // This profile's recent history, for the recommendation rows: from memory while nothing
                // has been watched since (no request), else one small request shared by every caller.
                val watchNow = com.auroratv.data.WatchActivity.version.value
                val peek = container.recommendations.peekPlan(profileKey, watchNow)
                val recPlan = async {
                    peek?.takeIf { it.fresh }?.plan ?: runCatching { container.recommendations.planFor(api, profileKey, watchNow, resume.await()) }
                        .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it; com.auroratv.core.Log.w("Recommend", "No history: ${it.message}") }.getOrNull()
                }
                val libraryViews = views.await().filter { it.collectionType in setOf("movies", "tvshows", "homevideos", "musicvideos", null) }
                val latestRails = libraryViews.associate { view ->
                    view.id to RailState("latest-${view.id}", "Recently Added in ${view.name}", CardStyle.Poster) { api.latest(view.id, limit = 24) }
                }
                // The featured titles come from the same "Recently Added" loads as the rails (one request
                // per library, not two). Rows no longer wait for them: they appear as soon as Continue
                // Watching / Next Up are in (each library row fills itself), and the banner joins
                // when its loads finish. Before, every server row waited for all of these (up to 4 s)
                // and then for the history (up to 2.5 s), the "5 seconds" on a slow server.
                val heroSources = libraryViews.filter { it.collectionType == "movies" || it.collectionType == "tvshows" }.mapNotNull { latestRails[it.id] }
                val heroLoads = heroSources.map { rail -> async { rail.ensureLoaded() } }

                val favorites = RailState("favorites", "Favorites", CardStyle.Poster) {
                    api.items(com.auroratv.jellyfin.ItemQuery(isFavorite = true, includeItemTypes = listOf("Movie", "Series"), sortBy = "DatePlayed,SortName", sortDescending = true, limit = 24)).items
                }
                val libraryRails = libraryViews.map { view -> latestRails.getValue(view.id) }
                // The history is sent alongside the others. If it's back by the time the other rows
                // are (or within REC_WAIT_MS of Home opening), its rows are placed at once; otherwise
                // the rows show without it and the recommendation rows are merged in when it arrives.
                // The budget counts from the start, so it never adds to a slow server's wait.
                val budget = (REC_WAIT_MS - loadStart.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
                // A plan kept from earlier (even an old one) places the rows at once; a newer one
                // replaces it in place if the history has changed.
                val plan = peek?.plan ?: if (recPlan.isCompleted) recPlan.await() else kotlinx.coroutines.withTimeoutOrNull(budget) { recPlan.await() }
                val rails = arrangeRails(resume.await(), nextUp.await(), recommendationRails(container, api, profileKey, plan), libraryRails, favorites)
                if (plan == null || peek?.fresh == false) launch {
                    val late = recPlan.await() ?: return@launch
                    if (plan != null && late.signature == plan.signature && late.seeds.map { it.id } == plan.seeds.map { it.id }) return@launch
                    val now = model.rails ?: return@launch
                    model.rails = mergeRails(now, arrangeRails(resume.await(), nextUp.await(), recommendationRails(container, api, profileKey, late),
                        now.filter { it.key.startsWith("latest-") }, now.firstOrNull { it.key == "favorites" }))
                }
                // Rows that haven't loaded yet keep showing last session's items until they do.
                val shown = model.rails?.associateBy { it.key }.orEmpty()
                rails.forEach { r -> if (r.items == null) shown[r.key]?.items?.takeIf { it.isNotEmpty() }?.let { r.items = it; r.stale = true } }
                model.rails = rails
                model.loadedAt = kotlin.time.TimeSource.Monotonic.markNow()
                container.logHomeReady()
                // The banner: from the same loads, without holding anything else up.
                launch {
                    val pool = kotlinx.coroutines.withTimeoutOrNull(HERO_WAIT_MS) { heroLoads.awaitAll() }?.flatten()
                        ?: heroSources.flatMap { it.items.orEmpty() }
                    val freshHero = pool.filter { it.backdropImageTags.isNotEmpty() }.distinctBy { it.seriesId ?: it.id }.shuffled().take(6)
                    // Keep a saved banner that's already on screen rather than swapping it under the user.
                    if (model.hero.isEmpty()) model.hero = freshHero
                    // A library that was too slow for the banner still joins it once its row has loaded.
                    if (model.hero.size < 6) {
                        val late = heroLoads.awaitAll().flatten().filter { it.backdropImageTags.isNotEmpty() }
                        val extra = late.filter { l -> model.hero.none { (it.seriesId ?: it.id) == (l.seriesId ?: l.id) } }.distinctBy { it.seriesId ?: it.id }.shuffled()
                        if (extra.isNotEmpty()) model.hero = (model.hero + extra).take(6)
                    }
                    heroLoads.awaitAll()
                    delay(1_500)
                    homeCache.write(if (freshHero.isNotEmpty()) freshHero else model.hero, rails)
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.onError(t.toAppError())
            savedJob?.join() // offline: the saved Home (if any) is better than an error screen
            model.error = t.toAppError().userMessage
        }
    }

    // Back from watching something (or after marking it watched): refresh Continue Watching, Next Up
    // and the recommendations in place, keeping every row (and the focused card) that didn't change.
    val watchVersion by com.auroratv.data.WatchActivity.version.collectAsState()
    LaunchedEffect(watchVersion) {
        if (watchVersion == model.seenWatch) return@LaunchedEffect
        model.seenWatch = watchVersion
        if (model.rails == null) return@LaunchedEffect
        delay(400) // let the server record the stop first
        runCatching {
            coroutineScope {
                val resume = async { api.resumeItems() }
                val nextUp = async { api.nextUp() }
                val plan = runCatching { container.recommendations.planFor(api, profileKey, watchVersion, resume.await()) }
                    .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }.getOrNull()
                val current = model.rails ?: return@coroutineScope
                val fresh = arrangeRails(resume.await(), nextUp.await(), recommendationRails(container, api, profileKey, plan),
                    current.filter { it.key.startsWith("latest-") }, current.firstOrNull { it.key == "favorites" })
                model.rails = mergeRails(current, fresh)
            }
        }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it; com.auroratv.core.Log.w("Home", "Refresh after watching failed: ${it.message}") }
    }

    // Perf log (CI reads it): what Home cost the server in its first 10 s. (When the rows are placed
    // is logged by AppContainer.logHomeReady.)
    LaunchedEffect(Unit) {
        delay(HOME_COST_AFTER_MS)
        container.platform.requestStats()?.let { com.auroratv.core.Log.i("Perf", "Server requests in the first ${HOME_COST_AFTER_MS / 1000} s: $it") }
    }
    // Perf log (CI reads it): Home opened again in this session (from the side menu): how soon its
    // rows are on screen, and how many server requests that costs in its first 10 s.
    LaunchedEffect(model, model.rails != null) {
        if (!model.isRevisit || model.rails == null || model.revisitLogged) return@LaunchedEffect
        model.revisitLogged = true
        val before = model.apiAtOpen
        com.auroratv.core.Log.i("Perf", "Home opened again: rows on screen in ${model.createdAt.elapsedNow().inWholeMilliseconds} ms")
        delay(HOME_COST_AFTER_MS)
        val after = apiRequestsSoFar(container)
        if (before != null && after != null) com.auroratv.core.Log.i("Perf", "Home opened again: ${after - before} API request(s) in its first ${HOME_COST_AFTER_MS / 1000} s")
    }
    val rails = model.rails
    ScreenFocus(entry, navigator, heroFocus, ready = rails != null)

    if (model.error != null && rails == null) {
        ErrorState(model.error!!, onRetry = { reloadKey++ })
        return
    }

    val heroItem = model.hero.getOrNull(model.heroIndex % model.hero.size.coerceAtLeast(1))

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.80f
        if (settings.showBackdrops) {
            // Reads the focused card itself, so moving between cards redraws only the background,
            // not the whole of Home.
            HomeBackdrop(heroItem, model, settings.frostedBackground, settings.artworkAccents)
        }
        LazyColumn(
            state = model.listState,
            contentPadding = PaddingValues(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (settings.showHeroCarousel) {
                item(key = "hero") {
                    Hero(model, heroItem, heroFocus, heroHeight, onPlay = ::playBanner,
                        onInfo = { bannerSeries(it)?.let { s -> navigator.push(Route.Detail(s)) } ?: navigator.push(Route.Detail(it.id, it)) },
                        onFocusItem = { bannerFocus.value = it })
                }
            }
            if (settings.showBrandTiles && model.brands.isNotEmpty()) {
                item(key = "brands") {
                    BrandRow(model.brands) { b -> navigator.push(Route.Brand(b.brand.id, b.brand.name)) }
                }
            }
            if (rails == null) {
                items(3) { SkeletonRail(if (it < 1) CardStyle.Landscape else CardStyle.Poster) }
            } else {
                items(rails, key = { it.key }) { rail ->
                    LaunchedEffect(rail) { rail.ensureLoaded() }
                    val items = rail.items
                    when {
                        items == null -> SkeletonRail(rail.style)
                        items.isEmpty() && rail.failed -> RailRetry(rail.title) { scope.launch { rail.ensureLoaded() } }
                        items.isEmpty() -> Unit
                        else -> ContentRail(
                            title = rail.title,
                            items = items,
                            images = api.images,
                            style = rail.style,
                            railKey = rail.key,
                            onItemClick = { item -> openItem(navigator, item) },
                            onItemFocused = {
                                model.focused = it
                                if (rail.key == "resume" || rail.key == "nextup") prewarmRequests.value = it.id
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val HERO_WAIT_MS = 4_000L
private const val HOME_COST_AFTER_MS = 10_000L
private const val REC_WAIT_MS = 600L
/** Home opened again within this long of its last load shows the same rows without asking the server. */
private const val HOME_FRESH_MS = 5 * 60_000L
private const val PREWARM_DELAY_MS = 700L
private const val SAVED_PER_ROW = 12

/**
 * The last Home this user saw on this device (banner titles and each row's items), so the next
 * launch can show it before the server has answered. Artwork URLs are identical, so posters come
 * straight from the image cache too.
 */
private class HomeCache(private val files: com.auroratv.platform.FileStore, serverId: String, userId: String) {
    private val name = "home-cache-$serverId-$userId.json"
    private val json = kotlinx.serialization.json.Json(com.auroratv.core.AuroraJson) { encodeDefaults = false }

    private fun slim(item: BaseItemDto, keepText: Boolean): BaseItemDto {
        val usedTags = buildSet {
            addAll(item.imageTags.values); addAll(item.backdropImageTags); addAll(item.parentBackdropImageTags)
            item.seriesPrimaryImageTag?.let(::add); item.parentThumbImageTag?.let(::add)
        }
        return item.copy(
            overview = item.overview.takeIf { keepText },
            genres = if (keepText) item.genres else emptyList(),
            taglines = emptyList(), providerIds = emptyMap(), studios = emptyList(), people = emptyList(),
            mediaSources = emptyList(), mediaStreams = emptyList(), chapters = emptyList(), trickplay = null,
            imageBlurHashes = item.imageBlurHashes
                .filterKeys { it == "Primary" || it == "Backdrop" || it == "Thumb" }
                .mapValues { (_, byTag) -> byTag.filterKeys { it in usedTags } }
                .filterValues { it.isNotEmpty() },
        )
    }

    @kotlinx.serialization.Serializable
    private data class Saved(val hero: List<BaseItemDto>, val rails: List<SavedRail>)

    @kotlinx.serialization.Serializable
    private data class SavedRail(val key: String, val title: String, val style: String, val items: List<BaseItemDto>)

    // On the I/O pool: at launch the CPU pool is busy (start-up work, image decoding) and a read
    // queued there waited ~0.4 s for a thread, while the read itself takes a few milliseconds.
    suspend fun read(): SavedHome? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val t0 = kotlin.time.TimeSource.Monotonic.markNow()
            val text = files.read(name) ?: return@runCatching null
            val t1 = t0.elapsedNow().inWholeMilliseconds
            val saved = json.decodeFromString(Saved.serializer(), text)
            com.auroratv.core.Log.i("Perf", "Saved Home: ${text.length / 1024} KB, file $t1 ms, decode ${t0.elapsedNow().inWholeMilliseconds - t1} ms")
            SavedHome(
                hero = saved.hero,
                rails = saved.rails.mapNotNull { r ->
                    val style = CardStyle.entries.firstOrNull { it.name == r.style } ?: return@mapNotNull null
                    // Waits for the fresh rows (which replace these) instead of loading anything itself.
                    RailState(r.key, r.title, style) { kotlinx.coroutines.awaitCancellation() }.also { it.items = r.items; it.stale = true }
                },
            )
        }.onFailure { com.auroratv.core.Log.w("Home", "Saved Home unreadable: ${it.message}") }.getOrNull()
    }

    suspend fun write(hero: List<BaseItemDto>, rails: List<RailState>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching {
            // Only what the first screens draw: a dozen cards per row, card fields only (the banner
            // keeps its text), so the copy is small and quick to read back.
            val saved = Saved(
                hero.map { slim(it, keepText = true) },
                rails.mapNotNull { r ->
                    r.items?.takeIf { it.isNotEmpty() && !r.failed }?.let { items -> SavedRail(r.key, r.title, r.style.name, items.take(SAVED_PER_ROW).map { slim(it, keepText = false) }) }
                },
            )
            files.write(name, json.encodeToString(Saved.serializer(), saved))
        }.onFailure { com.auroratv.core.Log.w("Home", "Couldn't save Home: ${it.message}") }
    }
}

private class SavedHome(val hero: List<BaseItemDto>, val rails: List<RailState>)

/** A rail that couldn't load (server busy or offline): say so and offer another go instead of vanishing. */
@Composable
private fun RailRetry(title: String, onRetry: () -> Unit) {
    Column(Modifier.padding(start = ScreenStart, end = ScreenPadding)) {
        Text(title, style = AuroraType.section, color = Aurora.colors.onBackground)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Couldn't load this row.", style = AuroraType.body, color = Aurora.colors.onSurfaceMuted)
            AuroraButton("Try again", onRetry)
        }
    }
}

/** The show a banner item belongs to (the banner shows shows, not single episodes), or null for a film. */
private fun bannerSeries(item: BaseItemDto): String? = when {
    item.isSeries -> item.id
    item.isEpisode || item.isSeason -> item.seriesId
    else -> null
}

/** Movies/episodes open their detail page; folders open as a library. */
fun openItem(navigator: com.auroratv.ui.nav.Navigator, item: BaseItemDto) {
    when {
        item.isBrowsableFolder ->
            navigator.push(Route.Library(item.id, item.name ?: "", emptyList(), item.collectionType))
        else -> navigator.push(Route.Detail(item.id, item))
    }
}

/**
 * Home's background. The featured banner always shows its artwork; with a card focused it's either
 * that title's backdrop or (default) frosted glass in the colours of its poster.
 */
@Composable
private fun HomeBackdrop(hero: BaseItemDto?, model: HomeModel, frosted: Boolean, accents: Boolean) {
    val api = LocalSession.current.api
    val focused = model.focused
    val backdropItem = focused ?: hero
    val accentTarget = if (accents) backdropItem?.let { item ->
        val tag = item.backdropImageTags.firstOrNull() ?: item.parentBackdropImageTags.firstOrNull()
        BlurHash.accentFrom(tag?.let { item.imageBlurHashes["Backdrop"]?.get(it) }, Aurora.colors.isDark)
    } else null
    val accent by animateColorAsState(accentTarget ?: Aurora.colors.accent, tween(Aurora.motion.duration(600)))
    val colors = Aurora.colors
    val backdropPx = screenArtworkPx()
    val item = focused ?: hero
    val frostedNow = frosted && focused != null
    // Frosted fields are keyed by colour (the BlurHash), so moving between cards of the same title or
    // artwork doesn't fade at all.
    val frostHash = if (frostedNow) focused?.let { (api.images.poster(it, 1) ?: api.images.landscape(it, 1))?.blurHash } else null
    val key = if (frostedNow) "frost:${frostHash ?: focused?.id}" else "art:${item?.id}"
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = key, animationSpec = tween(Aurora.motion.duration(if (frostedNow) 350 else 500))) { k ->
            if (k.startsWith("frost:")) {
                FrostedField(k.removePrefix("frost:"))
            } else {
                // Captured when this layer appeared, so the outgoing image stays while it fades out.
                val target = remember { item?.takeIf { k == "art:${it.id}" } }
                ArtworkImage(target?.let { api.images.backdrop(it, backdropPx) }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
            }
        }
        // The frosted field carries its own shading; artwork needs scrims to keep text readable.
        if (!frostedNow) ArtworkScrims(accent)
    }
}

@Composable
private fun ArtworkScrims(accent: Color) {
    val colors = Aurora.colors
    Box(Modifier.fillMaxSize()) {
        // Left and bottom scrims keep text readable; the accent tints the shadow side like ambient light.
        // tvOS keeps artwork bright; only the lower-left, where text sits, is shaded.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to colors.background.copy(alpha = 0.85f), 0.45f to colors.background.copy(alpha = 0.25f), 0.7f to accent.copy(alpha = 0.06f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 0.78f to colors.background.copy(alpha = 0.9f), 1f to colors.background)))
    }
}

@Composable
private fun Hero(
    model: HomeModel,
    item: BaseItemDto?,
    focus: FocusRequester,
    height: androidx.compose.ui.unit.Dp,
    onPlay: (BaseItemDto) -> Unit,
    onInfo: (BaseItemDto) -> Unit,
    onFocusItem: (BaseItemDto) -> Unit = {},
) {
    val colors = Aurora.colors
    val api = LocalSession.current.api
    var heroFocused by remember { mutableStateOf(false) }

    LaunchedEffect(heroFocused, item?.id) { if (heroFocused && item != null) onFocusItem(item) }
    // Coming back up to the featured title shows the whole banner, not just enough to see its buttons:
    // the banner is requested into view together with the focused button (revealOnFocus below), and
    // the page settles at the very top once focus's own scroll has finished (it used to race it).
    LaunchedEffect(heroFocused) {
        if (!heroFocused) return@LaunchedEffect
        delay(450)
        val list = model.listState
        if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) list.animateScrollToItem(0)
    }
    // Auto-advance the featured carousel, pausing while the user is interacting with it.
    LaunchedEffect(model.hero.size, heroFocused) {
        if (model.hero.size < 2) return@LaunchedEffect
        while (true) {
            delay(9_000)
            if (!heroFocused) model.heroIndex = (model.heroIndex + 1) % model.hero.size
        }
    }
    LaunchedEffect(heroFocused) { if (heroFocused) model.focused = null }

    Column(
        Modifier.fillMaxWidth().height(height).revealOnFocus().padding(start = ScreenStart, end = ScreenPadding, bottom = 36.dp)
            .onFocusChanged { heroFocused = it.hasFocus },
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (item == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AuroraButton("Browse", { }, focusRequester = focus)
            }
            return@Column
        }
        val logo = api.images.logo(item, artworkPx(480.dp, 1f))
        Box(Modifier.height(170.dp).widthIn(max = 560.dp), contentAlignment = Alignment.BottomStart) {
            if (logo != null) ArtworkImage(logo, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, showPlaceholder = false, colorFilter = com.auroratv.ui.components.logoColorFilter())
            else Text(item.seriesName ?: item.name ?: "", style = AuroraType.hero, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        val meta = listOfNotNull(
            item.productionYear?.toString(),
            item.officialRating,
            formatRuntime(item.runtimeMs),
            formatRating(item.communityRating)?.let { "★ $it" },
            item.genres.take(2).joinToString(", ").ifBlank { null },
        ).joinToString("  ·  ")
        Text(meta, style = AuroraType.bodyStrong, color = colors.onSurfaceMuted)
        Spacer(Modifier.height(12.dp))
        Text(
            item.overview ?: "",
            style = AuroraType.body,
            color = colors.onSurfaceMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Right on More Info pages to the next featured title (wrapping), like swiping on tvOS.
            // Left on Play goes back a title, except on the first one, where it opens the sidebar.
            val count = model.hero.size.coerceAtLeast(1)
            AuroraButton("Play", { onPlay(item) }, icon = Icons.Filled.PlayArrow, primary = true, focusRequester = focus, focusKey = "hero/play",
                modifier = Modifier.onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && model.hero.size > 1 && model.heroIndex % count != 0) {
                        model.heroIndex = (model.heroIndex - 1 + count) % count; true
                    } else false
                })
            AuroraButton("More Info", { onInfo(item) }, icon = Icons.Filled.Info, focusKey = "hero/info",
                modifier = Modifier.onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight && model.hero.size > 1) { model.heroIndex = (model.heroIndex + 1) % count; true } else false
                })
            Spacer(Modifier.width(18.dp))
            model.hero.forEachIndexed { i, _ ->
                Box(
                    Modifier.padding(horizontal = 3.dp).size(if (i == model.heroIndex % model.hero.size) 9.dp else 7.dp).clip(CircleShape)
                        .background(if (i == model.heroIndex % model.hero.size) colors.onBackground else colors.onSurfaceFaint.copy(alpha = 0.6f))
                )
            }
        }
    }
}
