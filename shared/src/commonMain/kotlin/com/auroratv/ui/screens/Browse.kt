package com.auroratv.ui.screens

import com.auroratv.ui.nav.focusRow

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.auroratv.search.SearchIndex
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
import com.auroratv.ui.components.ScreenStart
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
    var suggestions by mutableStateOf<List<String>>(emptyList())
    /** Only typo matches were found: the suggestions read as "Did you mean". */
    var onlyCloseMatches by mutableStateOf(false)
    var starring by mutableStateOf<Pair<BaseItemDto, List<BaseItemDto>>?>(null)
    var episodes by mutableStateOf<List<BaseItemDto>>(emptyList())
    var genres by mutableStateOf<List<String>>(emptyList())
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val listState = LazyListState()
}

/**
 * Search as you type. Titles (movies, series, collections) are matched on the device against an
 * index of the library (typos, partial words, any word order, years, genres; see SearchIndex), so
 * results follow every keypress without a request. People ("Starring …") and episodes come from the
 * server a moment after typing pauses. Empty, it offers recent searches and genres.
 */
@Composable
fun SearchScreen() {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("search") { SearchModel() }
    val fieldFocus = remember { FocusRequester() }
    val colors = Aurora.colors
    val recent by container.searchHistory.recent.collectAsState()
    ScreenFocus(entry, navigator, fieldFocus)

    // Start loading the title index as soon as Search opens (reused for 30 minutes).
    LaunchedEffect(api) { container.search.index(api).await()?.let { model.genres = it.genres.take(14) } }

    LaunchedEffect(model.query) {
        val q = model.query.trim()
        if (q.length < 2) {
            model.results = null; model.suggestions = emptyList(); model.starring = null; model.episodes = emptyList()
            model.searching = false; model.error = null
            return@LaunchedEffect
        }
        delay(60) // a keypress that follows within 60 ms replaces this search
        model.searching = true
        try {
            val index = container.search.index(api).await()
            if (index != null) {
                val (hits, suggestions) = withContext(Dispatchers.Default) { index.search(q, 90) to index.suggest(q, 6) }
                model.results = linkedMapOf(
                    "Movies" to hits.filter { it.item.isMovie && it.kind != SearchIndex.Kind.Genre }.map { it.item },
                    "TV Shows" to hits.filter { it.item.isSeries && it.kind != SearchIndex.Kind.Genre }.map { it.item },
                    "Collections" to hits.filter { it.item.type == "BoxSet" && it.kind != SearchIndex.Kind.Genre }.map { it.item },
                    "In this genre" to hits.filter { it.kind == SearchIndex.Kind.Genre }.map { it.item }.take(40),
                ).filterValues { it.isNotEmpty() }
                model.onlyCloseMatches = hits.isNotEmpty() && hits.none { it.kind != SearchIndex.Kind.Fuzzy && it.kind != SearchIndex.Kind.Genre }
                model.suggestions = suggestions.filterNot { it.equals(q, ignoreCase = true) }
            } else {
                // The index couldn't load (old server, network): the server's own search.
                val r = api.items(ItemQuery(searchTerm = q, includeItemTypes = listOf("Movie", "Series", "BoxSet"), recursive = true, limit = 80, sortBy = "SortName", totalCount = false)).items
                model.results = linkedMapOf(
                    "Movies" to r.filter { it.isMovie }, "TV Shows" to r.filter { it.isSeries }, "Collections" to r.filter { it.type == "BoxSet" },
                ).filterValues { it.isNotEmpty() }
                model.suggestions = emptyList(); model.onlyCloseMatches = false
            }
            model.error = null
            model.searching = false
            // People and episodes from the server, once typing pauses (both optional).
            delay(320)
            coroutineScope {
                launch { model.starring = runCatching { container.search.people(api, q) }.getOrNull() }
                launch { model.episodes = runCatching { container.search.episodes(api, q) }.getOrDefault(emptyList()) }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.onError(t.toAppError())
            model.error = t.toAppError().userMessage
            model.searching = false
        }
    }

    fun open(item: BaseItemDto) {
        container.searchHistory.add(model.query)
        openItem(navigator, item)
    }

    LazyColumn(
        state = model.listState,
        contentPadding = PaddingValues(top = com.auroratv.ui.ScreenTop, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "field") {
            Column(Modifier.padding(start = ScreenStart, end = ScreenPadding)) {
                Text("Search", style = AuroraType.title, color = colors.onBackground)
                Spacer(Modifier.height(18.dp))
                AuroraTextField(
                    value = model.query,
                    onValueChange = { model.query = it },
                    label = "Titles, people, genres, years",
                    imeAction = ImeAction.Search,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().focusRequester(fieldFocus),
                )
            }
        }
        val q = model.query.trim()
        if (q.length < 2) {
            if (recent.isNotEmpty()) item(key = "recent") {
                SearchChips("Recent searches", recent, trailing = "Clear" to { container.searchHistory.clear() }) { model.query = it }
            }
            if (model.genres.isNotEmpty()) item(key = "genres") { SearchChips("Browse by genre", model.genres) { model.query = it } }
            return@LazyColumn
        }
        if (model.suggestions.isNotEmpty()) item(key = "suggest") {
            SearchChips(if (model.onlyCloseMatches) "Did you mean" else "Suggestions", model.suggestions) { model.query = it }
        }
        val results = model.results
        val starring = model.starring
        when {
            model.error != null && results == null -> item { Text(model.error!!, style = AuroraType.body, color = colors.error, modifier = Modifier.padding(start = ScreenStart, end = ScreenPadding)) }
            model.searching && results == null -> items(2) { SkeletonRail(CardStyle.Poster) }
            results != null && results.isEmpty() && starring == null && model.episodes.isEmpty() -> item(key = "empty") {
                Column {
                    EmptyState(
                        "No matches for “$q”",
                        "Try fewer words, the start of a title, an actor's name, a genre or a year." +
                            if (model.genres.isNotEmpty()) " Or browse a genre:" else "",
                    )
                    if (model.genres.isNotEmpty()) SearchChips(null, model.genres) { model.query = it }
                }
            }
            results != null -> {
                results.forEach { (title, list) ->
                    item(key = "r-$title") {
                        ContentRail(title, list, api.images, CardStyle.Poster, "search-$title", onItemClick = ::open)
                    }
                }
            }
        }
        if (starring != null) item(key = "starring") {
            ContentRail("Starring ${starring.first.name}", starring.second, api.images, CardStyle.Poster, "search-starring", onItemClick = ::open)
        }
        if (model.episodes.isNotEmpty()) item(key = "episodes") {
            ContentRail("Episodes", model.episodes, api.images, CardStyle.Landscape, "search-Episodes", onItemClick = ::open)
        }
    }
}

/** A titled row of small pills (suggestions, recent searches, genres). */
@Composable
private fun SearchChips(title: String?, chips: List<String>, trailing: Pair<String, () -> Unit>? = null, onPick: (String) -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.padding(start = ScreenStart, end = ScreenPadding)) {
        if (title != null) {
            Text(title, style = AuroraType.label, color = colors.onSurfaceMuted)
            Spacer(Modifier.height(10.dp))
        }
        // The row already starts at the screen margin (padded column), so it only keeps a little room for focus.
        com.auroratv.ui.components.RowScroll(start = 12.dp, end = 12.dp) { androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusRow()) {
            items(chips, key = { it }) { chip -> SearchChip(chip) { onPick(chip) } }
            trailing?.let { (label, action) -> item(key = "trailing") { SearchChip(label, subtle = true, onClick = action) } }
        } }
    }
}

