package com.auroratv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.LocalContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.AuroraChip
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.EmptyState
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.components.MediaCard
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.flow.distinctUntilChanged

enum class SortOption(val label: String, val field: String, val descending: Boolean) {
    Name("A–Z", "SortName", false),
    Added("Recently added", "DateCreated,SortName", true),
    Released("Release date", "PremiereDate,ProductionYear,SortName", true),
    Rating("Rating", "CommunityRating,SortName", true),
    Played("Last watched", "DatePlayed,SortName", true),
}

enum class FilterOption(val label: String, val filters: List<String>, val favorite: Boolean?) {
    All("All", emptyList(), null),
    Unwatched("Unwatched", listOf("IsUnplayed"), null),
    Watched("Watched", listOf("IsPlayed"), null),
    Favorites("Favorites", emptyList(), true),
}

/** Paged item list (60 per page) with load-more as the user nears the end. */
class PagedItems(
    private val pageSize: Int = 60,
    private val onError: (Throwable) -> Unit = {},
    private val fetch: suspend (start: Int, limit: Int) -> Pair<List<BaseItemDto>, Int>) {
    var items by mutableStateOf<List<BaseItemDto>>(emptyList())
    var total by mutableIntStateOf(-1)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val hasMore get() = total < 0 || items.size < total

    suspend fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        try {
            val (page, count) = fetch(items.size, pageSize)
            items = items + page
            total = count
            error = null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val err = t.toAppError()
            onError(err)
            error = err.userMessage
            if (items.isEmpty()) total = 0
        } finally {
            loading = false
        }
    }
}

class LibraryModel(val route: Route.Library) {
    var sort by mutableStateOf(if (route.collectionType == "tvshows" || route.collectionType == "movies") SortOption.Added else SortOption.Name)
    var filter by mutableStateOf(FilterOption.All)
    var paged by mutableStateOf<PagedItems?>(null)
    val gridState = LazyGridState()

    fun reset(api: JellyfinApi, onError: (Throwable) -> Unit) {
        val s = sort; val f = filter
        paged = PagedItems(onError = onError) { start, limit ->
            val r = api.items(
                ItemQuery(
                    parentId = route.parentId,
                    includeItemTypes = route.itemTypes,
                    recursive = route.itemTypes.isNotEmpty(),
                    sortBy = s.field,
                    sortDescending = s.descending,
                    startIndex = start,
                    limit = limit,
                    filters = f.filters,
                    isFavorite = f.favorite,
                )
            )
            r.items to r.totalRecordCount
        }
    }
}

@Composable
fun LibraryScreen(route: Route.Library) {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("library") { LibraryModel(route) }
    val firstChip = remember { FocusRequester() }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        if (model.paged == null || version > 0) model.reset(api, container::onError)
        model.paged?.let { if (it.items.isEmpty()) it.loadMore() }
    }
    val paged = model.paged
    ScreenFocus(entry, navigator, firstChip, ready = paged != null && !paged.loading)

    // Load the next page when the user is within two rows of the end.
    LaunchedEffect(paged) {
        paged ?: return@LaunchedEffect
        snapshotFlow { model.gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= paged.items.size - 14) paged.loadMore() }
    }

    com.auroratv.ui.ReportScrollToTopBar { model.gridState.firstVisibleItemIndex == 0 && model.gridState.firstVisibleItemScrollOffset < 40 }
    val style = if (route.collectionType == "music") CardStyle.Square else CardStyle.Poster
    val colors = Aurora.colors

    LazyVerticalGrid(
        columns = GridCells.Adaptive(style.width + 22.dp),
        state = model.gridState,
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = com.auroratv.ui.TopBarSpace, bottom = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column {
                Row {
                    Text(route.title, style = AuroraType.title, color = colors.onBackground)
                    Spacer(Modifier.width(16.dp))
                    paged?.total?.takeIf { it >= 0 }?.let {
                        Text("$it items", style = AuroraType.body, color = colors.onSurfaceFaint, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortOption.entries.forEachIndexed { i, opt ->
                        AuroraChip(
                            opt.label, model.sort == opt,
                            onClick = { if (model.sort != opt) { model.sort = opt; version++ } },
                            focusKey = "sort/${opt.name}",
                            modifier = if (i == 0) Modifier.focusRequester(firstChip) else Modifier,
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    FilterOption.entries.forEach { opt ->
                        AuroraChip(opt.label, model.filter == opt, onClick = { if (model.filter != opt) { model.filter = opt; version++ } }, focusKey = "filter/${opt.name}")
                    }
                }
            }
        }
        val list = paged?.items.orEmpty()
        items(list, key = { it.id }) { item ->
            MediaCard(item, api.images, style, onClick = { openItem(navigator, item) }, focusKey = "grid/${item.id}")
        }
        if (paged == null || (paged.loading && list.isEmpty())) {
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().height(300.dp)) { LoadingState() } }
        } else if (list.isEmpty() && paged.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(paged.error!!, onRetry = { version++ }, modifier = Modifier.height(360.dp)) }
        } else if (list.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Nothing here", "No items match this filter.") }
        }
    }
}
