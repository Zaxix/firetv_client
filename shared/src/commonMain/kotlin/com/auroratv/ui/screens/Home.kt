package com.auroratv.ui.screens

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
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.ContentRail
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.ScreenPadding
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** A rail whose items load lazily the first time it scrolls into view. */
class RailState(val key: String, val title: String, val style: CardStyle, val loader: suspend () -> List<BaseItemDto>) {
    var items by mutableStateOf<List<BaseItemDto>?>(null)
    var failed by mutableStateOf(false)
    suspend fun ensureLoaded() {
        if (items != null) return
        runCatching { loader() }.onSuccess { items = it; failed = false }.onFailure { failed = true; items = emptyList() }
    }
}

class HomeModel {
    var hero by mutableStateOf<List<BaseItemDto>>(emptyList())
    var rails by mutableStateOf<List<RailState>?>(null)
    var error by mutableStateOf<String?>(null)
    var focused by mutableStateOf<BaseItemDto?>(null)
    var heroIndex by mutableIntStateOf(0)
    val listState = LazyListState()
}

@Composable
fun HomeScreen() {
    val container = LocalContainer.current
    val session = LocalSession.current
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("home") { HomeModel() }
    val api = session.api
    val settings = container.settings.current
    val heroFocus = remember { FocusRequester() }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        if (model.rails != null && reloadKey == 0) return@LaunchedEffect
        model.error = null
        try {
            coroutineScope {
                val views = async { api.userViews() }
                val resume = async { runCatching { api.resumeItems() }.getOrDefault(emptyList()) }
                val nextUp = async { runCatching { api.nextUp() }.getOrDefault(emptyList()) }
                val libraryViews = views.await().filter { it.collectionType in setOf("movies", "tvshows", "homevideos", "musicvideos", null) }
                val heroPool = libraryViews.filter { it.collectionType == "movies" || it.collectionType == "tvshows" }.map { view ->
                    async { runCatching { api.latest(view.id, limit = 8) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten().filter { it.backdropImageTags.isNotEmpty() }

                val rails = buildList {
                    val r = resume.await()
                    if (r.isNotEmpty()) add(RailState("resume", "Continue Watching", CardStyle.Landscape) { r }.also { it.items = r })
                    val n = nextUp.await()
                    if (n.isNotEmpty()) add(RailState("nextup", "Next Up", CardStyle.Landscape) { n }.also { it.items = n })
                    libraryViews.forEach { view ->
                        add(RailState("latest-${view.id}", "Recently Added in ${view.name}", CardStyle.Poster) { api.latest(view.id, limit = 24) })
                    }
                    add(RailState("favorites", "Favorites", CardStyle.Poster) {
                        api.items(com.auroratv.jellyfin.ItemQuery(isFavorite = true, includeItemTypes = listOf("Movie", "Series"), sortBy = "DatePlayed,SortName", sortDescending = true, limit = 24)).items
                    })
                }
                model.hero = heroPool.distinctBy { it.seriesId ?: it.id }.shuffled().take(6)
                model.rails = rails
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.onError(t.toAppError())
            model.error = t.toAppError().userMessage
        }
    }

    val rails = model.rails
    ScreenFocus(entry, navigator, heroFocus, ready = rails != null)

    if (model.error != null && rails == null) {
        ErrorState(model.error!!, onRetry = { reloadKey++ })
        return
    }

    val heroItem = model.hero.getOrNull(model.heroIndex % model.hero.size.coerceAtLeast(1))
    val backdropItem = model.focused ?: heroItem
    val accentTarget = if (settings.artworkAccents) backdropItem?.let { item ->
        val tag = item.backdropImageTags.firstOrNull() ?: item.parentBackdropImageTags.firstOrNull()
        BlurHash.accentFrom(tag?.let { item.imageBlurHashes["Backdrop"]?.get(it) }, Aurora.colors.isDark)
    } else null
    val accent by animateColorAsState(accentTarget ?: Aurora.colors.accent, tween(Aurora.motion.duration(600)))

    com.auroratv.ui.ReportScrollToTopBar { model.listState.firstVisibleItemIndex == 0 && model.listState.firstVisibleItemScrollOffset < 40 }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.80f
        if (settings.showBackdrops) {
            HomeBackdrop(backdropItem, accent)
        }
        LazyColumn(
            state = model.listState,
            contentPadding = PaddingValues(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (settings.showHeroCarousel) {
                item(key = "hero") {
                    Hero(model, heroItem, heroFocus, heroHeight, onPlay = { navigator.push(Route.Player(it.id)) }, onInfo = { navigator.push(Route.Detail(it.id, it)) })
                }
            }
            if (rails == null) {
                items(3) { SkeletonRail(if (it < 1) CardStyle.Landscape else CardStyle.Poster) }
            } else {
                items(rails, key = { it.key }) { rail ->
                    LaunchedEffect(rail.key) { rail.ensureLoaded() }
                    val items = rail.items
                    when {
                        items == null -> SkeletonRail(rail.style)
                        items.isEmpty() -> Unit
                        else -> ContentRail(
                            title = rail.title,
                            items = items,
                            images = api.images,
                            style = rail.style,
                            railKey = rail.key,
                            onItemClick = { item -> openItem(navigator, item) },
                            onItemFocused = { model.focused = it },
                        )
                    }
                }
            }
        }
    }
}

/** Movies/episodes open their detail page; folders open as a library. */
fun openItem(navigator: com.auroratv.ui.nav.Navigator, item: BaseItemDto) {
    when {
        item.isBrowsableFolder ->
            navigator.push(Route.Library(item.id, item.name ?: "", emptyList(), item.collectionType))
        else -> navigator.push(Route.Detail(item.id, item))
    }
}

@Composable
private fun HomeBackdrop(item: BaseItemDto?, accent: Color) {
    val api = LocalSession.current.api
    val colors = Aurora.colors
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = item?.id, animationSpec = tween(Aurora.motion.duration(500))) { id ->
            val target = if (id == item?.id) item else null
            ArtworkImage(target?.let { api.images.backdrop(it, 1920) }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
        }
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
) {
    val colors = Aurora.colors
    val api = LocalSession.current.api
    var heroFocused by remember { mutableStateOf(false) }

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
        Modifier.fillMaxWidth().height(height).padding(start = ScreenPadding, end = ScreenPadding, bottom = 36.dp)
            .onFocusChanged { heroFocused = it.hasFocus },
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (item == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AuroraButton("Browse", { }, focusRequester = focus)
            }
            return@Column
        }
        val logo = api.images.logo(item, 800)
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
            // Left on Play / Right on More Info pages through the featured titles, like swiping on tvOS.
            val count = model.hero.size.coerceAtLeast(1)
            AuroraButton("Play", { onPlay(item) }, icon = Icons.Filled.PlayArrow, primary = true, focusRequester = focus, focusKey = "hero/play",
                modifier = Modifier.onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && model.hero.size > 1) { model.heroIndex = (model.heroIndex - 1 + count) % count; true } else false
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