@Composable
private fun SearchChip(text: String, subtle: Boolean = false, onClick: () -> Unit) {
    val colors = Aurora.colors
    com.auroratv.ui.components.FocusSurface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(50), sheen = false) { focused ->
        Text(
            text, style = AuroraType.label, maxLines = 1,
            color = if (focused) colors.background else if (subtle) colors.onSurfaceMuted else colors.onBackground,
            modifier = Modifier
                .background(if (focused) colors.onBackground else colors.onBackground.copy(alpha = if (subtle) 0.06f else 0.12f))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
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
        sections == null -> Column(Modifier.padding(top = com.auroratv.ui.ScreenTop)) { SkeletonRail(CardStyle.Landscape); SkeletonRail(CardStyle.Poster) }
        sections.all { it.second.isEmpty() } -> Column(Modifier.padding(top = com.auroratv.ui.ScreenTop)) {
            Text(title, style = AuroraType.title, color = colors.onBackground, modifier = Modifier.padding(start = ScreenStart, end = ScreenPadding))
            EmptyState(emptyTitle, emptyMessage)
        }
        else -> LazyColumn(
            state = model.listState,
            contentPadding = PaddingValues(top = com.auroratv.ui.ScreenTop, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Text(title, style = AuroraType.title, color = colors.onBackground, modifier = Modifier.padding(start = ScreenStart, end = ScreenPadding)) }
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
            contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding, top = com.auroratv.ui.ScreenTop, bottom = 64.dp),
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
