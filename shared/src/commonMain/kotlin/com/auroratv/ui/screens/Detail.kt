package com.auroratv.ui.screens

import com.auroratv.ui.nav.focusRow

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.Artwork
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.LocalContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.ArtworkImage
import com.auroratv.ui.components.RowScroll
import com.auroratv.ui.components.revealOnFocus
import com.auroratv.ui.components.artworkPx
import com.auroratv.ui.components.screenArtworkPx
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.AuroraChip
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.ContentRail
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.components.MediaCard
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.components.ScreenStart
import com.auroratv.ui.components.formatRating
import com.auroratv.ui.components.formatRuntime
import com.auroratv.ui.components.formatTime
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DetailModel(preview: BaseItemDto?) {
    var item by mutableStateOf(preview)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var seasons by mutableStateOf<List<BaseItemDto>>(emptyList())
    var selectedSeason by mutableStateOf<String?>(null)
    var episodes by mutableStateOf<List<BaseItemDto>?>(null)
    var nextUp by mutableStateOf<BaseItemDto?>(null)
    var similar by mutableStateOf<List<BaseItemDto>>(emptyList())
    /** Episodes of this series that haven't aired, with their dates (see AirDateService). */
    var upcoming by mutableStateOf<List<com.auroratv.data.UpcomingEpisode>>(emptyList())
    var ratings by mutableStateOf<com.auroratv.ratings.ExternalRatings?>(preview?.let { com.auroratv.ratings.RatingsService.fromJellyfin(it) })
    var reviews by mutableStateOf<List<com.auroratv.ratings.Review>>(emptyList())
    var openReview by mutableStateOf<com.auroratv.ratings.Review?>(null)
    var ratingsFor: String? = null
    val reviewsFocus = FocusRequester()
    val listState = LazyListState()
    /** For the Perf log: when the page was opened, and how many API requests had been made by then. */
    val openedAt = kotlin.time.TimeSource.Monotonic.markNow()
    var apiAtOpen: Int? = null
    var loads = 0
    /** A load is under way (the item shown may be from before the last playback). */
    var loading = false
    /** Episodes of each season already shown on this page (switching back is instant, no request). */
    val seasonEpisodes = HashMap<String, List<BaseItemDto>>()
    /** "More Like This" for this item, kept when the page reloads (it doesn't change with watching). */
    var similarFor: String? = null
}

