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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.auroratv.jellyfin.EpisodeSplit
import com.auroratv.jellyfin.SplitPart
import com.auroratv.jellyfin.VirtualEpisodes
import com.auroratv.jellyfin.VirtualIds
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.AuroraChip
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.components.ScreenStart
import com.auroratv.ui.components.formatRuntime
import com.auroratv.ui.components.formatTime
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.nav.focusRow
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch

/** One episode being edited: where it starts and ends in the file (ms), its number and title. */
private class DraftPart(number: Int?, title: String, startMs: Long, endMs: Long) {
    var number by mutableStateOf(number)
    var title by mutableStateOf(title)
    var startMs by mutableStateOf(startMs)
    var endMs by mutableStateOf(endMs)
}

private class SplitEditorModel {
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** The file (whole, with its chapters) and the split's key in the store. */
    var file by mutableStateOf<BaseItemDto?>(null)
    var key: String = ""
    /** The server's own entries for this file, in episode order, when it has several (else empty). */
    var entries by mutableStateOf<List<BaseItemDto>>(emptyList())
    val parts = mutableStateListOf<DraftPart>()
    var note by mutableStateOf<String?>(null)
    var existing by mutableStateOf(false)
}

/**
 * Where the episodes in one file start and end, set by hand (Virtual Episode Splitting): how many
 * episodes, each one's number, title, start and end, with a preview of each start in the player.
 * Saved per server; a removed split is remembered too, so automatic detection doesn't bring it back.
 */
