package com.auroratv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.LocalContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.ContentRail
import com.auroratv.ui.components.EmptyState
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.components.MediaCard
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.components.SkeletonRail
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

// ---- Search ---------------------------------------------------------------------------------

class SearchModel {
    var query by mutableStateOf("")
    var results by mutableStateOf<Map<String, List<BaseItemDto>>?>(null)
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val listState = LazyListState()
}

@Composable
fun SearchScreen() {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("search") { SearchModel() }
    val fieldFocus = remember { FocusRequester() }
    val colors = Aurora.colors
    ScreenFocus(entry, navigator, fieldFocus)

    // Debounced: typing cancels the previous request (LaunchedEffect restarts on every keystroke).
    LaunchedEffect(model.query) {
        val q = model.query.trim()
        if (q.length < 2) { model.results = null; model.searching = false; return@LaunchedEffect }
        delay(350)
        model.searching = true
        try {
            val r = api.items(
                ItemQuery(searchTerm = q, includeItemTypes = listOf("Movie", "Series", "Episode", "BoxSet"), recursive = true, limit = 80, sortBy = "SortName")
            ).items
            model.results = linkedMapOf(
                "Movies" to r.filter { it.isMovie },
                "TV Shows" to r.filter { it.isSeries },
                "Episodes" to r.filter { it.isEpisode },
                "Collections" to r.filter { it.type == "BoxSet" },
            ).filterValues { it.isNotEmpty() }
            model.error = null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.onError(t.toAppError())
            model.error = t.toAppError().userMessage
        } finally {
            model.searching = false
        }
    }

    LazyColumn(
        state = model.listState,
        contentPadding = PaddingValues(top = 48.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "field") {
            Column(Modifier.padding(horizontal = ScreenPadding)) {
                Text("Search", style = AuroraType.title, color = colors.onBackground)
                Spacer(Modifier.height(18.dp))
                AuroraTextField(
                    value = model.query,
                    onValueChange = { model.query = it },
                    label = "Movies, shows, episodes",
                    imeAction = ImeAction.Search,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().focusRequester(fieldFocus),
                )
            }
        }
        val results = model.results
        when {
            model.error != null -> item { Text(model.error!!, style = AuroraType.body, color = colors.error, modifier = Modifier.padding(horizontal = ScreenPadding)) }
            model.searching && results == null -> items(2) { SkeletonRail(CardStyle.Poster) }
            results != null && results.isEmpty() -> item { EmptyState("No results", "Nothing matched “${model.query.trim()}”.") }
            results != null -> results.forEach { (title, list) ->
                item(key = "r-$title") {
                    ContentRail(title, list, api.images, if (title == "Episodes") CardStyle.Landscape else CardStyle.Poster, "search-$title", onItemClick = { openItem(navigator, it) })
                }
            }
        }
    }
}

// ---- Simple grids: Continue Watching, Favorites, Libraries ---------------------------------

class GridModel {
    var sections by mutableStateOf<List<Pair<String, List<BaseItemDto>>>?>(null)
    var error by mutableStateOf<String?>(null)
    val listState = LazyListState()
    val gridState = LazyGridState()
}

@Composable
private fun SectionedScreen(
    title: String,
    emptyTitle: String,
    emptyMessage: String,
    load: suspend () -> List<Pair<String, List<BaseItemDto>>>,
    styleFor: (String) -> CardStyle,
) {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("sections") { GridModel() }
    var reload by remember { mutableIntStateOf(0) }
    val colors = Aurora.colors

    LaunchedEffect(reload) {
        try {
            model.sections = load()
            model.error = null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.onError(t.toAppError())
            model.error = t.toAppError().userMessage
        }
    }
    val firstKey = model.sections?.firstOrNull { it.second.isNotEmpty() }?.let { (t, l) -> "sec-$t/${l.first().id}" }
    val fallback = firstKey?.let { entry.focus.requester(it) }
    ScreenFocus(entry, navigator, fallback, ready = model.sections != null)

    val sections = model.sections
    when {
        sections == null && model.error != null -> ErrorState(model.error!!, onRetry = { reload++ })
        sections == null -> Column(Modifier.padding(top = 120.dp)) { SkeletonRail(CardStyle.Landscape); SkeletonRail(CardStyle.Poster) }
        sections.all { it.second.isEmpty() } -> Column(Modifier.padding(top = 48.dp)) {
            Text(title, style = AuroraType.title, color = colors.onBackground, modifier = Modifier.padding(horizontal = ScreenPadding))
            EmptyState(emptyTitle, emptyMessage)
        }
        else -> LazyColumn(
            state = model.listState,
            contentPadding = PaddingValues(top = 48.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Text(title, style = AuroraType.title, color = colors.onBackground, modifier = Modifier.padding(horizontal = ScreenPadding)) }
            sections.filter { it.second.isNotEmpty() }.forEach { (name, list) ->
                item(key = name) {
                    ContentRail(name, list, api.images, styleFor(name), "sec-$name", onItemClick = { openItem(navigator, it) })
                }
            }
        }
    }
}

@Composable
fun ContinueScreen() {
    val api = LocalSession.current.api
    SectionedScreen(
        title = "Continue Watching",
        emptyTitle = "Nothing in progress",
        emptyMessage = "Movies and episodes you start will appear here.",
        load = {
            coroutineScope {
                val resume = async { api.resumeItems(limit = 50) }
                val next = async { api.nextUp(limit = 50) }
                listOf("In Progress" to resume.await(), "Next Up" to next.await())
            }
        },
        styleFor = { CardStyle.Landscape },
    )
}

@Composable
fun FavoritesScreen() {
    val api = LocalSession.current.api
    SectionedScreen(
        title = "Favorites",
        emptyTitle = "No favorites yet",
        emptyMessage = "Use the Favorite button on any movie, show or episode.",
        load = {
            val all = api.items(ItemQuery(isFavorite = true, includeItemTypes = listOf("Movie", "Series", "Episode", "BoxSet"), limit = 300, sortBy = "SortName")).items
            listOf(
                "Movies" to all.filter { it.isMovie },
                "TV Shows" to all.filter { it.isSeries },
                "Episodes" to all.filter { it.isEpisode },
                "Collections" to all.filter { it.type == "BoxSet" },
            )
        },
        styleFor = { if (it == "Episodes") CardStyle.Landscape else CardStyle.Poster },
    )
}

@Composable
fun LibrariesScreen() {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("libraries") { GridModel() }
    var reload by remember { mutableIntStateOf(0) }
    val colors = Aurora.colors

    LaunchedEffect(reload) {
        try {
            model.sections = listOf("Libraries" to api.userViews())
            model.error = null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.onError(t.toAppError())
            model.error = t.toAppError().userMessage
        }
    }
    val views = model.sections?.firstOrNull()?.second
    ScreenFocus(entry, navigator, views?.firstOrNull()?.let { entry.focus.requester("lib/${it.id}") }, ready = views != null)

    when {
        views == null && model.error != null -> ErrorState(model.error!!, onRetry = { reload++ })
        views == null -> LoadingState()
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(CardStyle.Landscape.width + 22.dp),
            state = model.gridState,
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 48.dp, bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text("Libraries", style = AuroraType.title, color = colors.onBackground)
            }
            items(views, key = { it.id }) { view ->
                MediaCard(view, api.images, CardStyle.Landscape, focusKey = "lib/${view.id}", subtitle = view.collectionType?.replaceFirstChar { it.uppercase() }, onClick = {
                    navigator.push(Route.Library(view.id, view.name ?: "", emptyList(), view.collectionType))
                })
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