@Composable
fun DetailScreen(route: Route.Detail) {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("detail") { DetailModel(route.preview).also { it.apiAtOpen = apiRequestsSoFar(container) } }
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    var reload by remember { mutableIntStateOf(0) }

    // Always refresh on (re)entry so progress/watched state is current after playback.
    LaunchedEffect(reload, entry) {
        model.loading = true
        try {
            coroutineScope {
                // Everything that only needs the id starts together with the item itself (the card we
                // came from already says whether it's a series), instead of one request after another.
                val knownSeries = route.preview?.isSeries == true
                val perf = com.auroratv.core.PerfTuning.on
                // "More Like This" is the server's heaviest query here and sits at the bottom of the
                // page: asked once per page (not again on each return from the player), and after the
                // page's own data so it never competes with it.
                val similar: kotlinx.coroutines.Deferred<List<BaseItemDto>>? = if (perf) null else async { runCatching { api.similar(route.itemId) }.getOrDefault(emptyList()) }
                val seasonsEarly = if (knownSeries) async { api.seasons(route.itemId) } else null
                val nextEarly = if (knownSeries) async { runCatching { api.nextUp(limit = 1, seriesId = route.itemId).firstOrNull() }.getOrNull() } else null
                // An episode shows its number in its show's episode order, if the show has one.
                val item = api.displayed(api.item(route.itemId))
                model.item = item
                model.error = null
                if (item.isSeries) {
                    val seasons = seasonsEarly ?: async { api.seasons(item.id) }
                    val next = nextEarly ?: async { runCatching { api.nextUp(limit = 1, seriesId = item.id).firstOrNull() }.getOrNull() }
                    model.seasons = seasons.await()
                    model.nextUp = next.await()
                    if (model.selectedSeason == null) {
                        model.selectedSeason = model.nextUp?.seasonId ?: model.seasons.firstOrNull { it.indexNumber != 0 }?.id ?: model.seasons.firstOrNull()?.id
                    }
                    model.seasonEpisodes.clear() // watched state may have changed since
                    model.selectedSeason?.let { sid -> model.episodes = api.episodes(item.id, seasonId = sid).also { model.seasonEpisodes[sid] = it } }
                } else if (item.isSeason && item.seriesId != null) {
                    model.episodes = api.episodes(item.seriesId, seasonId = item.id)
                }
                if (similar != null) model.similar = similar.await()
                model.loaded = true
                if (perf && model.similarFor != item.id) launch {
                    model.similar = runCatching { api.similar(item.id) }.getOrDefault(emptyList())
                    model.similarFor = item.id
                }
                // Perf (CI reads it): how long until the page had everything, and what it cost the server.
                val kind = item.type ?: "Item"
                val first = model.loads++ == 0
                val requests = apiRequestsSoFar(container)?.let { now -> model.apiAtOpen?.let { now - it } }
                com.auroratv.core.Log.i("Perf", "Detail loaded in ${model.openedAt.elapsedNow().inWholeMilliseconds} ms ($kind, ${if (first) "opened" else "again"}" +
                    (if (route.preview != null) ", card known" else "") + (requests?.let { ", $it API request(s) so far" } ?: "") + ")")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val err = t.toAppError()
            container.onError(err)
            model.error = err.userMessage
        } finally {
            model.loading = false
        }
    }
    // Ratings and reviews come from outside services: fetched once per item after the page is up,
    // cached by RatingsService, and never in the way of the page itself.
    val loadedId = model.item?.takeIf { model.loaded }?.id
    LaunchedEffect(loadedId) {
        val item = model.item ?: return@LaunchedEffect
        if (loadedId == null || model.ratingsFor == loadedId) return@LaunchedEffect
        model.ratingsFor = loadedId
        model.ratings = com.auroratv.ratings.RatingsService.fromJellyfin(item)
        if (!container.ratings.supports(item)) return@LaunchedEffect
        container.ratingsKeys.pull(api)
        coroutineScope {
            launch { container.ratings.ratings(item)?.let { model.ratings = it } }
            launch { model.reviews = container.ratings.reviews(item) }
        }
    }
    // A show's alternative episode orders (TMDB): looked up after the page is up, at most weekly. When
    // that changes the order the show is in (its DVD or intended order applies now), the page reloads.
    LaunchedEffect(loadedId) {
        val item = model.item ?: return@LaunchedEffect
        if (loadedId == null || !item.isSeries) return@LaunchedEffect
        container.ratingsKeys.pull(api)
        if (runCatching { api.lookUpOrders(item) }.getOrDefault(false)) { model.selectedSeason = null; reload++ }
    }
    // Air dates of upcoming episodes: after the page is up, from memory when this series was opened
    // in the last few hours, else one small request (never for a series that has ended).
    LaunchedEffect(loadedId) {
        val item = model.item ?: return@LaunchedEffect
        if (loadedId == null) return@LaunchedEffect
        val series = when {
            item.isSeries -> item.takeUnless { it.status.equals("Ended", ignoreCase = true) }
            item.isSeason && item.seriesId != null -> BaseItemDto(id = item.seriesId, type = "Series", name = item.seriesName)
            else -> null
        } ?: return@LaunchedEffect
        model.upcoming = container.airDates.cached(series.id) ?: runCatching { container.airDates.upcoming(api, series) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }.getOrDefault(emptyList())
    }
    ScreenFocus(entry, navigator, playFocus, ready = model.item != null)

    val item = model.item
    if (item == null) {
        if (model.error != null) ErrorState(model.error!!, onRetry = { reload++ }) else LoadingState()
        return
    }
    val colors = Aurora.colors

    fun toggleFavorite() {
        val fav = !(item.userData?.isFavorite ?: false)
        scope.launch {
            runCatching { api.setFavorite(item.id, fav) }.onSuccess { com.auroratv.data.WatchActivity.changed() }
                .onSuccess { model.item = item.copy(userData = (item.userData ?: com.auroratv.jellyfin.model.UserItemData()).copy(isFavorite = fav)) }
                .onFailure { container.onError(it.toAppError()) }
        }
    }
    fun togglePlayed() {
        container.prewarmer.invalidate() // resume point changes
        val played = !(item.userData?.played ?: false)
        scope.launch {
            runCatching { api.setPlayed(item.id, played) }.onSuccess { com.auroratv.data.WatchActivity.changed() }
                .onSuccess { reload++ }
                .onFailure { container.onError(it.toAppError()) }
        }
    }

    val ratingKeys by container.ratingsKeys.keys.collectAsState()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerHeight = maxHeight * 0.86f
        // Full-screen backdrop like the tvOS TV app; shade only where text sits (lower left).
        Box(Modifier.fillMaxSize()) {
            ArtworkImage(api.images.backdrop(item, screenArtworkPx()), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to colors.background.copy(alpha = 0.88f), 0.5f to colors.background.copy(alpha = 0.3f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 0.8f to colors.background.copy(alpha = 0.92f), 1f to colors.background)))
        }

        LazyColumn(
            state = model.listState,
            contentPadding = PaddingValues(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                // Returning to the buttons from any row below must bring back the whole hero (logo,
                // title, description, artwork), not just the buttons. It used to start a scroll to the
                // top at the same moment as focus's own "just show the button" scroll; whichever began
                // last won, usually the small one (going up to the profile button and back worked only
                // because the button was then already on screen). Now the whole hero is requested
                // alongside the button (one combined scroll), and if anything still left the page short
                // of the top, it settles there once focus's scroll is done.
                Box(
                    Modifier.fillMaxWidth().height(headerHeight).revealOnFocus().onFocusChanged { f ->
                        if (f.hasFocus) scope.launch {
                            kotlinx.coroutines.delay(HERO_SETTLE_MS)
                            val list = model.listState
                            if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) list.animateScrollToItem(0)
                            if (com.auroratv.ui.components.UiTrace.on) com.auroratv.core.Log.i("AuroraLayout", "detail hero at item=${list.firstVisibleItemIndex} offset=${list.firstVisibleItemScrollOffset}")
                        }
                    },
                    contentAlignment = Alignment.BottomStart,
                ) {
                DetailHeader(
                    item = item,
                    ratings = model.ratings,
                    nextUp = model.nextUp,
                    playFocus = playFocus,
                    onPlay = { target, fromStart ->
                        // A film with a disc menu pack opens it on Play; resuming and "From Beginning" go straight to the player.
                        val menu = if (!fromStart && target.resumePositionMs == 0L && target.type == "Movie" && container.settings.current.discMenus) container.discMenus.packFor(target) else null
                        navigator.push(if (menu != null) Route.DiscMenu(target.id, target) else Route.Player(target.id, fromStart, target))
                    },
                    // The page's own item already has its media details (a film or an episode page): the
                    // preparation doesn't ask for it again.
                    onPrewarm = { target ->
                        val known = model.item?.takeIf { com.auroratv.core.PerfTuning.on && model.loaded && !model.loading && it.id == target.id }
                        container.prewarmPlayback(api, target.id, known)
                    },
                    onFavorite = ::toggleFavorite,
                    onPlayed = ::togglePlayed,
                    onSeries = item.seriesId?.let { sid -> { navigator.push(Route.Detail(sid)) } },
                    // Several episodes in this file: where each starts (Virtual Episode Splitting).
                    onSplit = if (item.isEpisode && item.locationType != "Virtual") { { navigator.push(Route.SplitEditor(item.id)) } } else null,
                    // Episodes that aired out of order: the show's episode order (automatic or by hand).
                    onOrder = if (item.isSeries) { { navigator.push(Route.EpisodeOrder(item.id)) } } else null,
                    episodesFallback = model.episodes?.firstOrNull { it.locationType != "Virtual" },
                    nextAir = if (item.isSeries) com.auroratv.data.Upcoming.next(model.upcoming) else null,
                )
                }
            }
            // Seasons that haven't started yet get their premiere date ("Premieres …" when firm,
            // "Expected …" when not); one the server doesn't list yet is added after the others.
            val airedSeasons = model.seasons.filter { it.locationType != "Virtual" }.mapNotNull { it.indexNumber }.toSet()
            val premieres = com.auroratv.data.Upcoming.seasonPremieres(model.upcoming, airedSeasons)
            val seasons = model.seasons + premieres.keys.filter { n -> model.seasons.none { it.indexNumber == n } }.sorted()
                .map { n -> BaseItemDto(id = "$UPCOMING_SEASON$n", type = "Season", name = "Season $n", indexNumber = n, locationType = "Virtual") }
            if (item.isSeries && seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonPicker(seasons, model.selectedSeason, premieres) { sid ->
                        if (sid != model.selectedSeason) {
                            model.selectedSeason = sid
                            model.episodes = null
                            // A season the server doesn't list has nothing to fetch: its episodes are the upcoming ones.
                            if (sid.startsWith(UPCOMING_SEASON)) model.episodes = emptyList()
                            else scope.launch {
                                val t0 = kotlin.time.TimeSource.Monotonic.markNow()
                                val before = apiRequestsSoFar(container)
                                val perf = com.auroratv.core.PerfTuning.on
                                // A season already shown on this page comes back at once (nothing is
                                // watched while the page is open; the page reloads after playback).
                                val list = (if (perf) model.seasonEpisodes[sid] else null)
                                    ?: runCatching { api.episodes(item.id, seasonId = sid) }.getOrDefault(emptyList())
                                        .also { if (perf && it.isNotEmpty()) model.seasonEpisodes[sid] = it }
                                // A slower answer for a season switched away from never replaces the one now shown.
                                if (perf && model.selectedSeason != sid) return@launch
                                model.episodes = list
                                com.auroratv.core.Log.i("Perf", "Season shown in ${t0.elapsedNow().inWholeMilliseconds} ms (${list.size} episodes" +
                                    (before?.let { b -> apiRequestsSoFar(container)?.let { ", ${it - b} API request(s)" } } ?: "") + ")")
                            }
                        }
                    }
                }
            }
            if (item.isSeries || item.isSeason) {
                item(key = "episodes") {
                    val eps = model.episodes
                    if (eps == null) Box(Modifier.fillMaxWidth().height(220.dp)) { LoadingState() }
                    else {
                        val season = if (item.isSeason) item else seasons.firstOrNull { it.id == model.selectedSeason }
                        val shown = remember(eps, model.upcoming, season?.id) { withUpcoming(eps, model.upcoming, season) }
                        EpisodeRow(shown.first, shown.second) { ep -> if (ep.id !in shown.second) navigator.push(Route.Detail(ep.id, ep)) }
                    }
                }
            }
            if (model.reviews.isNotEmpty()) {
                item(key = "reviews") { ReviewsRow(model.reviews, model.reviewsFocus) { model.openReview = it } }
            } else if (ratingKeys.trakt.isBlank() && (item.isMovie || item.isSeries)) {
                item(key = "reviews-setup") {
                    ReviewsHint(hasMdbList = ratingKeys.mdblist.isNotBlank()) {
                        container.pendingSettingsPage = "Ratings"
                        navigator.selectSection(com.auroratv.ui.nav.Section.Settings)
                    }
                }
            }
            val cast = item.people.filter { it.type == "Actor" }.take(20)
            if (cast.isNotEmpty()) {
                item(key = "cast") { CastRow(cast, api.images) }
            }
            if (model.similar.isNotEmpty()) {
                item(key = "similar") {
                    ContentRail("More Like This", model.similar, api.images, CardStyle.Poster, "similar", onItemClick = { navigator.push(Route.Detail(it.id, it)) })
                }
            }
            item(key = "info") { TechnicalInfo(item) }
        }
        model.openReview?.let { review ->
            ReviewOverlay(review) {
                model.openReview = null
                runCatching { model.reviewsFocus.requestFocus() }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    item: BaseItemDto,
    ratings: com.auroratv.ratings.ExternalRatings?,
    nextUp: BaseItemDto?,
    playFocus: FocusRequester,
    onPlay: (BaseItemDto, Boolean) -> Unit,
    onPrewarm: (BaseItemDto) -> Unit,
    onFavorite: () -> Unit,
    onPlayed: () -> Unit,
    onSeries: (() -> Unit)?,
    onSplit: (() -> Unit)? = null,
    onOrder: (() -> Unit)? = null,
    episodesFallback: BaseItemDto?,
    nextAir: com.auroratv.data.UpcomingEpisode? = null,
) {
    val colors = Aurora.colors
    val api = LocalSession.current.api
    Column(Modifier.padding(start = ScreenStart, end = ScreenPadding).widthIn(max = 760.dp)) {
        val logo = api.images.logo(item, artworkPx(480.dp, 1f))
        if (logo != null && !item.isEpisode) {
            ArtworkImage(logo, item.name, Modifier.height(140.dp).widthIn(max = 520.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, showPlaceholder = false, colorFilter = com.auroratv.ui.components.logoColorFilter())
        } else {
            if (item.isEpisode) Text(item.seriesName ?: "", style = AuroraType.section, color = colors.onSurfaceMuted)
            // An episode without a title of its own (a later part of a shared file, say) is called by its number.
            Text(item.name ?: item.indexNumber?.takeIf { item.isEpisode }?.let { "Episode $it" } ?: "", style = AuroraType.hero, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        val meta = listOfNotNull(
            item.episodeLabel,
            item.productionYear?.toString(),
            item.officialRating,
            formatRuntime(item.runtimeMs),
            // Community score stays in the line unless IMDb's own number is shown below.
            formatRating(item.communityRating)?.takeIf { ratings?.imdb == null }?.let { "★ $it" },
            item.status?.takeIf { item.isSeries },
        ).joinToString("  ·  ")
        // "Continuing — Next Episode: 24 November 2026": the date in yellow, only when it's known
        // and still ahead (see AirDates).
        val next = nextAir?.takeIf { item.isSeries && !item.status.equals("Ended", ignoreCase = true) }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(meta)
                if (next != null) {
                    append(if (item.status.isNullOrBlank()) (if (meta.isEmpty()) "" else "  ·  ") else " — ")
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = AiringYellow))
                    append("Next Episode: ")
                    append(airLabel(next.date, capitalised = false))
                    pop()
                }
            },
            style = AuroraType.bodyStrong, color = colors.onSurfaceMuted,
        )
        if (item.genres.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(item.genres.joinToString(", "), style = AuroraType.body, color = colors.onSurfaceFaint)
        }
        if (ratings != null && !ratings.isEmpty) {
            Spacer(Modifier.height(16.dp))
            RatingsStrip(ratings)
        }
        item.taglines.firstOrNull()?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = AuroraType.bodyStrong, color = colors.onBackground)
        }
        Spacer(Modifier.height(12.dp))
        Text(item.overview ?: "", style = AuroraType.body, color = colors.onSurfaceMuted, maxLines = 5, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(28.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val playTarget = when {
                item.isSeries -> nextUp ?: episodesFallback
                item.isSeason -> episodesFallback
                else -> item
            }
            if (playTarget != null) {
                // Get playback ready while the viewer reads the page (PlaybackInfo, fonts), so
                // pressing Play goes straight to the video. Short delay: skip pages just passed through.
                LaunchedEffect(playTarget.id, playTarget.resumePositionMs) { delay(400); onPrewarm(playTarget) }
                val resumeMs = playTarget.resumePositionMs
                val label = when {
                    resumeMs > 0 -> "Resume ${formatTime(resumeMs)}"
                    item.isSeries && playTarget.episodeLabel != null -> "Play ${playTarget.episodeLabel}"
                    else -> "Play"
                }
                AuroraButton(label, { onPlay(playTarget, false) }, icon = Icons.Filled.PlayArrow, primary = true, focusRequester = playFocus, focusKey = "detail/play")
                if (resumeMs > 0) AuroraButton("From Beginning", { onPlay(playTarget, true) }, icon = Icons.Filled.Replay, focusKey = "detail/restart")
            }
            val fav = item.userData?.isFavorite == true
            AuroraButton(if (fav) "Favorited" else "Favorite", onFavorite, icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, focusKey = "detail/fav")
            if (item.isPlayable || item.isSeries || item.isSeason) {
                val played = item.userData?.played == true
                AuroraButton(if (played) "Watched" else "Mark Watched", onPlayed, icon = Icons.Filled.Check, focusKey = "detail/played")
            }
            if (onSeries != null && item.isEpisode) AuroraButton("Go to Series", onSeries, icon = Icons.Filled.Tv, focusKey = "detail/series")
            if (onSplit != null) {
                val split = com.auroratv.jellyfin.VirtualIds.isVirtual(item.id) || api.virtual?.handles(item.id) == true
                AuroraButton(if (split) "Edit Split" else "Split Episodes", onSplit, icon = Icons.Filled.ContentCut, focusKey = "detail/split")
            }
            if (onOrder != null) {
                // "DVD Order", "Intended Order", "Custom Order" when one applies.
                val current = api.order?.plan(item.id)?.label?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                AuroraButton(current ?: "Episode Order", onOrder, icon = Icons.Filled.FormatListNumbered, focusKey = "detail/order")
            }
        }
    }
}