@Composable
fun SplitEditorScreen(route: Route.SplitEditor) {
    val session = LocalSession.current
    val api = session.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("split") { SplitEditorModel() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val colors = Aurora.colors
    val v = api.virtual

    LaunchedEffect(Unit) {
        if (model.loaded) return@LaunchedEffect
        runCatching {
            val file = api.fileItem(route.itemId)
            val store = v?.splits ?: error("Episode splitting isn't available")
            // A group of server entries sharing this file (known, or found among the season's episodes).
            val known = store.forEntry(file.id)
            val siblings = when {
                known != null -> known.second.entries
                file.path != null && file.seriesId != null -> api.episodes(file.seriesId, seasonId = file.seasonId)
                    .filter { it.path == file.path && !VirtualIds.isVirtual(it.id) }.map { it.id }.takeIf { it.size >= 2 }.orEmpty()
                else -> emptyList()
            }
            val entries = siblings.mapNotNull { id -> runCatching { api.fileItem(id) }.getOrNull() }
                .sortedWith(compareBy({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: Int.MAX_VALUE }))
            val key = known?.first ?: entries.firstOrNull()?.id ?: file.id
            val split = store[key]?.takeIf { it.fits(file) }
            val runtime = file.runtimeMs ?: 0L
            model.file = file; model.key = key; model.entries = entries
            model.existing = split?.isSplit == true
            model.parts.clear()
            when {
                split?.isSplit == true -> split.parts.forEach { p ->
                    model.parts += DraftPart(p.number, p.title ?: "", p.startMs, p.endMs)
                }
                else -> {
                    val count = entries.size.takeIf { it >= 2 } ?: split?.count?.takeIf { it >= 2 }
                        ?: file.indexNumberEnd?.let { e -> file.indexNumber?.let { e - it + 1 } }?.takeIf { it >= 2 }
                        ?: VirtualEpisodes.fileNameCount(file.path) ?: 2
                    resetParts(model, count)
                }
            }
            model.loaded = true
        }.onFailure { model.error = it.message ?: "Couldn't load this episode" }
    }
    ScreenFocus(entry, navigator, first, ready = model.loaded)

    val file = model.file
    Box(Modifier.fillMaxSize()) {
        when {
            model.error != null -> ErrorState(model.error!!, onRetry = null)
            !model.loaded || file == null -> LoadingState()
            else -> LazyColumn(
                contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding, top = 56.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "head") {
                    Column(Modifier.widthIn(max = 900.dp)) {
                        Text("Split episodes", style = AuroraType.title, color = colors.onBackground)
                        Text(listOfNotNull(file.seriesName, file.episodeLabel, file.name, formatRuntime(file.runtimeMs)).joinToString("  ·  "),
                            style = AuroraType.bodyStrong, color = colors.onSurfaceMuted)
                        Text("Each episode plays only its part of this file, with its own progress. The file itself is never changed.",
                            style = AuroraType.caption, color = colors.onSurfaceFaint, modifier = Modifier.padding(top = 6.dp))
                        model.note?.let { Text(it, style = AuroraType.caption, color = colors.onSurfaceMuted, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                if (model.entries.isEmpty()) item(key = "count") {
                    Row(Modifier.focusRow(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Episodes in this file: ${model.parts.size}", style = AuroraType.label, color = colors.onBackground, modifier = Modifier.width(260.dp))
                        AuroraButton("Fewer", { if (model.parts.size > 2) resetParts(model, model.parts.size - 1) }, focusRequester = first, focusKey = "split/fewer")
                        AuroraButton("More", { if (model.parts.size < 6) resetParts(model, model.parts.size + 1) }, focusKey = "split/more")
                    }
                }
                model.parts.forEachIndexed { k, part ->
                    item(key = "part$k") {
                        PartEditor(
                            k = k, part = part, model = model,
                            firstFocus = if (k == 0 && model.entries.isNotEmpty()) first else null,
                            onPreview = { atMs -> navigator.push(Route.Player(model.entries.getOrNull(k)?.id ?: file.id, previewAtMs = atMs)) },
                        )
                    }
                }
                item(key = "actions") {
                    Row(Modifier.focusRow().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuroraButton("Save", {
                            val problem = validate(model)
                            if (problem != null) { model.note = problem; return@AuroraButton }
                            v?.splits?.put(model.key, toSplit(model, file))
                            com.auroratv.data.WatchActivity.changed()
                            navigator.pop()
                        }, primary = true, focusKey = "split/save")
                        if (model.existing) AuroraButton("Remove Split", {
                            // Remembered as "not split", so detection doesn't split it again on its own.
                            v?.splits?.put(model.key, EpisodeSplit(file.runTimeTicks ?: 0L, manual = true, count = model.parts.size,
                                reason = "split removed by hand", entries = model.entries.map { it.id }))
                            com.auroratv.data.WatchActivity.changed()
                            navigator.pop()
                        }, focusKey = "split/remove")
                        AuroraButton("Cancel", { navigator.pop() }, focusKey = "split/cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun PartEditor(k: Int, part: DraftPart, model: SplitEditorModel, firstFocus: FocusRequester?, onPreview: (Long) -> Unit) {
    val colors = Aurora.colors
    val file = model.file ?: return
    val runtime = file.runtimeMs ?: 0L
    val chapters = remember(file.id) { file.chapters.map { it.startPositionTicks / 10_000 }.sorted() }
    val entry = model.entries.getOrNull(k)
    Column(
        Modifier.widthIn(max = 1100.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surface.copy(alpha = 0.6f)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            listOfNotNull("Episode ${part.number ?: (k + 1)}", entry?.name, "${formatTime(part.startMs)} – ${formatTime(part.endMs)}", formatRuntime(part.endMs - part.startMs))
                .joinToString("  ·  "),
            style = AuroraType.label, color = colors.onBackground,
        )
        if (entry == null) {
            // Number and title (the server has no entry of its own for this one).
            Row(Modifier.focusRow(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AuroraButton("Number −", { part.number = ((part.number ?: (k + 1)) - 1).coerceAtLeast(0) }, focusRequester = firstFocus, focusKey = "split/$k/n-")
                AuroraButton("Number +", { part.number = (part.number ?: (k + 1)) + 1 }, focusKey = "split/$k/n+")
                AuroraTextField(part.title, { part.title = it }, label = "Title (optional)", modifier = Modifier.width(420.dp))
            }
        }
        TimeRow("Starts", part.startMs, enabled = k > 0, chapters = chapters, max = runtime, focus = if (entry != null) firstFocus else null, key = "split/$k/s") { part.startMs = it; if (k > 0) model.parts[k - 1].endMs = it }
        TimeRow("Ends", part.endMs, enabled = true, chapters = chapters, max = runtime, focus = null, key = "split/$k/e") {
            part.endMs = it
            model.parts.getOrNull(k + 1)?.let { next -> if (next.startMs < it) next.startMs = it }
        }
        Row(Modifier.focusRow(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuroraButton("Preview start", { onPreview(part.startMs) }, focusKey = "split/$k/ps")
            AuroraButton("Preview end", { onPreview((part.endMs - 10_000).coerceAtLeast(part.startMs)) }, focusKey = "split/$k/pe")
        }
    }
}

/** A time with steps (−1 min … +1 min) and a jump to the next chapter mark; [enabled] false shows it fixed. */
@Composable
private fun TimeRow(label: String, valueMs: Long, enabled: Boolean, chapters: List<Long>, max: Long, focus: FocusRequester?, key: String, onChange: (Long) -> Unit) {
    val colors = Aurora.colors
    Row(Modifier.focusRow(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ${formatTime(valueMs)}", style = AuroraType.bodyStrong, color = colors.onBackground, modifier = Modifier.width(190.dp))
        if (!enabled) { Text("(the start of the file)", style = AuroraType.caption, color = colors.onSurfaceFaint); return@Row }
        fun step(d: Long) = onChange((valueMs + d).coerceIn(0, max))
        AuroraChip("−1 min", false, { step(-60_000) }, focusKey = "$key/-60")
        AuroraChip("−10 s", false, { step(-10_000) }, focusKey = "$key/-10")
        AuroraChip("−1 s", false, { step(-1_000) }, focusKey = "$key/-1", modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier)
        AuroraChip("+1 s", false, { step(1_000) }, focusKey = "$key/+1")
        AuroraChip("+10 s", false, { step(10_000) }, focusKey = "$key/+10")
        AuroraChip("+1 min", false, { step(60_000) }, focusKey = "$key/+60")
        if (chapters.isNotEmpty()) AuroraChip("Next chapter", false, {
            // The next chapter mark after this time (wrapping round), for files whose chapters line up with episodes.
            onChange(chapters.firstOrNull { it > valueMs + 500 } ?: chapters.first())
        }, focusKey = "$key/ch")
    }
}

/** Starting values for [count] episodes: from chapters that name them, else the file divided evenly (to adjust). */
private fun resetParts(model: SplitEditorModel, count: Int) {
    val file = model.file ?: return
    val runtime = file.runtimeMs ?: 0L
    val numbers = if (model.entries.size >= 2) model.entries.map { it.indexNumber } else List(count) { k -> file.indexNumber?.plus(k) }
    val titles = if (model.entries.size >= 2) model.entries.map { it.name } else VirtualEpisodes.splitNames(file.name, count) ?: List(count) { k -> if (k == 0) file.name else null }
    val named = VirtualEpisodes.boundariesFromChapters(file.chapters, count, numbers, titles, runtime)
    val chapterGuess = named ?: VirtualEpisodes.suggestFromChapters(file.chapters, count, runtime)
    val starts = chapterGuess ?: (1 until count).map { it * runtime / count }
    model.note = when {
        named != null -> "Starts taken from chapters that name the episodes. Preview them before saving."
        chapterGuess != null -> "Starts taken from the file's chapters (one per episode). Preview them before saving."
        else -> "No chapters mark the episodes, so these are even divisions of the file: preview each start and adjust before saving."
    }
    val bounds = listOf(0L) + starts + runtime
    model.parts.clear()
    for (k in 0 until count) model.parts += DraftPart(numbers.getOrNull(k), titles.getOrNull(k) ?: "", bounds[k], bounds[k + 1])
}

private fun validate(model: SplitEditorModel): String? {
    val runtime = model.file?.runtimeMs ?: return "The file's length isn't known"
    model.parts.forEachIndexed { k, p ->
        if (p.endMs - p.startMs < 30_000) return "Episode ${p.number ?: (k + 1)} is shorter than 30 seconds"
        if (p.endMs > runtime) return "Episode ${p.number ?: (k + 1)} ends after the file does"
        val prev = model.parts.getOrNull(k - 1)
        if (prev != null && p.startMs < prev.endMs) return "Episode ${p.number ?: (k + 1)} starts before the one before it ends"
    }
    return null
}

private fun toSplit(model: SplitEditorModel, file: BaseItemDto) = EpisodeSplit(
    runtimeTicks = file.runTimeTicks ?: 0L,
    parts = model.parts.mapIndexed { k, p ->
        val thumb = VirtualEpisodes.chapterAt(file.chapters, p.startMs)
        SplitPart(p.startMs, p.endMs, number = p.number, title = p.title.trim().takeIf { it.isNotEmpty() && model.entries.isEmpty() }, entryId = model.entries.getOrNull(k)?.id,
            thumbChapter = thumb?.first, thumbTag = thumb?.second)
    },
    manual = true,
    count = model.parts.size,
    reason = "set by hand",
    entries = model.entries.map { it.id },
)
