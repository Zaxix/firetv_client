package com.auroratv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.auroratv.jellyfin.CustomSeason
import com.auroratv.jellyfin.SeriesOrder
import com.auroratv.jellyfin.SourceOrder
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.LocalContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.AuroraChip
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.components.ScreenStart
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.nav.focusRow
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch

/** One season of a hand-made order being edited. */
private class DraftSeason(val number: Int, name: String?, items: List<BaseItemDto>) {
    var name by mutableStateOf(name)
    val items: SnapshotStateList<BaseItemDto> = mutableStateListOf<BaseItemDto>().apply { addAll(items) }
}

private class OrderEditorModel {
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var series by mutableStateOf<BaseItemDto?>(null)
    /** The mode chosen on screen (SeriesOrder.mode), saved with Save. */
    var mode by mutableStateOf(SeriesOrder.AUTO)
    val seasons = mutableStateListOf<DraftSeason>()
    /** Aired season and number of each episode, as the server numbers it ("S2 · E2"). */
    var original: Map<String, String> = emptyMap()
    /** The episode being moved, and where everything was when the move began (Back puts it back). */
    var moving by mutableStateOf<String?>(null)
    var before: List<Pair<Int, List<BaseItemDto>>> = emptyList()
    var moveTick by mutableIntStateOf(0)
    var position by mutableStateOf("")
    var note by mutableStateOf<String?>(null)
    var looking by mutableStateOf(false)
    val listState = LazyListState()
}

/**
 * A show's episode order: automatic (a documented intended or DVD order from TMDB that fits the
 * library), the server's own, one of the orders TMDB lists, or one arranged by hand, across seasons
 * too. Episodes keep their ids, files and progress; only where they appear changes. Saved per server.
 *
 * Moving by remote: select an episode, then Up/Down moves it one place (into the next season at a
 * season's edge), Left/Right moves it to the end of the previous / start of the next season, and
 * select again puts it down (Back puts it back where it was). A position can also be typed.
 */