@Composable
private fun SeasonPicker(seasons: List<BaseItemDto>, selected: String?, premieres: Map<Int, com.auroratv.data.AirDate>, onSelect: (String) -> Unit) {
    RowScroll { LazyRow(contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusRow().revealOnFocus()) {
        items(seasons, key = { it.id }) { season ->
            // "Season 3 — Premieres 24 November 2026" for a season that hasn't started (only with a known date).
            val premiere = season.indexNumber?.let { premieres[it] }?.takeIf { season.locationType == "Virtual" || season.id.startsWith(UPCOMING_SEASON) }
            AuroraChip(season.name ?: "Season ${season.indexNumber}", season.id == selected, onClick = { onSelect(season.id) }, focusKey = "season/${season.id}",
                note = premiere?.let { (if (it.tentative) "Expected " else "Premieres ") + it.text })
        }
    } }
}

/** Id of a season known only from upcoming episodes (not on the server yet). */
private const val UPCOMING_SEASON = "upcoming-season-"

/** "Airs 24 November 2026", "Expected 2027" (or lower-case after "Next Episode:"). */
private fun airLabel(date: com.auroratv.data.AirDate, capitalised: Boolean = true): String =
    if (!date.tentative) date.text else (if (capitalised) "Expected " else "expected ") + date.text

