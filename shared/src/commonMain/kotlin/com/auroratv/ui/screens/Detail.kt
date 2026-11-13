package com.auroratv.ui.screens

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
    val listState = LazyListState()
}

@Composable
fun DetailScreen(route: Route.Detail) {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("detail") { DetailModel(route.preview) }
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    var reload by remember { mutableIntStateOf(0) }

    // Always refresh on (re)entry so progress/watched state is current after playback.
    LaunchedEffect(reload, entry) {
        try {
            coroutineScope {
                val item = api.item(route.itemId)
                model.item = item
                model.error = null
                val similar = async { runCatching { api.similar(item.id) }.getOrDefault(emptyList()) }
                if (item.isSeries) {
                    val seasons = async { api.seasons(item.id) }
                    val next = async { runCatching { api.nextUp(limit = 1, seriesId = item.id).firstOrNull() }.getOrNull() }
                    model.seasons = seasons.await()
                    model.nextUp = next.await()
                    if (model.selectedSeason == null) {
                        model.selectedSeason = model.nextUp?.seasonId ?: model.seasons.firstOrNull { it.indexNumber != 0 }?.id ?: model.seasons.firstOrNull()?.id
                    }
                    model.selectedSeason?.let { sid -> model.episodes = api.episodes(item.id, seasonId = sid) }
                } else if (item.isSeason && item.seriesId != null) {
                    model.episodes = api.episodes(item.seriesId, seasonId = item.id)
                }
                model.similar = similar.await()
                model.loaded = true
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val err = t.toAppError()
            container.onError(err)
            model.error = err.userMessage
        }
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
            runCatching { api.setFavorite(item.id, fav) }
                .onSuccess { model.item = item.copy(userData = (item.userData ?: com.auroratv.jellyfin.model.UserItemData()).copy(isFavorite = fav)) }
                .onFailure { container.onError(it.toAppError()) }
        }
    }
    fun togglePlayed() {
        val played = !(item.userData?.played ?: false)
        scope.launch {
            runCatching { api.setPlayed(item.id, played) }
                .onSuccess { reload++ }
                .onFailure { container.onError(it.toAppError()) }
        }
    }

    com.auroratv.ui.ReportScrollToTopBar { model.listState.firstVisibleItemIndex == 0 && model.listState.firstVisibleItemScrollOffset < 40 }
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
                Box(Modifier.fillMaxWidth().height(headerHeight), contentAlignment = Alignment.BottomStart) {
                DetailHeader(
                    item = item,
                    nextUp = model.nextUp,
                    playFocus = playFocus,
                    onPlay = { target, fromStart -> navigator.push(Route.Player(target.id, fromStart)) },
                    onFavorite = ::toggleFavorite,
                    onPlayed = ::togglePlayed,
                    onSeries = item.seriesId?.let { sid -> { navigator.push(Route.Detail(sid)) } },
                    episodesFallback = model.episodes?.firstOrNull(),
                )
                }
            }
            if (item.isSeries && model.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonPicker(model.seasons, model.selectedSeason) { sid ->
                        if (sid != model.selectedSeason) {
                            model.selectedSeason = sid
                            model.episodes = null
                            scope.launch { model.episodes = runCatching { api.episodes(item.id, seasonId = sid) }.getOrDefault(emptyList()) }
                        }
                    }
                }
            }
            if (item.isSeries || item.isSeason) {
                item(key = "episodes") {
                    val eps = model.episodes
                    if (eps == null) Box(Modifier.fillMaxWidth().height(220.dp)) { LoadingState() }
                    else EpisodeRow(eps) { ep -> navigator.push(Route.Detail(ep.id, ep)) }
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
    }
}

@Composable
private fun DetailHeader(
    item: BaseItemDto,
    nextUp: BaseItemDto?,
    playFocus: FocusRequester,
    onPlay: (BaseItemDto, Boolean) -> Unit,
    onFavorite: () -> Unit,
    onPlayed: () -> Unit,
    onSeries: (() -> Unit)?,
    episodesFallback: BaseItemDto?,
) {
    val colors = Aurora.colors
    val api = LocalSession.current.api
    Column(Modifier.padding(horizontal = ScreenPadding).widthIn(max = 760.dp)) {
        val logo = api.images.logo(item, artworkPx(480.dp, 1f))
        if (logo != null && !item.isEpisode) {
            ArtworkImage(logo, item.name, Modifier.height(140.dp).widthIn(max = 520.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, showPlaceholder = false, colorFilter = com.auroratv.ui.components.logoColorFilter())
        } else {
            if (item.isEpisode) Text(item.seriesName ?: "", style = AuroraType.section, color = colors.onSurfaceMuted)
            Text(item.name ?: "", style = AuroraType.hero, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        val meta = listOfNotNull(
            item.episodeLabel,
            item.productionYear?.toString(),
            item.officialRating,
            formatRuntime(item.runtimeMs),
            formatRating(item.communityRating)?.let { "★ $it" },
            item.criticRating?.let { "${it.toInt()}% critics" },
            item.status?.takeIf { item.isSeries },
        ).joinToString("  ·  ")
        Text(meta, style = AuroraType.bodyStrong, color = colors.onSurfaceMuted)
        if (item.genres.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(item.genres.joinToString(", "), style = AuroraType.body, color = colors.onSurfaceFaint)
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
        }
    }
}

@Composable
private fun SeasonPicker(seasons: List<BaseItemDto>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(seasons, key = { it.id }) { season ->
            AuroraChip(season.name ?: "Season ${season.indexNumber}", season.id == selected, onClick = { onSelect(season.id) }, focusKey = "season/${season.id}")
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EpisodeRow(episodes: List<BaseItemDto>, onClick: (BaseItemDto) -> Unit) {
    val api = LocalSession.current.api
    val colors = Aurora.colors
    if (episodes.isEmpty()) {
        Text("No episodes in this season yet.", style = AuroraType.body, color = colors.onSurfaceMuted, modifier = Modifier.padding(horizontal = ScreenPadding))
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.focusRestorer(),
    ) {
        items(episodes, key = { it.id }) { ep ->
            Column(Modifier.width(CardStyle.Landscape.width)) {
                MediaCard(ep, api.images, CardStyle.Landscape, onClick = { onClick(ep) }, focusKey = "ep/${ep.id}", showTitle = false)
                Spacer(Modifier.height(10.dp))
                Text(listOfNotNull(ep.episodeLabel?.substringAfter(':'), ep.name).joinToString(" · "), style = AuroraType.label, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ep.overview ?: formatRuntime(ep.runtimeMs) ?: "", style = AuroraType.caption, color = colors.onSurfaceFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CastRow(cast: List<com.auroratv.jellyfin.model.PersonDto>, images: com.auroratv.jellyfin.ImageUrls) {
    val colors = Aurora.colors
    val personPx = artworkPx(104.dp)
    Column {
        Text("Cast", style = AuroraType.section, color = colors.onBackground, modifier = Modifier.padding(start = ScreenPadding, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
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
        }
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
    Column(Modifier.padding(horizontal = ScreenPadding)) {
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