@Composable
fun EpisodeOrderScreen(route: Route.EpisodeOrder) {
    val session = LocalSession.current
    val container = LocalContainer.current
    val api = session.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("order") { OrderEditorModel() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val colors = Aurora.colors
    val store = api.order?.store
    var refresh by remember { mutableIntStateOf(0) }

    suspend fun loadDraft() {
        // Start from what's on screen today (the show's current order), so a source order can be
        // adjusted by hand too.
        val seasons = api.seasons(route.seriesId)
        val eps = api.episodes(route.seriesId)
        model.seasons.clear()
        seasons.forEach { s ->
            val items = eps.filter { it.seasonId == s.id || (it.seasonId == null && it.parentIndexNumber == s.indexNumber) }
            if (items.isNotEmpty()) model.seasons += DraftSeason(s.indexNumber ?: (model.seasons.size + 1), s.name, items)
        }
    }

    LaunchedEffect(Unit) {
        if (model.loaded) return@LaunchedEffect
        runCatching {
            store ?: error("Episode orders aren't available")
            model.series = api.item(route.seriesId)
            model.mode = store[route.seriesId]?.mode ?: SeriesOrder.AUTO
            model.original = api.serverEpisodeList(route.seriesId).associate { it.id to (it.episodeLabel ?: "") }
            loadDraft()
            model.loaded = true
        }.onFailure { model.error = it.message ?: "Couldn't load this show" }
        // The orders TMDB lists (at most weekly; the screen shows them once known).
        val series = model.series ?: return@LaunchedEffect
        if (api.orderSource?.available == true) {
            container.ratingsKeys.pull(api)
            model.looking = true
            runCatching { api.lookUpOrders(series) }
            model.looking = false
            refresh++
        }
    }
    ScreenFocus(entry, navigator, first, ready = model.loaded)

    // Focus follows the episode being moved (it changes place in the list).
    LaunchedEffect(model.moveTick) {
        val id = model.moving ?: return@LaunchedEffect
        val index = flatIndex(model, id) ?: return@LaunchedEffect
        val visible = model.listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index && it.offset >= 0 }) model.listState.scrollToItem((index - 3).coerceAtLeast(0))
        kotlinx.coroutines.delay(30)
        runCatching { entry.focus.requester("order/ep/$id").requestFocus() }
    }

    val series = model.series
    val saved = remember(refresh, model.loaded) { store?.get(route.seriesId) }
    Box(Modifier.fillMaxSize()) {
        when {
            model.error != null -> ErrorState(model.error!!, onRetry = null)
            !model.loaded || series == null -> LoadingState()
            else -> LazyColumn(
                state = model.listState,
                contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding, top = 56.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "head") {
                    Column(Modifier.widthIn(max = 980.dp)) {
                        Text("Episode order", style = AuroraType.title, color = colors.onBackground)
                        Text(series.name ?: "", style = AuroraType.bodyStrong, color = colors.onSurfaceMuted)
                        Text(
                            "Changes where episodes appear and what plays next, here only. Files, Jellyfin and everyone's progress stay as they are.",
                            style = AuroraType.caption, color = colors.onSurfaceFaint, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                item(key = "modes") {
                    val sources = saved?.sources.orEmpty()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.focusRow(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuroraChip("Automatic", model.mode == SeriesOrder.AUTO, { model.mode = SeriesOrder.AUTO; model.note = null }, focusKey = "order/auto")
                            AuroraChip("Jellyfin's order", model.mode == SeriesOrder.JELLYFIN, { model.mode = SeriesOrder.JELLYFIN; model.note = null }, focusKey = "order/jellyfin")
                            sources.forEach { src ->
                                val mode = SeriesOrder.GROUP + src.id
                                AuroraChip("${SourceOrder.kindOf(src.type, src.name)}: ${src.name}", model.mode == mode, {
                                    scope.launch {
                                        model.note = "Checking ${src.name} against your library…"
                                        val got = runCatching { api.sourceOrder(route.seriesId, src.id) }.getOrNull()
                                        model.note = when {
                                            got == null -> "Couldn't get ${src.name} from TMDB"
                                            got.second != null -> "${src.name} doesn't match this library's numbering (${got.second}), so it can't be used"
                                            else -> { model.mode = mode; "${src.name}: ${got.first.seasons.sumOf { it.episodes.size }} episodes in ${got.first.seasons.size} seasons" }
                                        }
                                        refresh++
                                    }
                                }, focusKey = "order/src/${src.id}")
                            }
                            AuroraChip("Custom", model.mode == SeriesOrder.CUSTOM, { model.mode = SeriesOrder.CUSTOM; model.note = null }, focusKey = "order/custom")
                        }
                        Text(modeNote(model, saved, api.order?.automaticLabel(route.seriesId), container.settings.current.autoEpisodeOrder, api.orderSource?.available == true),
                            style = AuroraType.caption, color = colors.onSurfaceMuted)
                        model.note?.let { Text(it, style = AuroraType.caption, color = Color(0xFFF2C94C)) }
                    }
                }
                item(key = "actions") {
                    Row(Modifier.focusRow().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AuroraButton("Save", {
                            val custom = if (model.mode == SeriesOrder.CUSTOM) model.seasons.filter { it.items.isNotEmpty() }
                                .map { s -> CustomSeason(s.number, s.name, s.items.map { it.id }) } else null
                            api.setOrder(route.seriesId, model.mode, custom)
                            container.prewarmer.invalidate()
                            com.auroratv.data.WatchActivity.changed()
                            navigator.pop()
                        }, primary = true, focusRequester = first, focusKey = "order/save")
                        AuroraButton("Reset to Jellyfin's Order", {
                            api.setOrder(route.seriesId, SeriesOrder.JELLYFIN, emptyList())
                            com.auroratv.data.WatchActivity.changed()
                            navigator.pop()
                        }, focusKey = "order/reset")
                        if (api.orderSource?.available == true) AuroraButton(if (model.looking) "Looking…" else "Look Up Orders Again", {
                            val s = model.series ?: return@AuroraButton
                            scope.launch { model.looking = true; runCatching { api.lookUpOrders(s, force = true) }; model.looking = false; refresh++ }
                        }, focusKey = "order/lookup")
                        AuroraButton("Cancel", { navigator.pop() }, focusKey = "order/cancel")
                    }
                }
                if (model.mode == SeriesOrder.CUSTOM) {
                    item(key = "help") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (model.moving == null) "Select an episode to move it. Up/Down moves it one place (into the next season at the edges), Left/Right to the previous or next season, select again to put it down, Back to undo the move."
                                else "Moving \"${model.seasons.flatMap { it.items }.firstOrNull { it.id == model.moving }?.name ?: ""}\". Up/Down: one place · Left/Right: another season · Select: put it down · Back: undo",
                                style = AuroraType.caption, color = colors.onSurfaceMuted, modifier = Modifier.widthIn(max = 1100.dp),
                            )
                            Row(Modifier.focusRow(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AuroraTextField(model.position, { model.position = it.filter(Char::isDigit).take(4) }, "Position in its season", Modifier.width(300.dp), keyboardType = KeyboardType.Number)
                                AuroraButton("Move There", {
                                    val id = model.moving ?: run { model.note = "Select an episode first, then type where it goes"; return@AuroraButton }
                                    val pos = model.position.toIntOrNull() ?: return@AuroraButton
                                    moveToPosition(model, id, pos)
                                    model.moving = null
                                    model.moveTick++
                                }, focusKey = "order/place")
                                AuroraButton("New Season", {
                                    val n = (model.seasons.maxOfOrNull { it.number } ?: 0) + 1
                                    model.seasons += DraftSeason(n, "Season $n", emptyList())
                                }, focusKey = "order/newseason")
                            }
                        }
                    }
                    model.seasons.forEachIndexed { si, season ->
                        item(key = "season-${season.number}") {
                            Text("${season.name ?: "Season ${season.number}"}  ·  ${season.items.size} episode${if (season.items.size == 1) "" else "s"}",
                                style = AuroraType.section, color = colors.onBackground, modifier = Modifier.padding(top = 14.dp))
                        }
                        season.items.forEachIndexed { i, ep ->
                            item(key = "ep-${ep.id}") { EpisodeRow(model, si, i, ep) }
                        }
                    }
                } else {
                    item(key = "preview") {
                        Text("Select Custom to arrange the episodes by hand (it starts from the order shown now).",
                            style = AuroraType.caption, color = colors.onSurfaceFaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(model: OrderEditorModel, seasonIndex: Int, index: Int, ep: BaseItemDto) {
    val colors = Aurora.colors
    val moving = model.moving == ep.id
    FocusSurface(
        onClick = {
            when (model.moving) {
                null -> { model.moving = ep.id; model.before = model.seasons.map { it.number to it.items.toList() } }
                ep.id -> model.moving = null
                else -> {}
            }
        },
        focusKey = "order/ep/${ep.id}",
        scaleOnFocus = false,
        sheen = false,
        modifier = Modifier.widthIn(max = 1100.dp).fillMaxWidth().onPreviewKeyEvent { ev ->
            if (!moving || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (ev.key) {
                Key.DirectionUp -> { step(model, ep.id, -1); true }
                Key.DirectionDown -> { step(model, ep.id, +1); true }
                Key.DirectionLeft -> { jumpSeason(model, ep.id, -1); true }
                Key.DirectionRight -> { jumpSeason(model, ep.id, +1); true }
                Key.Back, Key.Escape -> {
                    model.seasons.forEach { s -> model.before.firstOrNull { it.first == s.number }?.let { (_, items) -> s.items.clear(); s.items.addAll(items) } }
                    model.moving = null; model.moveTick++; true
                }
                else -> false
            }
        },
    ) { focused ->
        val bg = when {
            moving -> Color(0xFFF2C94C)
            focused -> Color.White
            else -> colors.surface.copy(alpha = 0.55f)
        }
        val fg = if (moving || focused) Color.Black else colors.onBackground
        Row(Modifier.background(bg).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}.", style = AuroraType.label, color = fg, modifier = Modifier.width(56.dp))
            Text(ep.name ?: "Episode", style = AuroraType.label, color = fg, modifier = Modifier.weight(1f))
            val aired = model.original[ep.id].orEmpty()
            if (aired.isNotBlank()) Text("Jellyfin: $aired", style = AuroraType.caption, color = if (moving || focused) Color(0xFF333333) else colors.onSurfaceFaint)
        }
    }
}

/** Where the episode [id] is: season index and index in it. */
private fun locate(model: OrderEditorModel, id: String): Pair<Int, Int>? {
    model.seasons.forEachIndexed { si, s -> val i = s.items.indexOfFirst { it.id == id }; if (i >= 0) return si to i }
    return null
}

/** One place up or down; past a season's edge it joins the neighbouring season (at its near end). */
private fun step(model: OrderEditorModel, id: String, delta: Int) {
    val (si, i) = locate(model, id) ?: return
    val season = model.seasons[si]
    val target = i + delta
    if (target in season.items.indices) {
        val item = season.items.removeAt(i); season.items.add(target, item)
    } else {
        val next = model.seasons.getOrNull(si + delta) ?: return
        val item = season.items.removeAt(i)
        if (delta < 0) next.items.add(item) else next.items.add(0, item)
    }
    model.moveTick++
}

/** To the end of the previous season, or the start of the next one. */
private fun jumpSeason(model: OrderEditorModel, id: String, delta: Int) {
    val (si, i) = locate(model, id) ?: return
    val next = model.seasons.getOrNull(si + delta) ?: return
    val item = model.seasons[si].items.removeAt(i)
    if (delta < 0) next.items.add(item) else next.items.add(0, item)
    model.moveTick++
}

private fun moveToPosition(model: OrderEditorModel, id: String, position: Int) {
    val (si, i) = locate(model, id) ?: return
    val season = model.seasons[si]
    val item = season.items.removeAt(i)
    season.items.add((position - 1).coerceIn(0, season.items.size), item)
}

/** Index of the episode's row in the list (head, modes, actions, help, then a header per season). */
private fun flatIndex(model: OrderEditorModel, id: String): Int? {
    var index = 3
    model.seasons.forEach { s ->
        index++ // the season's heading
        val i = s.items.indexOfFirst { it.id == id }
        if (i >= 0) return index + i
        index += s.items.size
    }
    return null
}

private fun modeNote(model: OrderEditorModel, saved: SeriesOrder?, automatic: String?, autoOn: Boolean, hasKey: Boolean): String = when (model.mode) {
    SeriesOrder.AUTO -> when {
        !autoOn -> "Automatic episode order is off in Settings, so this shows Jellyfin's order."
        automatic != null -> "Automatic: $automatic from TMDB, checked against your library."
        !hasKey -> "Automatic needs a TMDB key (Settings → Ratings). Until then this shows Jellyfin's order."
        model.looking -> "Looking up the orders TMDB lists for this show…"
        saved?.sources?.isEmpty() == true -> "TMDB lists no other orders for this show, so this shows Jellyfin's order."
        saved?.sources != null -> "No intended or DVD order that matches your library was found, so this shows Jellyfin's order."
        else -> "Automatic uses a documented intended order, else the DVD order, when one matches your library."
    }
    SeriesOrder.JELLYFIN -> "Episodes appear in the order Jellyfin lists them."
    SeriesOrder.CUSTOM -> "Your own order, below."
    else -> "This TMDB order, checked against your library."
}