/**
 * A season's episodes with the ones that haven't aired yet: the server's own (listed when the
 * user shows missing episodes) are marked, and upcoming ones it doesn't list are added in episode
 * order. Returns the list and the air dates of the unreleased ones (by episode id).
 */
private fun withUpcoming(
    episodes: List<BaseItemDto>,
    upcoming: List<com.auroratv.data.UpcomingEpisode>,
    season: BaseItemDto?,
): Pair<List<BaseItemDto>, Map<String, com.auroratv.data.AirDate>> {
    val now = com.auroratv.playback.wallClockMs()
    val dates = HashMap<String, com.auroratv.data.AirDate>()
    episodes.forEach { ep ->
        if (ep.locationType == "Virtual") com.auroratv.data.Upcoming.airDate(ep.premiereDate, ep.name, now)?.let { dates[ep.id] = it }
    }
    val seasonNumber = season?.indexNumber ?: return episodes to dates
    val extra = upcoming.filter { u ->
        (u.seasonNumber == seasonNumber || (u.seasonId != null && u.seasonId == season.id)) && u.date.isUpcoming(now) &&
            episodes.none { e -> e.id == u.item?.id || (u.episodeNumber != null && e.indexNumber == u.episodeNumber && e.parentIndexNumber in setOf(null, seasonNumber)) }
    }.map { u ->
        val ep = u.item ?: BaseItemDto(id = "upcoming-$seasonNumber-${u.episodeNumber}", type = "Episode", name = u.name,
            indexNumber = u.episodeNumber, parentIndexNumber = seasonNumber, locationType = "Virtual")
        dates[ep.id] = u.date
        ep
    }
    if (extra.isEmpty()) return episodes to dates
    return (episodes + extra).sortedWith(compareBy<BaseItemDto>({ it.indexNumber ?: Int.MAX_VALUE })) to dates
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EpisodeRow(episodes: List<BaseItemDto>, airDates: Map<String, com.auroratv.data.AirDate>, onClick: (BaseItemDto) -> Unit) {
    val api = LocalSession.current.api
    val colors = Aurora.colors
    if (episodes.isEmpty()) {
        Text("No episodes in this season yet.", style = AuroraType.body, color = colors.onSurfaceMuted, modifier = Modifier.padding(start = ScreenStart, end = ScreenPadding))
        return
    }
    // The focused episode's description is shown in full-width text under the row (like the TV apps),
    // instead of squeezed into two faint lines under each thumbnail where it was cut off or below the
    // screen. Cards keep their size; the whole block (row + description) scrolls into view on focus.
    var focused by remember(episodes) { mutableStateOf(episodes.first()) }
    Column(Modifier.fillMaxWidth().revealOnFocus()) {
        RowScroll { LazyRow(
            contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.focusRow().focusRestorer(),
        ) {
            items(episodes, key = { it.id }) { ep ->
                val airs = airDates[ep.id]
                Column(Modifier.width(CardStyle.Landscape.width)) {
                    // Not out yet: the card is dimmed and Select does nothing, so it never looks playable.
                    MediaCard(ep, api.images, CardStyle.Landscape, onClick = { onClick(ep) }, focusKey = "ep/${ep.id}", showTitle = false, onFocused = { focused = ep },
                        modifier = if (airs != null) Modifier.alpha(0.5f) else Modifier)
                    Spacer(Modifier.height(10.dp))
                    Text(listOfNotNull(ep.episodeLabel?.substringAfter(':'), ep.name?.takeUnless { airs != null && it.isBlank() }).joinToString(" · "), style = AuroraType.label,
                        color = if (focused.id == ep.id) colors.onBackground else colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (airs != null) Text((if (airs.tentative) "Expected " else "Airs ") + airs.text, style = AuroraType.caption, color = AiringYellow, maxLines = 1)
                    else formatRuntime(ep.runtimeMs)?.let { Text(it, style = AuroraType.caption, color = colors.onSurfaceFaint, maxLines = 1) }
                }
            }
        } }
        // Fixed height (four lines), so moving between episodes never makes the page jump; the rest
        // of a long description is on the episode's own page (Select).
        Column(Modifier.padding(start = ScreenStart, end = ScreenPadding, top = 6.dp).widthIn(max = 1000.dp).height(EPISODE_TEXT_HEIGHT)) {
            val airs = airDates[focused.id]
            if (airs != null) {
                // "Episode 8 — Airs 24 November 2026"
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(focused.indexNumber?.let { "Episode $it" } ?: focused.name ?: "Episode")
                        append(" — ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = AiringYellow))
                        append((if (airs.tentative) "Expected " else "Airs ") + airs.text)
                        pop()
                    },
                    style = AuroraType.bodyStrong, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(listOfNotNull(focused.episodeLabel, focused.name).joinToString(" · "), style = AuroraType.bodyStrong, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(focused.overview?.takeIf { it.isNotBlank() } ?: if (airs != null) "Not out yet." else "No description for this episode yet.", style = AuroraType.body,
                color = colors.onSurfaceMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val EPISODE_TEXT_HEIGHT = 150.dp
/** Air dates of episodes still to come: a soft yellow, quieter than the player's skip markers. */
internal val AiringYellow = Color(0xFFF2C94C)
private const val HERO_SETTLE_MS = 450L

@Composable
private fun CastRow(cast: List<com.auroratv.jellyfin.model.PersonDto>, images: com.auroratv.jellyfin.ImageUrls) {
    val colors = Aurora.colors
    val personPx = artworkPx(104.dp)
    Column(Modifier.revealOnFocus()) {
        Text("Cast", style = AuroraType.section, color = colors.onBackground, modifier = Modifier.padding(start = ScreenStart, bottom = 14.dp))
        RowScroll { LazyRow(contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.focusRow()) {
            items(cast, key = { (it.id ?: it.name) + it.role }) { person ->
                Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusSurface(onClick = {}, shape = CircleShape, modifier = Modifier.size(104.dp)) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
                            Text(person.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), style = AuroraType.section, color = colors.onSurfaceMuted)
                            images.person(person.id, person.primaryImageTag, personPx)?.let {
                                ArtworkImage(Artwork(it, null), person.name, Modifier.fillMaxSize(), showPlaceholder = false)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(person.name, style = AuroraType.caption, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    person.role?.let { Text(it, style = AuroraType.caption, color = colors.onSurfaceFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        } }
    }
}

@Composable
private fun TechnicalInfo(item: BaseItemDto) {
    val source = item.mediaSources.firstOrNull() ?: return
    val colors = Aurora.colors
    val video = source.videoStream
    val lines = buildList {
        video?.let { add("Video" to listOfNotNull(it.displayTitle ?: it.codec?.uppercase(), it.videoRangeType?.takeIf { r -> r != "SDR" }).joinToString(" · ")) }
        source.audioStreams.takeIf { it.isNotEmpty() }?.let { add("Audio" to it.joinToString("\n") { a -> a.displayTitle ?: a.codec.orEmpty() }) }
        source.subtitleStreams.takeIf { it.isNotEmpty() }?.let { add("Subtitles" to it.joinToString("\n") { s -> s.displayTitle ?: s.codec.orEmpty() }) }
        source.container?.let { add("Container" to it.uppercase()) }
    }
    Column(Modifier.padding(start = ScreenStart, end = ScreenPadding)) {
        Text("Media Info", style = AuroraType.section, color = colors.onBackground)
        Spacer(Modifier.height(12.dp))
        lines.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(k, style = AuroraType.label, color = colors.onSurfaceFaint, modifier = Modifier.width(140.dp))
                Text(v, style = AuroraType.body, color = colors.onSurfaceMuted)
            }
        }
    }
}
